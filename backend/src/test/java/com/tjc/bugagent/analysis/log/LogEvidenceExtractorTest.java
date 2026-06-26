package com.tjc.bugagent.analysis.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多信号锚定验证：整文件多接口报错时，按「传入的接口方法名是否在异常栈帧里」挑对那条异常，
 * 不再死抓文件里第一个异常。核心防的是——传 openMachine，但别的接口(queueIn)先炸，结果抓错栈带偏 Agent。
 */
class LogEvidenceExtractorTest {

    private final LogEvidenceExtractor extractor = new LogEvidenceExtractor();

    /** queueIn 先炸、openMachine 后炸，传 openMachine：栈必须是 openMachine 的，errorLines 不含 queueIn。 */
    @Test
    void picksTargetEndpointEvenWhenAnotherErroredFirst() {
        String log = ""
                + "14:00:01.100 [http-nio-8080-exec-2] ERROR c.t.c.GlobalExceptionHandler#handlerSQLException - nested exception is org.apache.ibatis.exceptions.PersistenceException:\n"
                + "### Error querying database. Cause: java.lang.NullPointerException\n"
                + "    at com.tangu.tcmts.service.imp.PadAppServiceImpl.queueIn(PadAppServiceImpl.java:880)\n"
                + "    at com.tangu.tcmts.controller.PadAppController.queueIn(PadAppController.java:120)\n"
                + "14:00:02.200 [http-nio-8080-exec-7] ERROR c.t.c.GlobalExceptionHandler#handlerSQLException - nested exception is org.apache.ibatis.exceptions.PersistenceException:\n"
                + "### Error updating database. Cause: java.lang.IllegalStateException: Cannot determine target DataSource for lookup key [machine_write]\n"
                + "    at com.tangu.tcmts.service.imp.PadAppServiceImpl.openMachine(PadAppServiceImpl.java:1906)\n"
                + "    at com.tangu.tcmts.controller.PadAppController.openMachine(PadAppController.java:267)\n";

        LogClues clues = extractor.extract(log, "/hrs/padApp/openMachine", null);

        assertNotNull(clues.getStackTrace());
        assertTrue(clues.getStackTrace().contains("openMachine"), "栈应锚定 openMachine");
        assertTrue(clues.getStackTrace().contains("machine_write"), "应是 openMachine 那条根因");
        assertFalse(clues.getStackTrace().contains("queueIn"), "不该抓 queueIn 的栈");
        // errorLines 应剔掉 queueIn 异常块的 ERROR，只留 openMachine 的
        String errors = String.join("\n", clues.getErrorLines());
        assertTrue(errors.contains("PersistenceException"), "应保留目标接口的 ERROR");
        // queueIn 块那行 ERROR 被剔除：两条 ERROR 文本一样，靠数量校验只剩 1 条
        assertTrue(clues.getErrorLines().size() == 1, "应只剩 openMachine 那条 ERROR，剔掉 queueIn 的");
    }

    /** 接口名压根没出现在任何栈里：退回抓第一个异常，绝不空着。 */
    @Test
    void fallsBackToFirstExceptionWhenEndpointNotInAnyStack() {
        String log = ""
                + "10:00:01 ERROR x - boom\n"
                + "java.lang.IllegalArgumentException: bad arg\n"
                + "    at com.foo.Bar.doStuff(Bar.java:10)\n";

        LogClues clues = extractor.extract(log, "/api/notexist/whatever", null);

        assertNotNull(clues.getStackTrace());
        assertTrue(clues.getStackTrace().contains("IllegalArgumentException"), "锚不到时退回第一个异常");
    }

    /** 日志只有时分秒、没年月日，requestTime 也要抓得到。 */
    @Test
    void parsesTimeOnlyTimestamp() {
        String log = "14:04:17.648 [exec-1] INFO start\n14:04:18.000 [exec-1] INFO done\n";
        LogClues clues = extractor.extract(log, "/x", null);
        assertEquals("14:04:17.648", clues.getRequestTime());
    }

    /** 带年月日的时间戳仍完整抓取，不被改坏。 */
    @Test
    void parsesFullTimestamp() {
        String log = "2026-06-25 09:12:01.330 [exec-1] INFO start\n";
        LogClues clues = extractor.extract(log, "/x", null);
        assertEquals("2026-06-25 09:12:01.330", clues.getRequestTime());
    }

    /** 不在异常块里的独立业务 ERROR 行，不能被误删。 */
    @Test
    void keepsStandaloneBusinessErrorLines() {
        String log = ""
                + "10:00:01 [exec-1] ERROR c.t.Svc - 硬件亮灯发送失败 shelf=B2\n"
                + "10:00:02 [exec-2] ERROR c.t.GlobalExceptionHandler - nested exception is java.lang.IllegalStateException\n"
                + "    at com.tangu.tcmts.service.imp.PadAppServiceImpl.openMachine(PadAppServiceImpl.java:1906)\n";

        LogClues clues = extractor.extract(log, "/hrs/padApp/openMachine", null);

        String errors = String.join("\n", clues.getErrorLines());
        assertTrue(errors.contains("硬件亮灯发送失败"), "独立业务 ERROR 行必须保留");
    }
}
