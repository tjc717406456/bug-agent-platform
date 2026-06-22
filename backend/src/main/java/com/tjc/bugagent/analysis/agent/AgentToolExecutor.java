package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.dbhub.DbhubClient;
import com.tjc.bugagent.dbhub.ReadonlySqlGuard;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 执行 Agent 可调用的只读分析工具。
 */
@Service
public class AgentToolExecutor {
    private static final int MAX_SNIPPET_LINES = 80;
    // grep 原始源码的护栏：单次最多回多少处命中、单行截多长、跳过多大的文件
    private static final int GREP_MAX_MATCHES = 40;
    private static final int GREP_MAX_LINE_LENGTH = 200;
    private static final long GREP_MAX_FILE_BYTES = 1_000_000;
    private final CodeGraphQueryService codeGraphQueryService;
    private final DbhubClient dbhubClient;
    private final ProjectService projectService;

    public AgentToolExecutor(CodeGraphQueryService codeGraphQueryService, DbhubClient dbhubClient, ProjectService projectService) {
        this.codeGraphQueryService = codeGraphQueryService;
        this.dbhubClient = dbhubClient;
        this.projectService = projectService;
    }

    /**
     * 执行 Agent 指定的只读工具。
     */
    public AgentToolResult execute(AgentToolCall call, AgentToolContext context) {
        String action = call.getAction();
        if (isBlank(action)) {
            return AgentToolResult.fail("unknown", "缺少 action");
        }
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
        if ("finish".equals(action)) {
            return AgentToolResult.ok("finish", "Agent 已输出最终报告", call.stringArg("report"));
        }
        return AgentToolResult.fail(action, "不支持的工具: " + action);
    }

    /**
     * 返回 OpenAI tools 字段使用的函数定义。
     */
    public List<Map<String, Object>> toolSchemas() {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        tools.add(toolSchema("search_code", "搜索已索引 Java 方法和 Mapper", properties("keyword"), required("keyword")));
        tools.add(toolSchema("get_code_detail", "读取关键源码片段，可按 nodeId 或 className/methodName 查询", properties("nodeId", "className", "methodName"), required()));
        tools.add(toolSchema("trace_call_chain", "按接口路径重新追调用链、SQL 和表", properties("apiPath"), required()));
        tools.add(toolSchema("search_sql", "搜索 SQL 或 Mapper 节点", properties("keyword"), required("keyword")));
        tools.add(toolSchema("grep_source", "在原始源码全文里 grep 关键词，能命中字符串字面量、枚举值、常量、注解、配置等代码图谱搜不到的内容（如错误提示文案、ResultEnum、魔法值）", properties("keyword"), required("keyword")));
        tools.add(toolSchema("find_callers", "反向查谁调用了某个方法/节点（上游调用者），用于从某方法往上回溯根因，nodeId 来自 search_code/get_code_detail 的结果", properties("nodeId"), required("nodeId")));
        tools.add(toolSchema("search_log", "在本次上传的日志原文里按关键词/traceId 检索匹配行（含上下文），用于深挖初始证据里被截断的日志细节", properties("keyword"), required("keyword")));
        tools.add(toolSchema("describe_tables", "查询表结构、数据量和最近样例", properties("tables"), required("tables")));
        tools.add(toolSchema("query_database", "执行只读 SQL，只允许 SELECT、SHOW、DESC、DESCRIBE、EXPLAIN", properties("sql"), required("sql")));
        tools.add(toolSchema("finish", "证据足够后输出最终定位报告，报告包含问题结论、证据链路、关键代码/SQL/数据证据、根因类型、建议处理人、置信度", properties("report"), required("report")));
        return tools;
    }

    private Map<String, Object> toolSchema(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        parameters.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private Map<String, Object> properties(String... names) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (String name : names) {
            Map<String, Object> property = new LinkedHashMap<String, Object>();
            property.put("type", "nodeId".equals(name) ? "integer" : "string");
            property.put("description", propertyDescription(name));
            properties.put(name, property);
        }
        return properties;
    }

    private List<String> required(String... names) {
        return Arrays.asList(names);
    }

