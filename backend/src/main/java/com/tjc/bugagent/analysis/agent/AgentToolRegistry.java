package com.tjc.bugagent.analysis.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Agent 工具注册表，统一负责 Schema、参数校验、授权、缓存和执行。
 */
@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<String, AgentTool>();
    private volatile List<Map<String, Object>> fullDefinitions;
    private volatile List<Map<String, Object>> discoveryDefinitions;

    public synchronized void register(AgentTool tool) {
        tools.put(tool.name(), tool);
        fullDefinitions = null;
        discoveryDefinitions = null;
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public boolean isConcurrencySafe(String name) {
        AgentTool tool = tools.get(name);
        return tool != null && tool.concurrencySafe();
    }

    public List<Map<String, Object>> definitions(boolean allowVerification) {
        List<Map<String, Object>> cached = allowVerification ? fullDefinitions : discoveryDefinitions;
        if (cached != null) {
            return cached;
        }
        List<AgentTool> ordered = new ArrayList<AgentTool>(tools.values());
        Collections.sort(ordered, (left, right) -> left.name().compareTo(right.name()));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AgentTool tool : ordered) {
            if (!allowVerification && tool.phase() == AgentToolPhase.VERIFICATION) {
                continue;
            }
            result.add(schema(tool));
        }
        result = Collections.unmodifiableList(result);
        if (allowVerification) {
            fullDefinitions = result;
        } else {
            discoveryDefinitions = result;
        }
        return result;
    }

    /**
     * 按执行作用域过滤模型可见工具，避免子 Agent 看见无权调用的能力后反复试错。
     */
    public List<Map<String, Object>> definitions(boolean allowVerification, ProjectExecutionScope scope) {
        if (scope == null || scope.getAllowedTools().isEmpty()) {
            return definitions(allowVerification);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AgentTool tool : tools.values()) {
            if ((!allowVerification && tool.phase() == AgentToolPhase.VERIFICATION)
                    || !scope.allowsTool(tool.name())) {
                continue;
            }
            result.add(schema(tool));
        }
        return Collections.unmodifiableList(result);
    }

    public AgentToolResult execute(AgentToolCall call, AgentToolExecutor.AgentToolContext context) {
        String name = call == null ? null : call.getAction();
        if (name == null || name.trim().isEmpty()) {
            return AgentToolResult.fail("unknown", "缺少 action");
        }
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return AgentToolResult.fail(name, "不支持的工具: " + name);
        }
        ProjectExecutionScope scope = context.getScope();
        if (scope != null && !scope.allowsTool(name)) {
            return AgentToolResult.fail(name, "当前任务无权使用工具: " + name);
        }
        for (String required : tool.required()) {
            Object value = call.getArguments().get(required);
            if (value == null || String.valueOf(value).trim().isEmpty()) {
                return AgentToolResult.fail(name, "缺少 " + required);
            }
        }
        String cacheKey = name + "|" + new TreeMap<String, Object>(call.getArguments()).toString();
        if (tool.cacheable()) {
            AgentToolResult cached = context.cachedResult(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        AgentToolResult result = tool.execute(call, context);
        if (tool.cacheable() && result != null && result.isOk()) {
            context.cacheResult(cacheKey, result);
        }
        return result;
    }

    private Map<String, Object> schema(AgentTool tool) {
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("type", "object");
        parameters.put("properties", tool.properties());
        parameters.put("required", tool.required());
        parameters.put("additionalProperties", false);
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", parameters);
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }
}
