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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
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
    private final SourceReader sourceReader;
    private final AgentToolSchemas agentToolSchemas;

    public AgentToolExecutor(CodeGraphQueryService codeGraphQueryService, DbhubClient dbhubClient,
                             ProjectService projectService, SourceReader sourceReader, AgentToolSchemas agentToolSchemas) {
        this.codeGraphQueryService = codeGraphQueryService;
        this.dbhubClient = dbhubClient;
        this.projectService = projectService;
        this.sourceReader = sourceReader;
        this.agentToolSchemas = agentToolSchemas;
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
     * 返回 OpenAI tools 字段使用的函数定义（全量）。
     */
    public List<Map<String, Object>> toolSchemas() {
        return agentToolSchemas.toolSchemas();
    }

    /**
     * 分阶段工具集，定义见 {@link AgentToolSchemas}。
     */
    public List<Map<String, Object>> toolSchemas(boolean allowQueryDatabase) {
        return agentToolSchemas.toolSchemas(allowQueryDatabase);
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

    private String formatNodeWithSnippet(CodeNode node, AgentToolContext context) {
        return formatNode(node) + "\n源码片段:\n"
                + sourceReader.readSnippet(node, context.getProjectId(), context.getVersionId(), MAX_SNIPPET_LINES);
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