    private String propertyDescription(String name) {
        // 各参数含义直接用字段名说明，思考过程走 message content，不进 schema
        if ("keyword".equals(name)) {
            return "方法名/类名/字段名关键字";
        }
        if ("nodeId".equals(name)) {
            return "代码节点ID";
        }
        if ("className".equals(name)) {
            return "全限定类名";
        }
        if ("methodName".equals(name)) {
            return "方法名";
        }
        if ("apiPath".equals(name)) {
            return "接口路径，如 /xxx/yyy";
        }
        if ("tables".equals(name)) {
            return "表名，多个用逗号分隔";
        }
        if ("sql".equals(name)) {
            return "只读 SQL，仅 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN";
        }
        if ("report".equals(name)) {
            return "最终定位报告";
        }
        return name;
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
        if (isBlank(context.getLogText())) {
            return AgentToolResult.empty("search_log", "本次分析未提供日志");
        }
        String[] lines = context.getLogText().split("\\r?\\n");
        String needle = keyword.toLowerCase();
        StringBuilder evidence = new StringBuilder();
        int matched = 0;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].toLowerCase().contains(needle)) {
                continue;
            }
            evidence.append('L').append(index + 1).append(": ").append(trimLine(lines[index].trim())).append('\n');
            if (++matched >= GREP_MAX_MATCHES) {
                evidence.append("...（命中超过 ").append(GREP_MAX_MATCHES).append(" 行已截断，请用更精确的关键词）\n");
                return AgentToolResult.ok("search_log", "日志命中 " + matched + " 行（已截断）", evidence.toString());
            }
        }
        if (matched == 0) {
            return AgentToolResult.empty("search_log", "日志里未匹配到: " + keyword);
        }
        return AgentToolResult.ok("search_log", "日志命中 " + matched + " 行", evidence.toString());
    }

    private boolean isSearchableSource(Path path) {
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
            return AgentToolResult.fail("describe_tables", "项目未配置 dbhub 数据源");
        }
        List<String> tables = parseTables(call.getArguments().get("tables"));
        if (tables.isEmpty()) {
            return AgentToolResult.fail("describe_tables", "缺少 tables");
        }
        String evidence = dbhubClient.describeTables(context.getDatasource().getDbhubKey(), tables);
        return AgentToolResult.ok("describe_tables", "已查询表结构: " + tables, evidence);
    }

    private AgentToolResult queryDatabase(AgentToolCall call, AgentToolContext context) {
        if (context.getDatasource() == null) {
            return AgentToolResult.fail("query_database", "项目未配置 dbhub 数据源");
        }
        String sql = call.stringArg("sql");
        if (!ReadonlySqlGuard.isReadonly(sql)) {
            return AgentToolResult.fail("query_database", "只允许只读 SQL: " + sql);
        }
        String evidence = dbhubClient.queryReadonly(context.getDatasource().getDbhubKey(), sql);
        return AgentToolResult.ok("query_database", "已执行只读 SQL", evidence);
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

    /**
     * 供初始证据预取源码片段使用，可指定较小的窗口以控制 token。
     */
    public String readSourceSnippet(CodeNode node, AgentToolContext context, int maxLines) {
        return readSnippet(node, context, maxLines);
    }

    /**
     * 按"类全限定名 + 行号"读源码，用于异常堆栈栈帧的精确定位。
     * 先在图谱里按类名找真实文件路径，找不到再按全限定名推断源码路径兜底。
     */
    public String readSourceAtClassLine(AgentToolContext context, String className, Integer lineNo, int maxLines) {
        if (isBlank(className) || lineNo == null) {
            return "无定位信息";
        }
        CodeNode located = firstNodeWithFile(
                codeGraphQueryService.searchNodesByClassName(context.getProjectId(), context.getVersionId(), className));
        CodeNode node = new CodeNode();
        node.setLineNo(lineNo);
        if (located != null) {
            node.setName(located.getName());
            node.setFilePath(located.getFilePath());
            node.setQualifiedName(located.getQualifiedName());
        } else {
            // 内部类/lambda 的 $ 部分对应同一个源文件，去掉再推断路径
            String stripped = stripInnerClass(className);
            node.setName(stripped);
            node.setQualifiedName(stripped + ".__stackframe");
            node.setFilePath(stripped.replace('.', '/') + ".java");
        }
        return readSnippet(node, context, maxLines);
    }

    private CodeNode firstNodeWithFile(List<CodeNode> nodes) {
        if (nodes == null) {
            return null;
        }
        for (CodeNode node : nodes) {
            if (node.getFilePath() != null) {
                return node;
            }
        }
        return null;
    }

    private String stripInnerClass(String className) {
        int dollar = className.indexOf('$');
        return dollar >= 0 ? className.substring(0, dollar) : className;
    }

    private String formatNodeWithSnippet(CodeNode node, AgentToolContext context) {
        return formatNode(node) + "\n源码片段:\n" + readSnippet(node, context, MAX_SNIPPET_LINES);
    }

    private String readSnippet(CodeNode node, AgentToolContext context, int maxLines) {
        if (node.getFilePath() == null || node.getLineNo() == null) {
            return "无源码定位信息";
        }
        try {
            Path path = resolveSourcePath(node.getFilePath(), node.getQualifiedName(), context);
            // 只读定位窗口内的行，避免大文件一次性读入内存撑爆堆
            int start = Math.max(0, node.getLineNo() - 8);
            StringBuilder snippet = new StringBuilder();
            try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
                List<String> window = stream.skip(start).limit(maxLines).collect(Collectors.toList());
                for (int index = 0; index < window.size(); index++) {
                    snippet.append(start + index + 1).append(": ").append(window.get(index)).append("\n");
                }
            }
            return snippet.length() == 0 ? "无源码内容" : snippet.toString();
        } catch (Exception exception) {
            return "读取源码失败: " + exception.getMessage();
        }
    }

    private Path resolveSourcePath(String filePath, String qualifiedName, AgentToolContext context) throws IOException {
        // 收集本次允许触达的根目录，任何解析结果都必须落在其中，杜绝路径遍历
        List<Path> allowedRoots = allowedRoots(context);

        Path directPath = resolveExistingPath(filePath);
        if (directPath != null && isWithinAny(allowedRoots, directPath)) {
            return directPath;
        }
        ProjectVersion version = projectService.getVersion(context.getVersionId());
        if (version != null && !isBlank(version.getSourcePath())) {
            Path sourceRoot = Paths.get(version.getSourcePath()).toAbsolutePath().normalize();
            Path fromVersionRoot = resolveFromSourceRoot(sourceRoot, filePath, qualifiedName);
            if (fromVersionRoot != null && isWithin(sourceRoot, fromVersionRoot)) {
                return fromVersionRoot;
            }
        }
        Path backendRoot = Paths.get("backend").toAbsolutePath().normalize();
        Path backendPath = backendRoot.resolve(filePath).normalize();
        if (Files.exists(backendPath) && isWithin(backendRoot, backendPath)) {
            return backendPath;
        }
        throw new IOException("无法定位源码文件: " + filePath);
    }

    /**
     * 本次执行允许读取的源码根目录：项目版本源码目录 + backend 自身目录。
     */
    private List<Path> allowedRoots(AgentToolContext context) {
        List<Path> roots = new ArrayList<Path>();
        ProjectVersion version = projectService.getVersion(context.getVersionId());
        if (version != null && !isBlank(version.getSourcePath())) {
            roots.add(Paths.get(version.getSourcePath()).toAbsolutePath().normalize());
        }
        roots.add(Paths.get("backend").toAbsolutePath().normalize());
        return roots;
    }

    private boolean isWithinAny(List<Path> roots, Path target) {
        for (Path root : roots) {
            if (isWithin(root, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithin(Path root, Path target) {
        return target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    private Path resolveExistingPath(String filePath) {
        try {
            Path path = Paths.get(filePath);
            Path absolute = path.isAbsolute() ? path.normalize() : path.toAbsolutePath().normalize();
            return Files.exists(absolute) ? absolute : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private Path resolveFromSourceRoot(Path sourceRoot, String filePath, String qualifiedName) {
        String normalized = filePath.replace('\\', '/');
        List<Path> candidates = new ArrayList<Path>();
        candidates.add(sourceRoot.resolve(filePath));
        int javaIndex = normalized.indexOf("src/main/java/");
        if (javaIndex >= 0) {
            candidates.add(sourceRoot.resolve(normalized.substring(javaIndex + "src/main/java/".length())));
        }
        int resourceIndex = normalized.indexOf("src/main/resources/");
        if (resourceIndex >= 0) {
            candidates.add(sourceRoot.resolve(normalized.substring(resourceIndex + "src/main/resources/".length())));
        }
        String className = classQualifiedName(qualifiedName);
        if (!isBlank(className)) {
            candidates.add(sourceRoot.resolve("src/main/java").resolve(className.replace('.', '/') + ".java"));
        }
        for (Path candidate : candidates) {
            Path normalizedCandidate = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalizedCandidate)) {
                return normalizedCandidate;
            }
        }
        return null;
    }


    private String classQualifiedName(String qualifiedName) {
        if (isBlank(qualifiedName)) {
            return null;
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            return qualifiedName;
        }
        return qualifiedName.substring(0, lastDot);
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
        private final Long projectId;
        private final Long versionId;
        private final String apiPath;
        private final ProjectDatasource datasource;
        // 本次分析的完整日志原文，供 search_log 深挖（接口讲解等无日志场景为空）
        private final String logText;

        public AgentToolContext(Long projectId, Long versionId, String apiPath, ProjectDatasource datasource) {
            this(projectId, versionId, apiPath, datasource, null);
        }

        public AgentToolContext(Long projectId, Long versionId, String apiPath, ProjectDatasource datasource, String logText) {
            this.projectId = projectId;
            this.versionId = versionId;
            this.apiPath = apiPath;
            this.datasource = datasource;
            this.logText = logText;
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
    }
}


