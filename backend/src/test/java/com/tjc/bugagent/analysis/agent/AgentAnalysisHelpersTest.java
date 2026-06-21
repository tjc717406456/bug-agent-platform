package com.tjc.bugagent.analysis.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收敛签名归一与确定性错误人话的纯函数测试。
 * 重构后 normalizeFactSignature 归 AgentConvergenceJudge、unknownColumnPlainAnswer 归 PlainAnswerResolver，
 * 测试随之放进同一个包，才够得着这俩包级私有 static 方法。
 */
class AgentAnalysisHelpersTest {

    @Test
    void normalizeFactSignatureCollapsesWhitespaceAndVolatileIds() {
        String a = "nodeId=12   line=34  id=56\ntype=method";
        String b = "nodeid=99 line=1 id=2 type=method";
        // 大小写在调用方已 toLowerCase；这里直接给小写验证 id 归一
        assertEquals(
                AgentConvergenceJudge.normalizeFactSignature(a.toLowerCase()),
                AgentConvergenceJudge.normalizeFactSignature(b.toLowerCase()));
    }

    @Test
    void normalizeFactSignatureTruncatesTo300() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            builder.append('x');
        }
        assertEquals(300, AgentConvergenceJudge.normalizeFactSignature(builder.toString()).length());
    }

    @Test
    void normalizeFactSignatureHandlesNull() {
        assertEquals("", AgentConvergenceJudge.normalizeFactSignature(null));
    }

    @Test
    void unknownColumnPlainAnswerExtractsTableAndColumn() {
        String text = "java.sql.SQLException: Unknown column 'nick_name' in 'field list'\n"
                + "执行 SQL: select id, nick_name from `sys_user` where id = 1";
        String answer = PlainAnswerResolver.unknownColumnPlainAnswer(text);
        assertNotNull(answer);
        assertTrue(answer.contains("sys_user.nick_name"), answer);
    }

    @Test
    void unknownColumnPlainAnswerFallsBackWhenNoFromClause() {
        String answer = PlainAnswerResolver.unknownColumnPlainAnswer("Unknown column 'age' in 'field list'");
        assertNotNull(answer);
        assertTrue(answer.contains("相关表.age"), answer);
    }

    @Test
    void unknownColumnPlainAnswerReturnsNullWhenNoMatch() {
        assertNull(PlainAnswerResolver.unknownColumnPlainAnswer("NullPointerException at line 12"));
        assertNull(PlainAnswerResolver.unknownColumnPlainAnswer(null));
    }
}
