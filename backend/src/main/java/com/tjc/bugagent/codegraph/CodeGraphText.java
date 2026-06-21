package com.tjc.bugagent.codegraph;

/**
 * 代码图谱模块共用的文本处理工具。注意这里的 trim 是纯长度截断（不加省略号），
 * 因为截断结果会直接落库存为节点名/SQL，省略号会污染数据。
 */
final class CodeGraphText {

    private CodeGraphText() {
    }

    /** 按最大长度截断，超出直接截掉，不补省略号。 */
    static String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** null 转空串，并去掉首尾空白。 */
    static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
