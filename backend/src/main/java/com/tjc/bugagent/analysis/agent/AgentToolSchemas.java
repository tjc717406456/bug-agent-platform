package com.tjc.bugagent.analysis.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具的 OpenAI 函数定义。和工具执行分开：这里只声明"有哪些工具、各自怎么用"，
 * 执行逻辑在 AgentToolExecutor。ACI 思想：工具描述里直接写清"什么时候该用我、命中不到该换谁"。
 */
@Component
public class AgentToolSchemas {

    /**
     * 全量工具定义。
     */
    public List<Map<String, Object>> toolSchemas() {
        return toolSchemas(true);
    }

    /**
     * 分阶段工具集：allowQueryDatabase=false 时不放 query_database，
     * 逼模型先用代码/表结构工具理解清楚，再进入查库取证阶段，避免没读代码就盲写 SQL 查错列。
     */
    public List<Map<String, Object>> toolSchemas(boolean allowQueryDatabase) {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        tools.add(toolSchema("search_code", "按方法名/类名搜已索引的 Java 方法和 Mapper；搜不到字面量/枚举/常量时改用 grep_source", properties("keyword"), required("keyword")));
        tools.add(toolSchema("get_code_detail", "读关键源码片段，按 nodeId 或 className/methodName 查；定位根因优先读栈顶那几行", properties("nodeId", "className", "methodName"), required()));
        tools.add(toolSchema("trace_call_chain", "按接口路径重追调用链、SQL 和表，拿全链路骨架", properties("apiPath"), required()));
        tools.add(toolSchema("search_sql", "按关键词搜 SQL/Mapper 节点；搜不到改用 grep_source 在 xml 里搜", properties("keyword"), required("keyword")));
        tools.add(toolSchema("grep_source", "源码全文 grep，命中代码图谱搜不到的字面量/枚举/常量/注解/配置（如错误文案、ResultEnum、魔法值）", properties("keyword"), required("keyword")));
        tools.add(toolSchema("find_callers", "反向查谁调用了某节点（上游调用者），从某方法往上回溯根因；nodeId 来自 search_code/get_code_detail", properties("nodeId"), required("nodeId")));
        tools.add(toolSchema("search_log", "在本次日志原文按关键词/traceId 检索匹配行；想看某行附近被截断的细节，传该行的显示行号 \"L<行号>\"（如 L39741）即可取该行上下文。注意结果里的 L<n> 是显示行号、不是可搜关键词", properties("keyword"), required("keyword")));
        tools.add(toolSchema("describe_tables", "查表结构、数据量、最近样例；写 query_database 前先用它确认列名，别凭空猜", properties("tables"), required("tables")));
        if (allowQueryDatabase) {
            tools.add(toolSchema("query_database", "执行只读 SQL 核对数据（只允许 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN）；先 describe_tables 确认列名再查", properties("sql"), required("sql")));
        }
        tools.add(toolSchema("finish", "证据足够后输出最终定位报告：通俗结论、问题结论、证据链路、关键代码/SQL/数据证据、根因类型、建议处理人、置信度", properties("report"), required("report")));
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
}
