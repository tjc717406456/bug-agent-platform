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

    @Test
    void rejectsSelectWritingFile() {
        // SELECT 也能写盘：INTO OUTFILE/DUMPFILE 落文件到 DB 服务器磁盘，属写操作必须拦
        assertFalse(ReadonlySqlGuard.isReadonly("select * into outfile '/tmp/x' from users"));
        assertFalse(ReadonlySqlGuard.isReadonly("SELECT a, b INTO DUMPFILE '/tmp/x' FROM t"));
        assertFalse(ReadonlySqlGuard.isReadonly("select *  into   outfile '/tmp/x' from t"));
        // 即便把注释塞在中间也拦得住
        assertFalse(ReadonlySqlGuard.isReadonly("select * /* c */ into outfile '/x' from t"));
        // SELECT ... INTO @var 是赋值给用户变量，仍属只读，别误杀
        assertTrue(ReadonlySqlGuard.isReadonly("select count(*) into @c from users"));
    }

    @Test
    void allowsLeadingCommentsBeforeReadonly() {
        assertTrue(ReadonlySqlGuard.isReadonly("/* hint */ select 1"));
        assertTrue(ReadonlySqlGuard.isReadonly("-- 取数\nselect * from users"));
        assertTrue(ReadonlySqlGuard.isReadonly("# c\n  show tables"));
        assertTrue(ReadonlySqlGuard.isReadonly("/* a */ /* b */ select 1"));
    }

    @Test
    void rejectsWriteHiddenBehindLeadingComment() {
        // 注释开头不能成为绕过前缀白名单的跳板
        assertFalse(ReadonlySqlGuard.isReadonly("/* x */ drop table users"));
        assertFalse(ReadonlySqlGuard.isReadonly("-- x\ndelete from users"));
        // 未闭合块注释视为可疑，直接拒
        assertFalse(ReadonlySqlGuard.isReadonly("/* select 1"));
    }

    @Test
    void allowsShowCreateTable() {
        // 含 "create" 字样但本质只读，不能被写操作判断误伤
        assertTrue(ReadonlySqlGuard.isReadonly("show create table users"));
    }
}
