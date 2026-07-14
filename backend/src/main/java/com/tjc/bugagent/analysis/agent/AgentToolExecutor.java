package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.dbhub.DbhubClient;
import com.tjc.bugagent.dbhub.ReadonlySqlGuard;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 执行 Agent 可调用的只读分析工具。
 */
@Service
public class AgentToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);
    private static final int MAX_SNIPPET_LINES = 80;
    // grep 原始源码的护栏：单次最多回多少处命中、单行截多长、跳过多大的文件
    private static final int GREP_MAX_MATCHES = 40;
    private static final int GREP_MAX_LINE_LENGTH = 200;
    private static final long GREP_MAX_FILE_BYTES = 1_000_000;
    // search_log 传 "L<行号>"(如 L39741)时按行号取上下文；只认 L 前缀，裸数字仍当内容搜(避免订单号等被误判)
    private static final Pattern LOG_LINE_REF = Pattern.compile("(?i)^l(\\d+)$");
    private static final int LOG_CONTEXT_LINES = 4;
    // 关键词带这些正则元字符(.* / [..] / \d / ^ $ 等)时按正则匹配日志；纯字面词(订单号/req串)不受影响
    private static final Pattern LOG_REGEX_HINT = Pattern.compile("\\.\\*|\\.\\+|\\\\d|\\\\s|\\\\w|\\[[^]]+]|\\^|\\$|\\(\\?");
    private final CodeGraphQueryService codeGraphQueryService;
    private final DbhubClient dbhubClient;
    private final ProjectService projectService;
    private final SourceReader sourceReader;
    private final AgentToolRegistry toolRegistry;

    public AgentToolExecutor(CodeGraphQueryService codeGraphQueryService, DbhubClient dbhubClient,
                             ProjectService projectService, SourceReader sourceReader, AgentToolRegistry toolRegistry) {
        this.codeGraphQueryService = codeGraphQueryService;
        this.dbhubClient = dbhubClient;
        this.projectService = projectService;
        this.sourceReader = sourceReader;
        this.toolRegistry = toolRegistry;
        registerTools();
    }

    /**
     * 执行 Agent 指定的只读工具。
     * 除 finish 外全是只读幂等的，同一次分析内按 (action, 参数) 缓存成功结果：
     * 并行假设链重复查同一段代码/同一条链路时直接复用，省重复查询和重复 token。
     */
    public AgentToolResult execute(AgentToolCall call, AgentToolContext context) {
        if (toolRegistry != null) {
            return toolRegistry.execute(call, context);
        }
        String action = call.getAction();
        if (isBlank(action)) {
            return AgentToolResult.fail("unknown", "缺少 action");
        }
        if ("finish".equals(action)) {
            return AgentToolResult.ok("finish", "Agent 已输出最终报告", call.stringArg("report"));
        }
        String cacheKey = cacheKey(action, call);
        AgentToolResult cached = context.cachedResult(cacheKey);
        if (cached != null) {
            return cached;
        }
        AgentToolResult result = doExecute(action, call, context);
        // 只缓存成功结果：瞬时失败(库抖动等)缓存了会毒化模型的重试
        if (result != null && result.isOk()) {
            context.cacheResult(cacheKey, result);
        }
        return result;
    }

    /** 参数按 key 排序拼缓存键，同样的参数换个顺序也命中同一条。 */
    private String cacheKey(String action, AgentToolCall call) {
        Map<String, Object> args = call.getArguments();
        return action + "|" + (args == null ? "{}" : new java.util.TreeMap<String, Object>(args).toString());
    }

    private AgentToolResult doExecute(String action, AgentToolCall call, AgentToolContext context) {
        if ("search_code".equals(action)) {
            return searchCode(call, context);
        }
        if ("get_code_detail".equals(action)) {
            return getCodeDetail(call, context);
        }
        if ("trace_call_chain".equals(action)) {
            return traceCallChain(call, context);
        }
        if ("search_sql".equals(action)) {
            return searchSql(call, context);
        }
        if ("grep_source".equals(action)) {
            return grepSource(call, context);
        }
        if ("find_callers".equals(action)) {
            return findCallers(call, context);
        }
        if ("search_log".equals(action)) {
            return searchLog(call, context);
        }
        if ("describe_tables".equals(action)) {
            return describeTables(call, context);
        }
        if ("query_database".equals(action)) {
            return queryDatabase(call, context);
        }
        return AgentToolResult.fail(action, "不支持的工具: " + action);
    }

    /**
     * 返回 OpenAI tools 字段使用的函数定义（全量）。
     */
    public List<Map<String, Object>> toolSchemas() {
        return toolRegistry == null ? Collections.<Map<String, Object>>emptyList() : toolRegistry.definitions(true);
    }

    /**
     * 分阶段工具集，定义见 {@link AgentToolSchemas}。
     */
    public List<Map<String, Object>> toolSchemas(boolean allowQueryDatabase) {
        return toolRegistry == null ? Collections.<Map<String, Object>>emptyList() : toolRegistry.definitions(allowQueryDatabase);
    }

    /** 注册所有内置取证工具，Schema 与执行入口保持同一真相源。 */
    private void registerTools() {
        if (toolRegistry == null) {
            return;
        }
        register("search_code", "按方法名/类名搜已索引的 Java 方法和 Mapper；搜不到字面量时改用 grep_source",
                AgentToolPhase.DISCOVERY, true, true, true, this::searchCode, "keyword");
        register("get_code_detail", "读关键源码片段，按 nodeId 或 className/methodName 查",
                AgentToolPhase.DISCOVERY, true, true, true, this::getCodeDetail);
        register("trace_call_chain", "按接口路径重追调用链、SQL 和表",
                AgentToolPhase.DISCOVERY, true, true, true, this::traceCallChain);
        register("search_sql", "按关键词搜 SQL/Mapper 节点",
                AgentToolPhase.DISCOVERY, true, true, true, this::searchSql, "keyword");
        register("grep_source", "源码全文 grep 字面量、枚举、常量、注解和配置",
                AgentToolPhase.DISCOVERY, true, true, true, this::grepSource, "keyword");
        register("find_callers", "反向查某节点的上游调用者",
                AgentToolPhase.DISCOVERY, true, true, true, this::findCallers, "nodeId");
        register("search_log", "检索本次日志原文；传 L<行号> 可查看附近上下文",
                AgentToolPhase.DISCOVERY, true, true, true, this::searchLog, "keyword");
        register("describe_tables", "查表结构、数据量和最近样例",
                AgentToolPhase.DISCOVERY, true, true, true, this::describeTables, "tables");
        register("query_database", "执行只读 SQL 核对数据",
                AgentToolPhase.VERIFICATION, true, true, true, this::queryDatabase, "sql");
        register("finish", "证据足够后宣布收口，report 可留空或只写结论概要",
                AgentToolPhase.TERMINAL, false, false, false,
                (call, context) -> AgentToolResult.ok("finish", "Agent 已输出最终报告", call.stringArg("report")));
    }

    private void register(String name, String description, AgentToolPhase phase, boolean concurrencySafe,
                          boolean cacheable, boolean contributesEvidence,
                          java.util.function.BiFunction<AgentToolCall, AgentToolContext, AgentToolResult> executor,
                          String... required) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        List<String> parameterNames = new ArrayList<String>(Arrays.asList(required));
        if ("get_code_detail".equals(name)) {
            parameterNames.addAll(Arrays.asList("nodeId", "className", "methodName"));
        } else if ("trace_call_chain".equals(name)) {
            parameterNames.add("apiPath");
        } else if ("finish".equals(name)) {
            parameterNames.add("report");
        }
        for (String parameter : parameterNames) {
            Map<String, Object> property = new LinkedHashMap<String, Object>();
            property.put("type", "nodeId".equals(parameter) ? "integer" : "string");
            property.put("description", parameter);
            properties.put(parameter, property);
        }
        toolRegistry.register(new RegisteredAgentTool(name, description, properties, Arrays.asList(required),
                phase, concurrencySafe, cacheable, contributesEvidence, executor));
    }

    private AgentToolResult searchCode(AgentToolCall call, AgentToolContext context) {
        String keyword = call.stringArg("keyword");
        if (isBlank(keyword)) {
            return AgentToolResult.fail("search_code", "缺少 keyword");
        }
        List<CodeNode> nodes = codeGraphQueryService.searchNodesByName(context.getProjectId(), context.getVersionId(), keyword);
        if (nodes.isEmpty()) {
            return AgentToolResult.empty("search_code", "未找到代码节点: " + keyword + "（可换关键词，或用 grep_source 全文搜字面量/枚举/常量）");
        }
        return AgentToolResult.ok("search_code", "找到 " + nodes.size() + " 个代码节点", formatNodes(nodes));
    }

    private AgentToolResult getCodeDetail(AgentToolCall call, AgentToolContext context) {
        CodeNode node = null;
        Long nodeId = call.longArg("nodeId");
        if (nodeId != null) {
            node = codeGraphQueryService.getNode(context.getProjectId(), context.getVersionId(), nodeId);
        }
        if (node == null) {
            String className = call.stringArg("className");
            String methodName = call.stringArg("methodName");
            List<CodeNode> nodes = isBlank(methodName)
                    ? codeGraphQueryService.searchNodesByClassName(context.getProjectId(), context.getVersionId(), safe(className))
                    : codeGraphQueryService.searchNodesByName(context.getProjectId(), context.getVersionId(), methodName);
            if (!isBlank(className)) {
                nodes = nodes.stream()
                        .filter(item -> item.getQualifiedName() != null && item.getQualifiedName().startsWith(className + "."))
                        .collect(Collectors.toList());
            }
            if (!nodes.isEmpty()) {
                node = nodes.get(0);
            }
        }
        if (node == null) {
            return AgentToolResult.empty("get_code_detail", "未找到源码节点（确认 nodeId 或类名/方法名，或先用 search_code 定位）");
        }
        return AgentToolResult.ok("get_code_detail", "读取源码: " + node.getName(), formatNodeWithSnippet(node, context));
    }

    private AgentToolResult traceCallChain(AgentToolCall call, AgentToolContext context) {
        String apiPath = call.stringArg("apiPath");
        if (isBlank(apiPath)) {
            apiPath = context.getApiPath();
        }
        CodeGraphQueryResult graph = codeGraphQueryService.queryByApiPath(context.getProjectId(), context.getVersionId(), apiPath);
        String evidence = "接口路径: " + apiPath + "\n" +
                "路由节点:\n" + formatNodes(graph.getRouteNodes()) + "\n" +
                "相关节点:\n" + formatNodes(graph.getRelatedNodes()) + "\n" +
                "SQL:\n" + join(graph.getSqlTexts()) + "\n" +
                "表: " + graph.getTables();
        return AgentToolResult.ok("trace_call_chain", "已追踪接口链路", evidence);
    }

    private AgentToolResult searchSql(AgentToolCall call, AgentToolContext context) {
        String keyword = call.stringArg("keyword");
        if (isBlank(keyword)) {
            return AgentToolResult.fail("search_sql", "缺少 keyword");
        }
        List<CodeNode> nodes = codeGraphQueryService.searchSqlNodes(context.getProjectId(), context.getVersionId(), keyword);
        if (nodes.isEmpty()) {
            return AgentToolResult.empty("search_sql", "未找到 SQL 节点: " + keyword + "（可换关键词，或用 grep_source 在 mapper xml 里搜）");
        }
        return AgentToolResult.ok("search_sql", "找到 " + nodes.size() + " 个 SQL/Mapper 节点", formatNodes(nodes));
    }

    /**
     * 在版本源码目录下全文 grep 关键词，补代码图谱的盲区（字面量、枚举、常量、注解、配置）。
     * 只扫源码类文本、限文件大小、限命中条数，避免把堆撑爆或回一大坨。
     */
    private AgentToolResult grepSource(AgentToolCall call, AgentToolContext context) {
        String keyword = call.stringArg("keyword");
        if (isBlank(keyword)) {
            return AgentToolResult.fail("grep_source", "缺少 keyword");
        }
        ProjectVersion version = projectService.getVersion(context.getVersionId());
        if (version == null || isBlank(version.getSourcePath())) {
            return AgentToolResult.fail("grep_source", "项目源码路径不可用");
        }
        Path root = Paths.get(version.getSourcePath()).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            return AgentToolResult.fail("grep_source", "源码目录不存在");
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).filter(this::isSearchableSource).collect(Collectors.toList());
        } catch (IOException exception) {
            return AgentToolResult.fail("grep_source", "扫描源码失败: " + exception.getMessage());
        }
        String needle = keyword.toLowerCase();
        StringBuilder evidence = new StringBuilder();
        int matched = 0;
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (Exception exception) {
                // 读不了（编码异常/二进制）就跳过这个文件
                continue;
            }
            String relative = root.relativize(file).toString().replace('\\', '/');
            for (int index = 0; index < lines.size(); index++) {
                if (!lines.get(index).toLowerCase().contains(needle)) {
                    continue;
                }
                evidence.append(relative).append(':').append(index + 1).append(": ")
                        .append(trimLine(lines.get(index).trim())).append('\n');
                if (++matched >= GREP_MAX_MATCHES) {
                    evidence.append("...（命中超过 ").append(GREP_MAX_MATCHES).append(" 处已截断，请用更精确的关键词）\n");
                    return AgentToolResult.ok("grep_source", "grep 命中 " + matched + " 处（已截断）", evidence.toString());
                }
            }
        }
        if (matched == 0) {
            return AgentToolResult.empty("grep_source", "源码里未匹配到: " + keyword);
        }
        return AgentToolResult.ok("grep_source", "grep 命中 " + matched + " 处", evidence.toString());
    }

    /**
     * 反向查某节点的上游调用者。空结果不算失败——说明它是入口或没有被索引到的调用方，这本身就是有用信息。
     */
    private AgentToolResult findCallers(AgentToolCall call, AgentToolContext context) {
        Long nodeId = call.longArg("nodeId");
        if (nodeId == null) {
            return AgentToolResult.fail("find_callers", "缺少 nodeId");
        }
        CodeNode node = codeGraphQueryService.getNode(context.getProjectId(), context.getVersionId(), nodeId);
        if (node == null) {
            return AgentToolResult.empty("find_callers", "未找到节点: " + nodeId + "（nodeId 应来自 search_code/get_code_detail 的结果）");
        }
        List<CodeNode> callers = codeGraphQueryService.findCallers(nodeId);
        if (callers.isEmpty()) {
            return AgentToolResult.ok("find_callers", node.getName() + " 没有已知调用者（可能是入口或调用未被索引）", "无");
        }
        return AgentToolResult.ok("find_callers", "找到 " + callers.size() + " 个调用者: " + node.getName(), formatNodes(callers));
    }

    /**
     * 在本次日志原文里按关键词检索匹配行，深挖初始证据里被截断的日志细节。
     */
    private AgentToolResult searchLog(AgentToolCall call, AgentToolContext context) {
        String keyword = call.stringArg("keyword");
        if (isBlank(keyword)) {
            return AgentToolResult.fail("search_log", "缺少 keyword");
        }
        if (context.getLogPath() == null && isBlank(context.getLogText())) {
            return AgentToolResult.empty("search_log", "本次分析未提供日志");
        }
        // 关键词是 "L<行号>"(结果里的显示行号标签)时，按行号取该行±上下文，省得模型把标签当内容搜
        Matcher lineRef = LOG_LINE_REF.matcher(keyword.trim());
        if (lineRef.matches()) {
            return readLogAround(context, Integer.parseInt(lineRef.group(1)));
        }
        String needle = keyword.toLowerCase();
        // 关键词带正则特征且能编译时按正则匹配——模型爱用"时间.*设备"这种过滤日志，直接支持；纯字面词仍走 contains
        Pattern regex = compileLogRegex(keyword);
        String mode = regex != null ? "（正则）" : "";
        StringBuilder evidence = new StringBuilder();
        int[] matched = {0};
        boolean[] truncated = {false};
        try {
            // 流式逐行匹配，命中到上限即停（不读完），避免把大日志整坨读进内存
            forEachLogLine(context, (lineNo, line) -> {
                boolean hit = regex != null ? regex.matcher(line).find() : line.toLowerCase().contains(needle);
                if (!hit) {
                    return true;
                }
                evidence.append('L').append(lineNo).append(": ").append(trimLine(line.trim())).append('\n');
                if (++matched[0] >= GREP_MAX_MATCHES) {
                    truncated[0] = true;
                    return false;
                }
                return true;
            });
        } catch (IOException exception) {
            return AgentToolResult.fail("search_log", "读取日志失败: " + exception.getMessage());
        }
        if (truncated[0]) {
            evidence.append("...（命中超过 ").append(GREP_MAX_MATCHES).append(" 行已截断，请用更精确的关键词）\n");
            return AgentToolResult.ok("search_log", "日志命中 " + matched[0] + " 行（已截断）" + mode, evidence.toString());
        }
        if (matched[0] == 0) {
            return AgentToolResult.empty("search_log", "日志里未匹配到: " + keyword + mode);
        }
        return AgentToolResult.ok("search_log", "日志命中 " + matched[0] + " 行" + mode, evidence.toString());
    }

    /** 逐行遍历日志：优先按 logPath 流式读文件，否则用粘贴的 logText；consumer 返回 false 提前中止。 */
    private void forEachLogLine(AgentToolContext context, LogLineConsumer consumer) throws IOException {
        String path = context.getLogPath();
        if (path != null) {
            try (BufferedReader reader = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    if (!consumer.accept(++lineNo, line)) {
                        return;
                    }
                }
            }
            return;
        }
        String text = context.getLogText();
        if (isBlank(text)) {
            return;
        }
        String[] lines = text.split("\\r?\\n");
        for (int index = 0; index < lines.length; index++) {
            if (!consumer.accept(index + 1, lines[index])) {
                return;
            }
        }
    }

    /** 行遍历回调：返回 false 中止遍历。 */
    private interface LogLineConsumer {
        boolean accept(int lineNo, String line);
    }

    /** 关键词含正则元字符且能编译时返回正则，否则返回 null（走字面匹配）；非法正则也退回 null 不报错。 */
    private Pattern compileLogRegex(String keyword) {
        if (!LOG_REGEX_HINT.matcher(keyword).find()) {
            return null;
        }
        try {
            return Pattern.compile(keyword, Pattern.CASE_INSENSITIVE);
        } catch (Exception exception) {
            return null;
        }
    }

    /** 按行号取该行及上下文（流式）；目标行给足长度（参数串常被截断），上下文行正常截断。 */
    private AgentToolResult readLogAround(AgentToolContext context, int lineNo) {
        int start = Math.max(1, lineNo - LOG_CONTEXT_LINES);
        int end = lineNo + LOG_CONTEXT_LINES;
        StringBuilder evidence = new StringBuilder();
        int[] total = {0};
        try {
            forEachLogLine(context, (currentLine, line) -> {
                total[0] = currentLine;
                if (currentLine >= start && currentLine <= end) {
                    String trimmed = line.trim();
                    String shown = currentLine == lineNo
                            ? (trimmed.length() > 800 ? trimmed.substring(0, 800) + "..." : trimmed)
                            : trimLine(trimmed);
                    evidence.append('L').append(currentLine).append(": ").append(shown).append('\n');
                }
                // 有效行号读到窗口末尾即停；越界（行号过大或 < 1）需读到底拿到总行数
                return !(lineNo >= 1 && currentLine >= end);
            });
        } catch (IOException exception) {
            return AgentToolResult.fail("search_log", "读取日志失败: " + exception.getMessage());
        }
        if (lineNo < 1 || lineNo > total[0]) {
            return AgentToolResult.empty("search_log", "行号 L" + lineNo + " 超出日志范围（共 " + total[0] + " 行）");
        }
        return AgentToolResult.ok("search_log", "日志 L" + lineNo + " 附近（±" + LOG_CONTEXT_LINES + " 行）", evidence.toString());
    }

    private boolean isSearchableSource(Path path) {
        // 只搜 src 目录下的源码：排除 target/build 编译产物与 dbchange 等，避免重复命中翻倍噪声、误当两处证据
        String normalized = path.toString().replace('\\', '/').toLowerCase();
        if (!normalized.contains("/src/")) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase();
        boolean source = name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".yml")
                || name.endsWith(".yaml") || name.endsWith(".properties") || name.endsWith(".sql");
        if (!source) {
            return false;
        }
        try {
            return Files.size(path) <= GREP_MAX_FILE_BYTES;
        } catch (IOException exception) {
            return false;
        }
    }

    private String trimLine(String line) {
        return line.length() > GREP_MAX_LINE_LENGTH ? line.substring(0, GREP_MAX_LINE_LENGTH) + "..." : line;
    }

    private AgentToolResult describeTables(AgentToolCall call, AgentToolContext context) {
        if (context.getDatasource() == null) {
            return AgentToolResult.fail("describe_tables", "项目未配置 dbhub 数据源，本次无法查库；请基于代码、SQL 与日志证据定位");
        }
        List<String> tables = parseTables(call.getArguments().get("tables"));
        if (tables.isEmpty()) {
            return AgentToolResult.fail("describe_tables", "缺少 tables");
        }
        if (context.getScope() != null && !context.getScope().allowsTables(tables)) {
            return AgentToolResult.fail("describe_tables", "请求包含项目白名单之外的表");
        }
        String evidence = dbhubClient.describeTables(context.getDatasource().getDbhubKey(), tables);
        return AgentToolResult.ok("describe_tables", "已查询表结构: " + tables, evidence);
    }

    private AgentToolResult queryDatabase(AgentToolCall call, AgentToolContext context) {
        if (context.getDatasource() == null) {
            return AgentToolResult.fail("query_database", "项目未配置 dbhub 数据源，本次无法查库；请基于代码、SQL 与日志证据定位");
        }
        String sql = call.stringArg("sql");
        if (!ReadonlySqlGuard.isReadonly(sql)) {
            return AgentToolResult.fail("query_database", "只允许只读 SQL，请改用 SELECT/SHOW/DESC 重写: " + sql);
        }
        if (context.getScope() != null && !context.getScope().allowsSql(sql)) {
            return AgentToolResult.fail("query_database", "SQL 包含项目白名单之外的表，或无法确认表范围");
        }
        // SQL 原文落 info 便于追溯执行了啥；结果正文含业务数据，只在 debug 打
        log.info("query_database 执行 SQL: {}", sql);
        String evidence = dbhubClient.queryReadonly(context.getDatasource().getDbhubKey(), sql);
        log.debug("query_database 结果: {}", evidence);
        // 摘要带上截断的 SQL，前端进度时间线和证据页一眼可见查的是啥
        String summarySql = sql.length() > 160 ? sql.substring(0, 160) + "..." : sql;
        return AgentToolResult.ok("query_database", "已执行只读 SQL: " + summarySql, evidence);
    }

    private List<String> parseTables(Object value) {
        if (value instanceof List) {
            return ((List<?>) value).stream().map(String::valueOf).map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toList());
        }
        return Arrays.stream(safe(value == null ? null : String.valueOf(value)).split("[,，]"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    private String formatNodeWithSnippet(CodeNode node, AgentToolContext context) {
        // 方法节点读整段（花括号配对到结束），免得长方法尾部读不到逼模型 grep 拼凑；其它节点读固定窗口
        String snippet = "METHOD".equals(node.getNodeType())
                ? sourceReader.readMethodSnippet(node, context.getProjectId(), context.getVersionId())
                : sourceReader.readSnippet(node, context.getProjectId(), context.getVersionId(), MAX_SNIPPET_LINES);
        if (snippet != null && snippet.startsWith("无源码定位信息")) {
            snippet = unlocatedNodeFallback(node, context);
        }
        return formatNode(node) + "\n源码片段:\n" + snippet;
    }

    // metadata 是索引期落的 JSON，解析只在无行号兜底时用；ObjectMapper 线程安全，静态共享即可
    private static final com.fasterxml.jackson.databind.ObjectMapper METADATA_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * MAPPER_METHOD/SQL 这类没索引到行号的节点，别甩"无源码定位信息"死胡同（模型只能自己 grep 好几轮补救）：
     * 优先回 metadata 里的 SQL 原文，再按 id="名字" 定位 xml 标签行给出语句片段，一次调用给足。
     */
    private String unlocatedNodeFallback(CodeNode node, AgentToolContext context) {
        StringBuilder result = new StringBuilder();
        String sql = metadataSql(node);
        if (!isBlank(sql)) {
            result.append("SQL 原文(来自索引 metadata):\n").append(sql).append("\n");
        }
        String xmlSnippet = sourceReader.readXmlTagSnippet(node, context.getVersionId(), MAX_SNIPPET_LINES);
        if (!isBlank(xmlSnippet)) {
            result.append("XML 定位(按 id 匹配):\n").append(xmlSnippet);
        }
        return result.length() == 0
                ? "无源码定位信息（该节点未索引行号，可用 grep_source 按名称全文搜索）"
                : result.toString();
    }

    /** 从节点 metadata JSON 里抠 sql 字段，没有或解析不了返回 null。 */
    private String metadataSql(CodeNode node) {
        if (isBlank(node.getMetadataJson())) {
            return null;
        }
        try {
            Map<String, Object> metadata = METADATA_MAPPER.readValue(node.getMetadataJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object sql = metadata.get("sql");
            return sql == null ? null : String.valueOf(sql);
        } catch (Exception exception) {
            return null;
        }
    }

    private String formatNodes(List<CodeNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "无";
        }
        return nodes.stream().map(this::formatNode).collect(Collectors.joining("\n"));
    }

    private String formatNode(CodeNode node) {
        return "nodeId=" + node.getId()
                + ", type=" + node.getNodeType()
                + ", name=" + node.getName()
                + ", qualifiedName=" + node.getQualifiedName()
                + ", file=" + node.getFilePath()
                + ", line=" + node.getLineNo()
                + ", metadata=" + node.getMetadataJson();
    }

    private String join(List<String> items) {
        return items == null || items.isEmpty() ? "无" : String.join("\n", items);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Agent 工具执行需要的运行上下文。
     */
    public static class AgentToolContext {
        // 一次分析内的工具结果缓存：context 每次分析建一个、所有假设链共享，只存成功结果
        private final java.util.concurrent.ConcurrentHashMap<String, AgentToolResult> resultCache =
                new java.util.concurrent.ConcurrentHashMap<String, AgentToolResult>();

        AgentToolResult cachedResult(String key) {
            return resultCache.get(key);
        }

        void cacheResult(String key, AgentToolResult result) {
            resultCache.putIfAbsent(key, result);
        }

        Map<String, AgentToolResult> cachedResultsSnapshot() {
            return new java.util.LinkedHashMap<String, AgentToolResult>(resultCache);
        }

        void restoreCachedResults(Map<String, AgentToolResult> results) {
            if (results != null) {
                resultCache.putAll(results);
            }
        }

        private final Long projectId;
        private final Long versionId;
        private final String apiPath;
        private final ProjectDatasource datasource;
        // 用户直接粘贴的日志文本（通常较小）；大日志走 logPath 流式读，不进内存
        private final String logText;
        // 上传日志文件路径；search_log 按需流式 grep，避免把大文件全文攥在内存里
        private final String logPath;
        private final ProjectExecutionScope scope;

        public AgentToolContext(Long projectId, Long versionId, String apiPath, ProjectDatasource datasource) {
            this(projectId, versionId, apiPath, datasource, null, null);
        }

        public AgentToolContext(Long projectId, Long versionId, String apiPath, ProjectDatasource datasource, String logText) {
            this(projectId, versionId, apiPath, datasource, logText, null);
        }

        public AgentToolContext(Long projectId, Long versionId, String apiPath, ProjectDatasource datasource, String logText, String logPath) {
            this(ProjectExecutionScope.create(null, null, projectId, versionId, datasource), apiPath, logText, logPath);
        }

        public AgentToolContext(ProjectExecutionScope scope, String apiPath, String logText, String logPath) {
            if (scope == null || scope.getProjectId() == null || scope.getVersionId() == null) {
                throw new IllegalArgumentException("Agent 工具执行范围缺少项目或版本");
            }
            this.scope = scope;
            this.projectId = scope.getProjectId();
            this.versionId = scope.getVersionId();
            this.apiPath = apiPath;
            this.datasource = scope.getDatasource();
            this.logText = logText;
            this.logPath = logPath;
        }

        public Long getProjectId() {
            return projectId;
        }

        public Long getVersionId() {
            return versionId;
        }

        public String getApiPath() {
            return apiPath;
        }

        public ProjectDatasource getDatasource() {
            return datasource;
        }

        public String getLogText() {
            return logText;
        }

        public String getLogPath() {
            return logPath;
        }

        public ProjectExecutionScope getScope() {
            return scope;
        }
    }
}
