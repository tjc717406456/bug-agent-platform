package com.tjc.bugagent.dbhub;

/**
 * 只读 SQL 校验：仅放行单条 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN 语句。
 * 抽成共享工具，避免 AgentToolExecutor 与 DbhubQueryService 各维护一份逻辑导致不一致。
 */
public final class ReadonlySqlGuard {

    private ReadonlySqlGuard() {
    }

    public static boolean isReadonly(String sql) {
        String normalized = sql == null ? "" : sql.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        // 允许结尾一个分号，但拒绝多语句（防止 SELECT ...; DROP ... 这类拼接）
        if (normalized.contains(";") && normalized.replaceAll(";\\s*$", "").contains(";")) {
            return false;
        }
        return normalized.startsWith("select ")
                || normalized.startsWith("show ")
                || normalized.startsWith("desc ")
                || normalized.startsWith("describe ")
                || normalized.startsWith("explain ");
    }
}
