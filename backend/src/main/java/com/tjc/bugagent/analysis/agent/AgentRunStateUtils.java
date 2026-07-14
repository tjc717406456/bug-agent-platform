package com.tjc.bugagent.analysis.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workflow 检查点状态的安全读取工具。
 */
public final class AgentRunStateUtils {
    private AgentRunStateUtils() {
    }

    public static int intValue(Map<String, Object> state, String key) {
        Object value = state == null ? null : state.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public static boolean boolValue(Map<String, Object> state, String key) {
        Object value = state == null ? null : state.get(key);
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rounds(Map<String, Object> state) {
        Object value = state == null ? null : state.get("rounds");
        if (!(value instanceof List)) {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) value) {
            if (item instanceof Map) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }
}
