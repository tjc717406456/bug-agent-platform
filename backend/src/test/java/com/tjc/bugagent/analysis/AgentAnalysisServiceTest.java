package com.tjc.bugagent.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAnalysisServiceTest {

    @Test
    void normalizeFactSignatureCollapsesWhitespaceAndVolatileIds() {
        String a = "nodeId=12   line=34  id=56\ntype=method";
        String b = "nodeid=99 line=1 id=2 type=method";
        // 大小写在调用方已 toLowerCase；这里直接给小写验证 id 归一
        assertEquals(
                AgentAnalysisService.normalizeFactSignature(a.toLowerCase()),
                AgentAnalysisService.normalizeFactSignature(b.toLowerCase()));
    }

    @Test
    void normalizeFactSignatureTruncatesTo300() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            builder.append('x');
        }
        assertEquals(300, AgentAnalysisService.normalizeFactSignature(builder.toString()).length());
    }

    @Test
    void normalizeFactSignatureHandlesNull() {
        assertEquals("", AgentAnalysisService.normalizeFactSignature(null));
    }

    @Test
    void unknownColumnPlainAnswerExtractsTableAndColumn() {
        String text = "java.sql.SQLException: Unknown column 'nick_name' in 'field list'\n"
                + "执行 SQL: select id, nick_name from `sys_user` where id = 1";
        String answer = AgentAnalysisService.unknownColumnPlainAnswer(text);
        assertNotNull(answer);
        assertTrue(answer.contains("sys_user.nick_name"), answer);
    }

    @Test
    void unknownColumnPlainAnswerFallsBackWhenNoFromClause() {
        String answer = AgentAnalysisService.unknownColumnPlainAnswer("Unknown column 'age' in 'field list'");
        assertNotNull(answer);
        assertTrue(answer.contains("相关表.age"), answer);
    }

    @Test
    void unknownColumnPlainAnswerReturnsNullWhenNoMatch() {
        assertNull(AgentAnalysisService.unknownColumnPlainAnswer("NullPointerException at line 12"));
        assertNull(AgentAnalysisService.unknownColumnPlainAnswer(null));
    }
}
