package com.tjc.bugagent.analysis;

import com.tjc.bugagent.codegraph.CodeGraphQueryResult;
import com.tjc.bugagent.codegraph.CodeGraphQueryService;
import com.tjc.bugagent.codegraph.CodeNode;
import com.tjc.bugagent.dbhub.DbhubClient;
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

/**
 * 执行 Agent 可调用的只读分析工具。
 */
@Service
public class AgentToolExecutor {
    private static final int MAX_SNIPPET_LINES = 80;
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
     * 返回给模型的工具说明。
     */
    public String toolManual() {
        return "可用工具：\n" +
                "1. search_code {\"keyword\":\"方法名/类名/字段名\"}：搜索已索引 Java 方法和 Mapper。\n" +
                "2. get_code_detail {\"nodeId\":123} 或 {\"className\":\"全限定类名\",\"methodName\":\"方法名\"}：读取关键源码片段。\n" +
                "3. trace_call_chain {\"apiPath\":\"/xxx\"}：重新按接口路径追调用链、SQL 和表。\n" +
                "4. search_sql {\"keyword\":\"表名/字段名/Mapper方法\"}：搜索 SQL 或 Mapper 节点。\n" +
                "5. describe_tables {\"tables\":\"table1,table2\"}：查询表结构、数据量和最近样例。\n" +
                "6. query_database {\"sql\":\"select ...\"}：执行只读 SQL，只允许 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN。\n" +
                "7. finish {\"report\":\"最终定位报告\"}：证据足够后输出报告。";
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
            return AgentToolResult.fail("search_code", "未找到代码节点: " + keyword);
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
            return AgentToolResult.fail("get_code_detail", "未找到源码节点");
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
            return AgentToolResult.fail("search_sql", "未找到 SQL 节点: " + keyword);
        }
        return AgentToolResult.ok("search_sql", "找到 " + nodes.size() + " 个 SQL/Mapper 节点", formatNodes(nodes));
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
        if (!isReadonlySql(sql)) {
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

    private boolean isReadonlySql(String sql) {
        String normalized = safe(sql).trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.contains(";") && normalized.replaceAll(";\\s*$", "").contains(";")) {
            return false;
        }
        return normalized.startsWith("select ")
                || normalized.startsWith("show ")
                || normalized.startsWith("desc ")
                || normalized.startsWith("describe ")
                || normalized.startsWith("explain ");
    }

    private String formatNodeWithSnippet(CodeNode node, AgentToolContext context) {
        return formatNode(node) + "\n源码片段:\n" + readSnippet(node, context);
    }

    private String readSnippet(CodeNode node, AgentToolContext context) {
        if (node.getFilePath() == null || node.getLineNo() == null) {
            return "无源码定位信息";
        }
        try {
            Path path = resolveSourcePath(node.getFilePath(), node.getQualifiedName(), context);
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, node.getLineNo() - 8);
            int end = Math.min(lines.size(), start + MAX_SNIPPET_LINES);
            StringBuilder snippet = new StringBuilder();
            for (int index = start; index < end; index++) {
                snippet.append(index + 1).append(": ").append(lines.get(index)).append("\n");
            }
            return snippet.toString();
        } catch (Exception exception) {
            return "读取源码失败: " + exception.getMessage();
        }
    }

    private Path resolveSourcePath(String filePath, String qualifiedName, AgentToolContext context) throws IOException {
        Path directPath = resolveExistingPath(filePath);
        if (directPath != null) {
            return directPath;
        }
        ProjectVersion version = projectService.getVersion(context.getVersionId());
        if (version != null && !isBlank(version.getSourcePath())) {
            Path sourceRoot = Paths.get(version.getSourcePath()).toAbsolutePath().normalize();
            Path fromVersionRoot = resolveFromSourceRoot(sourceRoot, filePath, qualifiedName);
            if (fromVersionRoot != null) {
                return fromVersionRoot;
            }
        }
        Path backendPath = Paths.get("backend").resolve(filePath).toAbsolutePath().normalize();
        if (Files.exists(backendPath)) {
            return backendPath;
        }
        throw new IOException("无法定位源码文件: " + filePath);
    }

    private Path resolveExistingPath(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (path.isAbsolute()) {
                return Files.exists(path) ? path.normalize() : null;
            }
            Path absolute = path.toAbsolutePath().normalize();
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

        public AgentToolContext(Long projectId, Long versionId, String apiPath, ProjectDatasource datasource) {
            this.projectId = projectId;
            this.versionId = versionId;
            this.apiPath = apiPath;
            this.datasource = datasource;
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
    }
}


