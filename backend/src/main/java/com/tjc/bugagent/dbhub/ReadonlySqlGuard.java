package com.tjc.bugagent.dbhub;

import java.util.regex.Pattern;

/**
 * 只读 SQL 校验：仅放行单条 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN 语句。
 * 抽成共享工具，避免 AgentToolExecutor 与 DbhubQueryService 各维护一份逻辑导致不一致。
 */
public final class ReadonlySqlGuard {

    // SELECT 也能写盘：INTO OUTFILE/DUMPFILE 把结果落到 DB 服务器磁盘，是披着只读皮的写操作，必须拦
    private static final Pattern WRITES_FILE = Pattern.compile("\\binto\\s+(outfile|dumpfile)\\b");

    private ReadonlySqlGuard() {
    }

    public static boolean isReadonly(String sql) {
        if (sql == null) {
            return false;
        }
        // 先剥掉前置注释，防 "/* x */ select"、"-- x\nselect" 绕过前缀判断
        String normalized = stripLeadingComments(sql.trim()).toLowerCase().trim();
        if (normalized.isEmpty()) {
            return false;
        }
        // 允许结尾一个分号，但拒绝多语句（防止 SELECT ...; DROP ... 这类拼接）
        if (normalized.contains(";") && normalized.replaceAll(";\\s*$", "").contains(";")) {
            return false;
        }
        if (WRITES_FILE.matcher(normalized).find()) {
            return false;
        }
        return normalized.startsWith("select ")
                || normalized.startsWith("show ")
                || normalized.startsWith("desc ")
                || normalized.startsWith("describe ")
                || normalized.startsWith("explain ");
    }

    /**
     * 剥掉语句最前面的块注释和行注释（可叠多段）。未闭合的块注释视为可疑，返回空串即拒绝。
     */
    private static String stripLeadingComments(String sql) {
        String remaining = sql.trim();
        while (true) {
            if (remaining.startsWith("/*")) {
                int end = remaining.indexOf("*/");
                if (end < 0) {
                    return "";
                }
                remaining = remaining.substring(end + 2).trim();
            } else if (remaining.startsWith("--") || remaining.startsWith("#")) {
                int newline = remaining.indexOf('\n');
                remaining = newline < 0 ? "" : remaining.substring(newline + 1).trim();
            } else {
                return remaining;
            }
        }
    }
}
