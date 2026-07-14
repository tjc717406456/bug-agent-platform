package com.tjc.bugagent.analysis.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void normalizeFactSignatureKeepsFullTextSoSuffixDiffsStayDistinct() {
        // 不再截断：前 300 字相同、后半不同的两条证据必须判为不同事实
        String prefix = repeat('x', 350);
        assertFalse(AgentConvergenceJudge.normalizeFactSignature(prefix + "aaa")
                .equals(AgentConvergenceJudge.normalizeFactSignature(prefix + "bbb")));
    }

    @Test
    void normalizeFactSignatureHandlesNull() {
        assertEquals("", AgentConvergenceJudge.normalizeFactSignature(null));
    }

    @Test
    void trimHeadTailKeepsBothEndsWhenOverLimit() {
        String head = repeat('H', 100);
        String tail = repeat('T', 100);
        String value = head + repeat('M', 500) + tail;
        String result = AgentTextUtils.trimHeadTail(value, 120);
        assertTrue(result.length() <= 120 + "\n...（中间证据已省略）...\n".length(), result);
        // 头尾各保留一段，中间探索证据被挖掉换成标记
        assertTrue(result.startsWith("H"), result);
        assertTrue(result.endsWith("T"), result);
        assertTrue(result.contains("中间证据已省略"), result);
        assertFalse(result.contains("M"), result);
    }

    @Test
    void trimHeadTailReturnsAsIsWhenWithinLimit() {
        String value = "short";
        assertEquals(value, AgentTextUtils.trimHeadTail(value, 100));
        assertNull(AgentTextUtils.trimHeadTail(null, 100));
    }

    private static String repeat(char ch, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(ch);
        }
        return builder.toString();
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

    @Test
    void plainAnswerExtractsMarkdownHeaderContentNotTitle() {
        // 模型用 markdown 标题写法时，简单说要抓标题下一行的正文，而不是 "## 通俗结论" 标题本身
        String report = "## 通俗结论\n\nSQL 查出来的 user_name 字段映射不到实体，用户名全是 null。\n\n## 问题结论\n\n列名与字段名不匹配。";
        String plain = new PlainAnswerResolver().buildPlainAnswer(report, "");
        assertTrue(plain.contains("user_name"), plain);
        assertFalse(plain.contains("##"), plain);
        assertFalse(plain.contains("通俗结论"), plain);
    }

    @Test
    void finalReportWrapsSingleParagraphIntoRequiredStructure() {
        AgentRoundReporter reporter = new AgentRoundReporter();
        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        Map<String, Object> round = new LinkedHashMap<String, Object>();
        round.put("iteration", 25);
        round.put("action", "search_log");
        round.put("toolSummary", "设备未返回ACK");
        round.put("toolEvidence", "09:34:34 sendWithAck等待30秒后返回false");
        rounds.add(round);

        String report = reporter.ensureAnalysisReportFormat("亮灯命令已发出，但设备没有返回ACK。", rounds);

        assertTrue(reporter.isCompleteAnalysisReport(report), report);
        assertTrue(report.contains("【通俗结论】"), report);
        assertTrue(report.contains("【问题结论】"), report);
        assertTrue(report.contains("【证据链路】"), report);
        assertTrue(report.contains("【关键代码/SQL/数据证据】"), report);
        assertTrue(report.contains("09:34:34"), report);
        assertTrue(report.contains("【根因类型】"), report);
        assertTrue(report.contains("【建议处理人】"), report);
        assertTrue(report.contains("【置信度】"), report);
    }

    @Test
    void finalReportKeepsAlreadyCompleteReportUnchanged() {
        AgentRoundReporter reporter = new AgentRoundReporter();
        String report = "【通俗结论】设备无响应\n"
                + "【问题结论】ACK超时\n"
                + "【证据链路】发送后等待30秒\n"
                + "【关键代码/SQL/数据证据】sendWithAck=false\n"
                + "【根因类型】设备通信异常\n"
                + "【建议处理人】设备开发\n"
                + "【置信度】HIGH";

        assertEquals(report, reporter.ensureAnalysisReportFormat(report,
                new ArrayList<Map<String, Object>>()));
    }
}
