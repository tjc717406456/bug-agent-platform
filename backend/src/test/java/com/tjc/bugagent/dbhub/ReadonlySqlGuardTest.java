package com.tjc.bugagent.dbhub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadonlySqlGuardTest {

    @Test
    void allowsReadonlyStatements() {
        assertTrue(ReadonlySqlGuard.isReadonly("select * from users"));
        assertTrue(ReadonlySqlGuard.isReadonly("SELECT id FROM users"));
        assertTrue(ReadonlySqlGuard.isReadonly("  select 1  "));
        assertTrue(ReadonlySqlGuard.isReadonly("show tables"));
        assertTrue(ReadonlySqlGuard.isReadonly("desc users"));
        assertTrue(ReadonlySqlGuard.isReadonly("describe users"));
        assertTrue(ReadonlySqlGuard.isReadonly("explain select * from users"));
    }

    @Test
    void allowsSingleTrailingSemicolon() {
        assertTrue(ReadonlySqlGuard.isReadonly("select 1;"));
        assertTrue(ReadonlySqlGuard.isReadonly("select 1 ;  "));
    }

    @Test
    void rejectsWriteStatements() {
        assertFalse(ReadonlySqlGuard.isReadonly("insert into users values (1)"));
        assertFalse(ReadonlySqlGuard.isReadonly("update users set name = 'x'"));
        assertFalse(ReadonlySqlGuard.isReadonly("delete from users"));
        assertFalse(ReadonlySqlGuard.isReadonly("drop table users"));
        assertFalse(ReadonlySqlGuard.isReadonly("truncate table users"));
    }

    @Test
    void rejectsStackedStatements() {
        assertFalse(ReadonlySqlGuard.isReadonly("select 1; drop table users"));
        assertFalse(ReadonlySqlGuard.isReadonly("select 1; select 2"));
    }

    @Test
    void rejectsBlankOrNull() {
        assertFalse(ReadonlySqlGuard.isReadonly(null));
        assertFalse(ReadonlySqlGuard.isReadonly(""));
        assertFalse(ReadonlySqlGuard.isReadonly("   "));
    }

    @Test
    void rejectsSelectWithoutTrailingSpace() {
        // 必须是 "select " 前缀，"selection ..." 这类不应被误判为只读
        assertFalse(ReadonlySqlGuard.isReadonly("selectfrom users"));
    }
}
