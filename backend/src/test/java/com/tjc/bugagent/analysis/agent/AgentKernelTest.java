package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

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
    void toolSchemasHideSearchLogWhenNoLogWasProvided() {
        AgentToolRegistry registry = new AgentToolRegistry();
        ProjectService projectService = mock(ProjectService.class);
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        version.setSourcePath(Paths.get(".").toAbsolutePath().normalize().toString());
        when(projectService.getVersion(2L)).thenReturn(version);
        AgentToolExecutor executor = new AgentToolExecutor(null, null, projectService, null, registry);
        AgentToolExecutor.AgentToolContext context = new AgentToolExecutor.AgentToolContext(
                1L, 2L, "/x", null);

        List<Map<String, Object>> definitions = executor.toolSchemas(false, context);
        String names = definitions.toString();

        assertFalse(names.contains("search_log"), names);
        assertTrue(names.contains("read_source"), names);
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
    void projectScopeFiltersSubAgentToolSchemas() {
        AgentToolRegistry registry = new AgentToolRegistry();
        registry.register(new RegisteredAgentTool("search_code", "搜索源码", stringProperties("keyword"),
                Collections.singletonList("keyword"), AgentToolPhase.DISCOVERY, true, true, true,
                (call, context) -> AgentToolResult.ok("search_code", "ok", "code")));
        registry.register(new RegisteredAgentTool("search_log", "搜索日志", stringProperties("keyword"),
                Collections.singletonList("keyword"), AgentToolPhase.DISCOVERY, true, true, true,
                (call, context) -> AgentToolResult.ok("search_log", "ok", "log")));
        ProjectExecutionScope parent = ProjectExecutionScope.create("task", 7L, 1L, 2L,
                (ProjectDatasource) null);
        ProjectExecutionScope logScope = parent.child("task:log", "search_log");

        List<Map<String, Object>> definitions = registry.definitions(false, logScope);

        assertEquals(1, definitions.size());
        Map<String, Object> function = (Map<String, Object>) definitions.get(0).get("function");
        assertEquals("search_log", function.get("name"));
        assertFalse(logScope.allowsTool("search_code"));
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
        when(toolExecutor.toolSchemas(anyBoolean(), any(ProjectExecutionScope.class)))
                .thenReturn(Collections.<Map<String, Object>>emptyList());
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
    void runnerStopsBeforeModelRequestWhenToolContextWasCancelled() {
        AiClient aiClient = mock(AiClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        AgentToolCallParser parser = mock(AgentToolCallParser.class);
        ToolFanoutExecutor fanout = mock(ToolFanoutExecutor.class);
        AgentConversation conversation = mock(AgentConversation.class);
        AgentRunner runner = new AgentRunner(aiClient, toolExecutor, parser, fanout, conversation);
        AgentToolExecutor.AgentToolContext toolContext = new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null);
        toolContext.cancel();
        AgentRunSpec spec = new AgentRunSpec();
        spec.setMessages(new ArrayList<Map<String, Object>>());
        spec.setToolContext(toolContext);
        spec.setMaxIterations(1);
        spec.setPolicy(new AgentRunPolicy() {
            public AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult ai, AgentToolCall call) {
                return AgentRunDirective.stop("finish", AgentStopReason.FINISH_TOOL);
            }

            public AgentRunDirective afterTools(AgentRunContext context, AiToolCallResult ai,
                                                List<AgentToolCall> calls, List<AgentToolResult> results) {
                return AgentRunDirective.continueRun();
            }

            public String finalizeRun(AgentRunContext context) { return "fallback"; }
        });

        assertThrows(AnalysisCancelledException.class, () -> runner.run(spec));
        verify(aiClient, times(0)).chatWithMessagesRequired(any(), any());
    }

    @Test
    void runnerAcceptsFinishBeforeApplyingTokenBudget() {
        AiClient aiClient = mock(AiClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        AgentToolCallParser parser = mock(AgentToolCallParser.class);
        ToolFanoutExecutor fanout = mock(ToolFanoutExecutor.class);
        AgentConversation conversation = mock(AgentConversation.class);
        AgentRunner runner = new AgentRunner(aiClient, toolExecutor, parser, fanout, conversation);
        AiToolCallResult response = new AiToolCallResult();
        response.setTotalTokens(120);
        AgentToolCall finish = new AgentToolCall();
        finish.setAction("finish");
        when(toolExecutor.toolSchemas(anyBoolean(), any(ProjectExecutionScope.class)))
                .thenReturn(Collections.<Map<String, Object>>emptyList());
        when(aiClient.chatWithMessagesRequired(any(), any())).thenReturn(response);
        when(parser.parseToolCalls(response)).thenReturn(Collections.singletonList(finish));
        when(parser.findFinish(any())).thenReturn(finish);
        AgentRunSpec spec = new AgentRunSpec();
        spec.setMessages(new ArrayList<Map<String, Object>>());
        spec.setToolContext(new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null));
        spec.setMaxIterations(2);
        spec.setMaxTotalTokens(100);
        spec.setPolicy(new AgentRunPolicy() {
            public AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult ai, AgentToolCall call) {
                return AgentRunDirective.stop("完整证据", AgentStopReason.FINISH_TOOL);
            }
            public String finalizeRun(AgentRunContext context) { return "预算兜底"; }
        });

        AgentRunResult result = runner.run(spec);

        assertEquals("完整证据", result.getFinalContent());
        assertEquals(AgentStopReason.FINISH_TOOL, result.getStopReason());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void runnerClosesProtocolForFilteredToolCalls() {
        AiClient aiClient = mock(AiClient.class);
        AgentToolExecutor toolExecutor = mock(AgentToolExecutor.class);
        AgentToolCallParser parser = mock(AgentToolCallParser.class);
        ToolFanoutExecutor fanout = mock(ToolFanoutExecutor.class);
        AgentConversation conversation = mock(AgentConversation.class);
        AgentRunner runner = new AgentRunner(aiClient, toolExecutor, parser, fanout, conversation);
        AiToolCallResult response = new AiToolCallResult();
        AgentToolCall first = new AgentToolCall();
        first.setAction("search_code");
        AgentToolCall second = new AgentToolCall();
        second.setAction("search_log");
        List<AgentToolCall> original = Arrays.asList(first, second);
        when(toolExecutor.toolSchemas(anyBoolean(), any(ProjectExecutionScope.class)))
                .thenReturn(Collections.<Map<String, Object>>emptyList());
        when(aiClient.chatWithMessagesRequired(any(), any())).thenReturn(response);
        when(parser.parseToolCalls(response)).thenReturn(original);
        when(parser.findFinish(any())).thenReturn(null);
        when(fanout.executeAll(any(), any())).thenReturn(Collections.singletonList(
                AgentToolResult.ok("search_code", "命中", "code")));
        AgentRunSpec spec = new AgentRunSpec();
        spec.setMessages(new ArrayList<Map<String, Object>>());
        spec.setToolContext(new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null));
        spec.setMaxIterations(1);
        spec.setPolicy(new AgentRunPolicy() {
            public List<AgentToolCall> filterToolCalls(AgentRunContext context, List<AgentToolCall> calls) {
                return Collections.singletonList(calls.get(0));
            }
            public AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult ai, AgentToolCall call) {
                return AgentRunDirective.stop("finish", AgentStopReason.FINISH_TOOL);
            }
            public AgentRunDirective afterTools(AgentRunContext context, AiToolCallResult ai,
                                                List<AgentToolCall> calls, List<AgentToolResult> results) {
                return AgentRunDirective.stop("done", AgentStopReason.FINISH_TOOL);
            }
            public String finalizeRun(AgentRunContext context) { return "fallback"; }
        });

        AgentRunResult result = runner.run(spec);

        ArgumentCaptor<List> resultCaptor = ArgumentCaptor.forClass(List.class);
        verify(conversation).appendToolMessages(any(), any(), any(), resultCaptor.capture());
        List<AgentToolResult> aligned = (List<AgentToolResult>) resultCaptor.getValue();
        assertEquals("done", result.getFinalContent());
        assertEquals(2, aligned.size());
        assertTrue(aligned.get(1).getSummary().contains("预算已满"));
    }

    @Test
    void subAgentInvestigationMergesStructuredHandoff() {
        Map<String, Object> round = new LinkedHashMap<String, Object>();
        round.put("action", "search_log");
        round.put("toolOk", true);
        round.put("toolEvidence", "L1");
        Map<String, AgentToolResult> cache = new LinkedHashMap<String, AgentToolResult>();
        cache.put("search_log|{keyword=order}", AgentToolResult.ok("search_log", "命中", "L1"));
        AgentRunContext context = new AgentRunContext(new ArrayList<Map<String, Object>>(),
                new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null), false);
        context.setFinalContent("日志证据");
        context.setStopReason(AgentStopReason.FINISH_TOOL);
        SubAgentResult result = new SubAgentResult("日志 Agent", new AgentRunResult(context),
                Collections.singletonList(round), cache);

        SubAgentInvestigation investigation = new SubAgentInvestigation(Collections.singletonList(result));

        assertEquals(1, investigation.rounds().size());
        assertEquals(1, investigation.cachedToolResults().size());
        assertTrue(investigation.evidencePrompt().contains("禁止重复相同工具和参数"));
        assertTrue(investigation.evidencePrompt().contains("第一轮直接调用 finish"));
    }

    @Test
    void orchestratorStartsOnlyRequestedSubAgent() {
        AgentRunner runner = mock(AgentRunner.class);
        AgentConversation conversation = mock(AgentConversation.class);
        com.tjc.bugagent.config.AppProperties properties = new com.tjc.bugagent.config.AppProperties();
        AgentToolExecutor.AgentToolContext parent = new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null);
        when(conversation.message(any(), any())).thenReturn(new LinkedHashMap<String, Object>());
        when(runner.run(any(AgentRunSpec.class))).thenAnswer(invocation -> {
            AgentRunSpec spec = invocation.getArgument(0);
            AgentRunContext context = new AgentRunContext(new ArrayList<Map<String, Object>>(),
                    spec.getToolContext(), false);
            context.setIteration(1);
            AgentToolCall call = new AgentToolCall();
            call.setAction("read_source");
            call.setArguments(Collections.<String, Object>singletonMap("filePath", "RecipeServiceImpl.java"));
            spec.getPolicy().afterTools(context, new AiToolCallResult(), Collections.singletonList(call),
                    Collections.singletonList(AgentToolResult.ok("read_source", "命中失败分支", "1563: if (!result)")));
            context.setFinalContent("源码证据");
            context.setStopReason(AgentStopReason.FINISH_TOOL);
            return new AgentRunResult(context);
        });
        SubAgentOrchestrator orchestrator = new SubAgentOrchestrator(runner, conversation, properties);
        try {
            SubAgentInvestigation investigation = orchestrator.investigate(
                    "只补源码返回分支", parent, com.tjc.bugagent.analysis.AnalysisProgressListener.NOOP, true, false);

            assertEquals(1, investigation.getResults().size());
            assertEquals("源码 Agent", investigation.getResults().get(0).getRole());
            verify(runner, times(1)).run(any(AgentRunSpec.class));
        } finally {
            orchestrator.shutdown();
        }
    }

    @Test
    void subAgentInvestigationDropsTextOnlyHandoff() {
        AgentRunContext context = new AgentRunContext(new ArrayList<Map<String, Object>>(),
                new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null), false);
        context.setFinalContent("只有模型说明，没有工具证据");
        context.setStopReason(AgentStopReason.FINISH_TOOL);

        SubAgentInvestigation investigation = new SubAgentInvestigation(Collections.singletonList(
                new SubAgentResult("源码 Agent", new AgentRunResult(context),
                        Collections.<Map<String, Object>>emptyList(), Collections.<String, AgentToolResult>emptyMap())));

        assertTrue(investigation.getResults().isEmpty());
    }

    @Test
    void subAgentInvestigationDropsSuccessfulToolWithoutConcreteEvidence() {
        Map<String, Object> round = new LinkedHashMap<String, Object>();
        round.put("action", "find_callers");
        round.put("toolOk", true);
        round.put("toolEvidence", "无");
        AgentRunContext context = new AgentRunContext(new ArrayList<Map<String, Object>>(),
                new AgentToolExecutor.AgentToolContext(1L, 2L, "/x", null), false);
        context.setFinalContent("没有直接证据");
        context.setStopReason(AgentStopReason.FINISH_TOOL);

        SubAgentInvestigation investigation = new SubAgentInvestigation(Collections.singletonList(
                new SubAgentResult("源码 Agent", new AgentRunResult(context), Collections.singletonList(round),
                        Collections.<String, AgentToolResult>emptyMap())));

        assertTrue(investigation.getResults().isEmpty());
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
