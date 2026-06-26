package com.tjc.bugagent.analysis.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * search_log 流式改造的前后等价验证：同一段日志，分别走「粘贴文本(内存 split)」和「文件路径(流式 readLine)」，
 * 两条路径的 search_log 输出必须逐字一致——证明改成路径流式只省了内存，没改任何行为。
 */
class SearchLogStreamingTest {

    // search_log 不碰这几个依赖，全 null 即可
    private final AgentToolExecutor executor = new AgentToolExecutor(null, null, null, null, null);

    @TempDir
    Path tempDir;

    private AgentToolCall searchCall(String keyword) {
        AgentToolCall call = new AgentToolCall();
        call.setAction("search_log");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("keyword", keyword);
        call.setArguments(args);
        return call;
    }

    private AgentToolResult runPasted(String logText, String keyword) {
        AgentToolExecutor.AgentToolContext ctx =
                new AgentToolExecutor.AgentToolContext(1L, 1L, "/x", null, logText);
        return executor.execute(searchCall(keyword), ctx);
    }

    private AgentToolResult runFile(String logText, String keyword) throws IOException {
        Path file = Files.createTempFile(tempDir, "log", ".log");
        Files.write(file, logText.getBytes(StandardCharsets.UTF_8));
        AgentToolExecutor.AgentToolContext ctx =
                new AgentToolExecutor.AgentToolContext(1L, 1L, "/x", null, null, file.toString());
        return executor.execute(searchCall(keyword), ctx);
    }

    /** 同内容、同关键词，粘贴路径 vs 文件路径，输出必须完全一致。 */
    private void assertEquivalent(String logText, String keyword) throws IOException {
        AgentToolResult pasted = runPasted(logText, keyword);
        AgentToolResult file = runFile(logText, keyword);
        assertEquals(pasted.getSummary(), file.getSummary(), "summary 不一致 keyword=" + keyword);
        assertEquals(pasted.getEvidence(), file.getEvidence(), "evidence 不一致 keyword=" + keyword);
        assertEquals(pasted.isOk(), file.isOk(), "ok 状态不一致 keyword=" + keyword);
    }

    private String sampleLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("2026-06-25 10:00:01 INFO start openMachine deviceCode=A1\n");
        sb.append("2026-06-25 10:00:02 ERROR NullPointerException at RecipeServiceImpl.java:152\n");
        sb.append("2026-06-25 10:00:03 INFO Preparing: SELECT id FROM employee WHERE code=?\n");
        sb.append("2026-06-25 10:00:04 WARN 硬件亮灯发送失败 shelf=B2\n");
        sb.append("2026-06-25 10:00:05 INFO done deviceCode=A1\n");
        return sb.toString();
    }

    @Test
    void plainKeywordEquivalent() throws IOException {
        assertEquivalent(sampleLog(), "deviceCode");
    }

    @Test
    void regexKeywordEquivalent() throws IOException {
        assertEquivalent(sampleLog(), "ERROR.*Exception");
    }

    @Test
    void noMatchEquivalent() throws IOException {
        assertEquivalent(sampleLog(), "不存在的关键词xyz");
    }

    @Test
    void lineRefEquivalent() throws IOException {
        assertEquivalent(sampleLog(), "L3");
    }

    @Test
    void lineRefOutOfRangeEquivalent() throws IOException {
        assertEquivalent(sampleLog(), "L9999");
    }

    @Test
    void truncationEquivalent() throws IOException {
        // 造大量命中行，触发 GREP_MAX_MATCHES 截断，验证两条路径截断行为一致
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("line ").append(i).append(" hitme value=").append(i).append('\n');
        }
        assertEquivalent(sb.toString(), "hitme");
    }

    @Test
    void emptyLogReportsNoLog() {
        AgentToolResult result = runPasted("", "anything");
        assertTrue(result.getSummary().contains("未提供日志"));
    }
}
