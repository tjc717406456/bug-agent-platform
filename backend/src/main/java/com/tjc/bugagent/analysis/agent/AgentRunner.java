package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通用模型—工具运行内核，统一三个 Workflow 的迭代、回灌、Hook 和检查点。
 */
@Component
public class AgentRunner {
    private final AiClient aiClient;
    private final AgentToolExecutor toolExecutor;
    private final AgentToolCallParser parser;
    private final ToolFanoutExecutor fanoutExecutor;
    private final AgentConversation conversation;
    private final List<AgentRunHook> defaultHooks;

    @Autowired
    public AgentRunner(AiClient aiClient, AgentToolExecutor toolExecutor, AgentToolCallParser parser,
                       ToolFanoutExecutor fanoutExecutor, AgentConversation conversation,
                       List<AgentRunHook> defaultHooks) {
        this.aiClient = aiClient;
        this.toolExecutor = toolExecutor;
        this.parser = parser;
        this.fanoutExecutor = fanoutExecutor;
        this.conversation = conversation;
        this.defaultHooks = defaultHooks == null ? Collections.<AgentRunHook>emptyList() : defaultHooks;
    }

    /** 测试和非 Spring 场景使用的兼容构造。 */
    public AgentRunner(AiClient aiClient, AgentToolExecutor toolExecutor, AgentToolCallParser parser,
                       ToolFanoutExecutor fanoutExecutor, AgentConversation conversation) {
        this(aiClient, toolExecutor, parser, fanoutExecutor, conversation, Collections.<AgentRunHook>emptyList());
    }

    public AgentRunResult run(AgentRunSpec spec) {
        validate(spec);
        AgentRunCheckpoint resume = spec.getProgress().resumeCheckpoint();
        List<java.util.Map<String, Object>> messages = resume != null && resume.getMessages() != null
                && !resume.getMessages().isEmpty() ? resume.getMessages() : spec.getMessages();
        AgentRunContext context = new AgentRunContext(messages, spec.getToolContext(), spec.isVerificationEnabled());
        context.restore(resume);
        if (resume != null) {
            spec.getPolicy().restoreState(context, resume.getWorkflowState());
        }
        List<AgentRunHook> allHooks = new ArrayList<AgentRunHook>(defaultHooks);
        allHooks.addAll(spec.getHooks());
        CompositeAgentRunHook hooks = new CompositeAgentRunHook(allHooks);
        hooks.beforeRun(context);
        try {
            int firstIteration = resume == null ? 1 : Math.max(1, resume.getIteration() + 1);
            int previousModelTokens = 0;
            for (int iteration = firstIteration; iteration <= spec.getMaxIterations(); iteration++) {
                context.setIteration(iteration);
                if (isCancelled(spec, context)) {
                    context.setStopReason(AgentStopReason.CANCELLED);
                    throw new AnalysisCancelledException();
                }
                if (wouldExceedTokenBudget(spec, context, previousModelTokens)) {
                    context.setStopReason(AgentStopReason.TOKEN_BUDGET);
                    checkpoint(spec, hooks, context, "TOKEN_BUDGET");
                    break;
                }
                hooks.beforeIteration(context);
                spec.getPolicy().beforeIteration(context);
                conversation.decayOldToolMessages(context.getMessages(), spec.getKeepRecentRounds());
                AiToolCallResult response = requestModel(spec, context);
                if (isCancelled(spec, context)) {
                    context.setStopReason(AgentStopReason.CANCELLED);
                    throw new AnalysisCancelledException();
                }
                previousModelTokens = Math.max(0, response.getTotalTokens());
                context.addTokens(response.getTotalTokens());
                hooks.afterModelResponse(context, response);
                spec.getPolicy().afterModelResponse(context, response);
                if (response.isFailed()) {
                    context.setFailed(true);
                    context.setStopReason(AgentStopReason.MODEL_ERROR);
                    context.setFinalContent(spec.getModelFailureMessage());
                    checkpoint(spec, hooks, context, "MODEL_ERROR");
                    break;
                }
                List<AgentToolCall> originalCalls = parser.parseToolCalls(response);
                List<AgentToolCall> calls = spec.getPolicy().filterToolCalls(context, originalCalls);
                AgentToolCall finish = spec.getPolicy().resolveFinish(context, calls, parser.findFinish(calls));
                AgentRunDirective directive;
                if (finish != null) {
                    directive = spec.getPolicy().onFinish(context, response, finish);
                } else if (spec.getMaxTotalTokens() > 0 && context.getTotalTokens() >= spec.getMaxTotalTokens()) {
                    // 本轮若已返回 finish，优先接住完整结果；只有仍想继续查证时才按预算收口。
                    context.setStopReason(AgentStopReason.TOKEN_BUDGET);
                    checkpoint(spec, hooks, context, "TOKEN_BUDGET");
                    break;
                } else {
                    hooks.beforeTools(context, calls);
                    long started = System.currentTimeMillis();
                    List<AgentToolResult> results = fanoutExecutor.executeAll(calls, context.getToolContext());
                    conversation.appendAssistantMessage(context.getMessages(), response);
                    conversation.appendToolMessages(context.getMessages(), response, originalCalls,
                            alignToolResults(originalCalls, calls, results));
                    long elapsed = Math.max(0, System.currentTimeMillis() - started);
                    long toolElapsed = calls.size() == 1 ? elapsed : -1L;
                    for (int index = 0; index < calls.size(); index++) {
                        AgentToolCall call = calls.get(index);
                        AgentToolResult result = results.get(index);
                        AgentToolEvent event = new AgentToolEvent(iteration, call.getAction(),
                                result.isOk() ? "OK" : (result.isHardFailure() ? "ERROR" : "EMPTY"),
                                result.getSummary(), toolElapsed);
                        context.getToolEvents().add(event);
                        hooks.afterTool(context, call, result, toolElapsed);
                    }
                    directive = spec.getPolicy().afterTools(context, response, calls, results);
                }
                if (directive != null && directive.isStop()) {
                    context.setFinalContent(directive.getFinalContent());
                    context.setStopReason(directive.getStopReason());
                    checkpoint(spec, hooks, context, "COMPLETED");
                    break;
                }
                checkpoint(spec, hooks, context, finish == null ? "TOOLS_COMPLETED" : "FINALIZING");
            }
            if (context.getFinalContent() == null) {
                context.setFinalContent(spec.getPolicy().finalizeRun(context));
                if (context.getStopReason() == null) {
                    context.setStopReason(AgentStopReason.MAX_ITERATIONS);
                }
                checkpoint(spec, hooks, context, "COMPLETED");
            }
            hooks.afterRun(context);
            return new AgentRunResult(context);
        } catch (java.util.concurrent.CancellationException cancelled) {
            context.setStopReason(AgentStopReason.CANCELLED);
            throw new AnalysisCancelledException();
        } catch (AnalysisCancelledException cancelled) {
            throw cancelled;
        } catch (RuntimeException exception) {
            hooks.onError(context, exception);
            throw exception;
        }
    }

