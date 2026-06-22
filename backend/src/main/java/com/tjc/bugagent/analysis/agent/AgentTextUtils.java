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

    /**
     * 保头留尾截断：超长时保留开头和结尾，中间挖掉用标记占位。
     * 自检场景专用——开头是入口/堆栈/路由的定位证据，结尾是末轮根因查证，可舍的是中间探索轮。
     */
    static String trimHeadTail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        String marker = "\n...（中间证据已省略）...\n";
        int budget = maxLength - marker.length();
        if (budget <= 0) {
            return value.substring(0, maxLength) + "...";
        }
        int headLen = budget / 2;
        int tailLen = budget - headLen;
        return value.substring(0, headLen) + marker + value.substring(value.length() - tailLen);
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
