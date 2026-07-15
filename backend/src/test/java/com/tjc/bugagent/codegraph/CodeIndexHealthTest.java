package com.tjc.bugagent.codegraph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码索引健康状态测试。
 */
class CodeIndexHealthTest {

    @Test
    void reportsEmptyPartialAndHealthyStates() {
        assertTrue(new CodeIndexHealth(0, 0, false, false).summary().startsWith("EMPTY"));
        assertTrue(new CodeIndexHealth(20, 10, true, false).summary().startsWith("PARTIAL"));
        assertTrue(new CodeIndexHealth(20, 10, true, true).summary().startsWith("HEALTHY"));
    }
}