    /** 未入选执行的 tool call 也要回填结果，保持 OpenAI 多轮工具协议闭合。 */
    private List<AgentToolResult> alignToolResults(List<AgentToolCall> originalCalls, List<AgentToolCall> selectedCalls,
                                                    List<AgentToolResult> selectedResults) {
        List<AgentToolResult> aligned = new ArrayList<AgentToolResult>();
        int selectedIndex = 0;
        for (AgentToolCall original : originalCalls) {
            if (selectedIndex < selectedCalls.size() && original == selectedCalls.get(selectedIndex)) {
                aligned.add(selectedResults.get(selectedIndex++));
            } else {
                aligned.add(AgentToolResult.empty(original.getAction(), "本轮工具预算已满，已跳过执行"));
            }
        }
        return aligned;
    }

    /** 根据上一轮上下文成本预估下一次调用，预算明显不够时直接用已有证据收口。 */
    private boolean wouldExceedTokenBudget(AgentRunSpec spec, AgentRunContext context, int previousModelTokens) {
        int budget = spec.getMaxTotalTokens();
        if (budget <= 0 || context.getTotalTokens() <= 0) {
            return false;
        }
        return context.getTotalTokens() >= budget
                || (previousModelTokens > 0 && context.getTotalTokens() + previousModelTokens >= budget);
    }

    private AiToolCallResult requestModel(AgentRunSpec spec, AgentRunContext context) {
        List<java.util.Map<String, Object>> schemas = toolExecutor.toolSchemas(
                context.isVerificationEnabled(), context.getToolContext());
        if (spec.getModelRole() == AgentRunSpec.ModelRole.UTILITY) {
            return aiClient.chatWithMessagesUtility(context.getMessages(), schemas);
        }
        return aiClient.chatWithMessagesRequired(context.getMessages(), schemas);
    }

    /** 汇总用户取消、子任务取消和线程中断三类停止信号。 */
    private boolean isCancelled(AgentRunSpec spec, AgentRunContext context) {
        return spec.getProgress().isCancelled() || context.getToolContext().isCancelled()
                || Thread.currentThread().isInterrupted();
    }

    private void checkpoint(AgentRunSpec spec, CompositeAgentRunHook hooks, AgentRunContext context, String phase) {
        hooks.afterIteration(context);
        spec.getProgress().onCheckpoint(context.checkpoint(phase, spec.getPolicy().snapshotState(context)));
    }

    private void validate(AgentRunSpec spec) {
        if (spec == null || spec.getMessages() == null || spec.getToolContext() == null || spec.getPolicy() == null) {
            throw new IllegalArgumentException("AgentRunSpec 缺少 messages、toolContext 或 policy");
        }
        if (spec.getMaxIterations() <= 0) {
            throw new IllegalArgumentException("Agent 最大轮次必须大于 0");
        }
    }
}
