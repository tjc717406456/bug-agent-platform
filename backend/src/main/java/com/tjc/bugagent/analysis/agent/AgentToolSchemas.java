package com.tjc.bugagent.analysis.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具 Schema 兼容门面。真实定义由 AgentToolRegistry 中的工具元数据生成。
 */
@Component
public class AgentToolSchemas {
    private final AgentToolRegistry registry;

    public AgentToolSchemas(AgentToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回完整工具定义。
     */
    public List<Map<String, Object>> toolSchemas() {
        return registry.definitions(true);
    }

    /**
     * 按运行阶段返回工具定义。
     */
    public List<Map<String, Object>> toolSchemas(boolean allowVerification) {
        return registry.definitions(allowVerification);
    }
}
