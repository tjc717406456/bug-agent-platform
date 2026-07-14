package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.project.ProjectDatasource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 内核测试：工具注册、执行范围、统一 Runner、Hook 和检查点。
 */
class AgentKernelTest {

    @Test
    void registryUsesSingleDefinitionAndExecutionSource() {
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new RegisteredAgentTool("demo", "演示工具", stringProperties("keyword"),
                Collections.singletonList("keyword"), AgentToolPhase.DISCOVERY, true, true, true,
                (call, context) -> AgentToolResult.ok("demo", "ok", call.stringArg("keyword"))));
        AgentToolCall call = new AgentToolCall();
        call.setAction("demo");
        call.setArguments(Collections.<String, Object>singletonMap("keyword", "value"));
        AgentToolExecutor.AgentToolContext context = new AgentToolExecutor.AgentToolContext(
                1L, 2L, "/x", null);

        assertEquals(1, registry.definitions(true).size());
        assertEquals("value", registry.execute(call, context).getEvidence());
        assertTrue(registry.isConcurrencySafe("demo"));
    }

    @Test
    void registryRejectsMissingRequiredParameter() {
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new RegisteredAgentTool("demo", "演示工具", stringProperties("keyword"),
                Collections.singletonList("keyword"), AgentToolPhase.DISCOVERY, true, false, true,
                (call, context) -> AgentToolResult.ok("demo", "ok", "never")));
        AgentToolCall call = new AgentToolCall();
        call.setAction("demo");

        AgentToolResult result = registry.execute(call, new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null));

        assertTrue(result.isHardFailure());
        assertTrue(result.getSummary().contains("keyword"));
    }

    @Test
    void projectScopeEnforcesDatasourceTableWhitelist() {
        ProjectDatasource datasource = new ProjectDatasource();
        datasource.setWhitelistTables("orders, order_item");
        ProjectExecutionScope scope = ProjectExecutionScope.create("task", 7L, 1L, 2L, datasource);

        assertTrue(scope.allowsTables(Arrays.asList("orders", "order_item")));
        assertFalse(scope.allowsTables(Collections.singletonList("users")));
        assertTrue(scope.allowsSql("select o.id from orders o join order_item i on i.order_id=o.id"));
        assertFalse(scope.allowsSql("select * from users"));
        assertFalse(scope.allowsSql("select 1"));
    }

    @Test
    void runnerProducesStructuredResultAndCheckpoint() {
        AiClient aiClient = mock(AiClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        AgentToolCallParser parser = mock(AgentToolCallParser.class);
        ToolFanoutExecutor fanout = mock(ToolFanoutExecutor.class);
        AgentConversation conversation = mock(AgentConversation.class);
        AgentRunner runner = new AgentRunner(aiClient, toolExecutor, parser, fanout, conversation);
        AiToolCallResult response = new AiToolCallResult();
        response.setContent("done");
        response.setTotalTokens(12);
        AgentToolCall finish = new AgentToolCall();
        finish.setAction("finish");
        when(toolExecutor.toolSchemas(anyBoolean())).thenReturn(Collections.<Map<String, Object>>emptyList());
        when(aiClient.chatWithMessagesRequired(any(), any())).thenReturn(response);
        when(parser.parseToolCalls(response)).thenReturn(Collections.singletonList(finish));
        when(parser.findFinish(any())).thenReturn(finish);
        final List<AgentRunCheckpoint> checkpoints = new ArrayList<AgentRunCheckpoint>();
        AtomicInteger afterRuns = new AtomicInteger();
        AgentRunSpec spec = new AgentRunSpec();
        spec.setMessages(new ArrayList<Map<String, Object>>());
        spec.setToolContext(new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null));
        spec.setMaxIterations(3);
        spec.setKeepRecentRounds(2);
        spec.setProgress(new com.tjc.bugagent.analysis.AnalysisProgressListener() {
            public void onStep(String step) { }
            public void onCheckpoint(AgentRunCheckpoint checkpoint) { checkpoints.add(checkpoint); }
        });
        spec.setHooks(Collections.<AgentRunHook>singletonList(new AgentRunHook() {
            public void afterRun(AgentRunContext context) { afterRuns.incrementAndGet(); }
        }));
        spec.setPolicy(new AgentRunPolicy() {
            public AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult ai, AgentToolCall call) {
                return AgentRunDirective.stop("report", AgentStopReason.FINISH_TOOL);
            }
            public String finalizeRun(AgentRunContext context) { return "fallback"; }
        });

        AgentRunResult result = runner.run(spec);

        assertEquals("report", result.getFinalContent());
        assertEquals(AgentStopReason.FINISH_TOOL, result.getStopReason());
        assertEquals(12, result.getTotalTokens());
        assertEquals(1, result.getIterations());
        assertEquals(1, afterRuns.get());
        assertEquals(1, checkpoints.size());
        assertEquals("COMPLETED", checkpoints.get(0).getPhase());
        assertEquals("FINISH_TOOL", checkpoints.get(0).getStopReason());
    }

    @Test
    void checkpointCarriesToolEvents() {
        AgentRunContext context = new AgentRunContext(new ArrayList<Map<String, Object>>(),
                new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null), false);
        context.setIteration(2);
        context.addTokens(30);
        context.getToolEvents().add(new AgentToolEvent(2, "search_code", "OK", "hit", 5));

        AgentRunCheckpoint checkpoint = context.checkpoint("TOOLS_COMPLETED", Collections.<String, Object>emptyMap());

        assertEquals(2, checkpoint.getIteration());
        assertEquals(30, checkpoint.getTotalTokens());
        assertEquals(1, checkpoint.getToolEvents().size());
        assertNotNull(checkpoint.getUpdatedAt());
    }

    private Map<String, Object> stringProperties(String name) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(name, property);
        return properties;
    }
}
