package com.tjc.bugagent.analysis.agent;

/**
 * Agent 分析链路共用的文本处理小工具，避免各协作类重复实现。
 */
final class AgentTextUtils {

    private AgentTextUtils() {
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 截断超长文本，超出部分用省略号收尾。 */
    static String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /** 任意值转字符串，null 转空串，用于拼接展示。 */
    static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 任意值转字符串，保留 null，用于需要区分"无值"的解析场景。 */
    static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
