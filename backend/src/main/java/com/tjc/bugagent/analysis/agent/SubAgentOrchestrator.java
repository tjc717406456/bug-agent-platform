package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.analysis.AnalysisProgressListener;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 受控子 Agent 编排器：并行执行源码调查与日志调查，返回压缩证据交给主 Agent 裁决。
 */
@Component
public class SubAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SubAgentOrchestrator.class);
    private static final int MAX_EVIDENCE_LENGTH = 12000;
    private static final int MAX_TOOLS_PER_ROUND = 2;
    private static final int MAX_TOOLS_PER_AGENT = 6;
    private static final String OUTPUT_CONTRACT = "只提交调查证据，不写最终修复报告。必须按以下结构输出：\n"
            + "【调查摘要】\n【已确认事实】\n【高概率推测】\n【冲突】\n【证据缺口】\n"
            + "每条事实必须附文件/行号、日志行号或工具返回来源；没有证据就明确写无。";

    private final AgentRunner agentRunner;
    private final AgentConversation conversation;
    private final AppProperties appProperties;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(20), runnable -> {
                Thread thread = new Thread(runnable, "agent-evidence-investigator");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public SubAgentOrchestrator(AgentRunner agentRunner, AgentConversation conversation, AppProperties appProperties) {
        this.agentRunner = agentRunner;
        this.conversation = conversation;
        this.appProperties = appProperties;
    }

    /**
     * 按证据面并行调查；没有日志时只启动源码子 Agent，避免空跑和浪费 Token。
     */
    public SubAgentInvestigation investigate(String initialEvidence,
                                              AgentToolExecutor.AgentToolContext parentContext,
                                              AnalysisProgressListener progress) {
        return investigate(initialEvidence, parentContext, progress, true, true);
    }

    /** 按主 Agent 已识别的证据缺口，只启动需要的专业子 Agent。 */
    public SubAgentInvestigation investigate(String focusedEvidence,
                                              AgentToolExecutor.AgentToolContext parentContext,
                                              AnalysisProgressListener progress,
                                              boolean codeNeeded, boolean logNeeded) {
        List<CompletableFuture<SubAgentResult>> futures = new ArrayList<CompletableFuture<SubAgentResult>>();
        if (codeNeeded) {
            submit(futures, () -> runCodeAgent(focusedEvidence, parentContext, progress), progress, "源码 Agent");
        }
        if (logNeeded && hasLog(parentContext)) {
            submit(futures, () -> runLogAgent(focusedEvidence, parentContext, progress), progress, "日志 Agent");
        } else if (logNeeded) {
            progress.onStep("[日志 Agent] 未提供日志，本次跳过");
        }
        List<SubAgentResult> results = awaitResults(futures, progress);
        return new SubAgentInvestigation(results);
    }

    private List<SubAgentResult> awaitResults(List<CompletableFuture<SubAgentResult>> futures,
                                               AnalysisProgressListener progress) {
        List<SubAgentResult> results = new ArrayList<SubAgentResult>();
        List<CompletableFuture<SubAgentResult>> pending = new ArrayList<CompletableFuture<SubAgentResult>>(futures);
        int waitSeconds = Math.max(10, appProperties.getAgent().getMultiAgentWaitSeconds());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        while (!pending.isEmpty()) {
            if (progress.isCancelled()) {
                cancelAll(pending);
                throw new AnalysisCancelledException();
            }
            for (Iterator<CompletableFuture<SubAgentResult>> iterator = pending.iterator(); iterator.hasNext();) {
                CompletableFuture<SubAgentResult> future = iterator.next();
                if (!future.isDone()) {
                    continue;
                }
                collectResult(future, results);
                iterator.remove();
            }
            if (pending.isEmpty()) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                cancelAll(pending);
                progress.onStep("子 Agent 调查等待超过 " + waitSeconds + " 秒，主 Agent 基于已有证据继续");
                break;
            }
            try {
                CompletableFuture.anyOf(pending.toArray(new CompletableFuture<?>[0])).get(1, TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
                // 每秒醒来检查取消标记，避免用户停止后仍卡在十五分钟等待。
            } catch (Exception ignored) {
                // 具体异常由 collectResult 统一记录，其他子 Agent 仍可继续。
            }
        }
        return results;
    }

    private void collectResult(CompletableFuture<SubAgentResult> future, List<SubAgentResult> results) {
        try {
            SubAgentResult result = future.get();
            if (result != null && !AgentTextUtils.isBlank(result.getEvidence())) {
                results.add(result);
            }
        } catch (Exception exception) {
            log.warn("子 Agent 调查失败，主 Agent 将基于已有证据继续: {}", exception.getMessage());
        }
    }

    private void cancelAll(List<CompletableFuture<SubAgentResult>> futures) {
        for (CompletableFuture<SubAgentResult> future : futures) {
            future.cancel(true);
        }
    }

    private void submit(List<CompletableFuture<SubAgentResult>> futures,
                        java.util.function.Supplier<SubAgentResult> task,
                        AnalysisProgressListener progress, String role) {
        try {
            futures.add(CompletableFuture.supplyAsync(task, executor));
        } catch (RejectedExecutionException exception) {
            progress.onStep("[" + role + "] 调查队列已满，本次跳过并由主 Agent 继续");
        }
    }

    private SubAgentResult runCodeAgent(String evidence, AgentToolExecutor.AgentToolContext parent,
                                        AnalysisProgressListener progress) {
        return runAgent("源码 Agent", "你只负责调查源码。优先闭合接口入口、失败条件、返回对象构造、成功与失败字段，"
                        + "只补关键方法尾部，不得泛搜相邻代码，不得分析日志或查询数据库。",
                evidence, childContext(parent, "code", "search_code", "get_code_detail", "trace_call_chain",
                        "search_sql", "grep_source", "find_callers", "finish"), progress);
    }

    private SubAgentResult runLogAgent(String evidence, AgentToolExecutor.AgentToolContext parent,
                                       AnalysisProgressListener progress) {
        return runAgent("日志 Agent", "你只负责日志。围绕用户提供的核心标识建立时间线，定位首个失败、错误码、超时或返回值，"
                        + "明确缺失的底层日志，不得换大量近义关键词撒网，不得阅读源码或查询数据库。",
                evidence, childContext(parent, "log", "search_log", "finish"), progress);
    }

    private SubAgentResult runAgent(String role, String responsibility, String evidence,
                                    AgentToolExecutor.AgentToolContext context,
                                    AnalysisProgressListener progress) {
        progress.onStep("[" + role + "] 开始独立调查");
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(conversation.message("system", responsibility + "\n" + OUTPUT_CONTRACT
                + "\n每轮最多调用2个工具，优先闭合失败条件和返回结果。证据不足时调用 finish 提交当前证据，不要撒网搜索。"));
        messages.add(conversation.message("user", "请基于下面的初始材料开始调查：\n"
                + AgentTextUtils.trim(evidence, MAX_EVIDENCE_LENGTH)));
        EvidencePolicy policy = new EvidencePolicy(role, appProperties.getAgent().getMultiAgentSubIterations());
        AgentRunSpec spec = new AgentRunSpec();
        spec.setMessages(messages);
        spec.setToolContext(context);
        spec.setPolicy(policy);
        spec.setProgress(subProgress(progress, role));
        spec.setMaxIterations(appProperties.getAgent().getMultiAgentSubIterations());
        spec.setMaxTotalTokens(appProperties.getAgent().getMultiAgentSubTokenBudget());
        spec.setKeepRecentRounds(3);
        spec.setVerificationEnabled(false);
        spec.setModelRole(AgentRunSpec.ModelRole.PRIMARY);
        spec.setModelFailureMessage(role + "调用模型失败");
        AgentRunResult result = agentRunner.run(spec);
        progress.onStep("[" + role + "] " + stopText(result.getStopReason()) + " · "
                + result.getIterations() + "轮 · " + result.getTotalTokens() + " tokens");
        return new SubAgentResult(role, result, policy.getRounds(), context.cachedResultsSnapshot());
    }

    private String stopText(AgentStopReason stopReason) {
        if (stopReason == AgentStopReason.FINISH_TOOL) {
            return "调查完成";
        }
        if (stopReason == AgentStopReason.TOKEN_BUDGET) {
            return "达到 Token 预算，提交已有证据";
        }
        if (stopReason == AgentStopReason.MAX_ITERATIONS) {
            return "达到调查轮次上限，提交已有证据";
        }
        if (stopReason == AgentStopReason.MODEL_ERROR) {
            return "模型调用失败，提交已有证据";
        }
        return "调查结束（" + stopReason + "）";
    }

    private AgentToolExecutor.AgentToolContext childContext(AgentToolExecutor.AgentToolContext parent,
                                                             String suffix, String... tools) {
        String parentTaskId = parent.getScope().getTaskId();
        String childTaskId = (AgentTextUtils.isBlank(parentTaskId) ? "sub-agent" : parentTaskId) + ":" + suffix;
        ProjectExecutionScope scope = parent.getScope().child(childTaskId, tools);
        AgentToolExecutor.AgentToolContext child = new AgentToolExecutor.AgentToolContext(
                scope, parent.getApiPath(), parent.getLogText(), parent.getLogPath());
        child.restoreCachedResults(parent.cachedResultsSnapshot());
        return child;
    }

    private AnalysisProgressListener subProgress(AnalysisProgressListener parent, String role) {
        return new AnalysisProgressListener() {
            @Override
            public void onStep(String step) {
                parent.onStep("[" + role + "] " + step);
            }

            @Override
            public boolean isCancelled() {
                return parent.isCancelled();
            }
        };
    }

    private boolean hasLog(AgentToolExecutor.AgentToolContext context) {
        return !AgentTextUtils.isBlank(context.getLogText()) || !AgentTextUtils.isBlank(context.getLogPath());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 子 Agent 只接受 finish 证据包，不做主报告自检和二次收口。 */
    private final class EvidencePolicy implements AgentRunPolicy {
        private final String role;
        private final int maxIterations;
        private final List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        private int toolCalls;

        private EvidencePolicy(String role, int maxIterations) {
            this.role = role;
            this.maxIterations = maxIterations;
        }

        @Override
        public void beforeIteration(AgentRunContext context) {
            if (context.getIteration() >= maxIterations || toolCalls >= MAX_TOOLS_PER_AGENT) {
                context.getMessages().add(conversation.message("user",
                        "停止继续调用调查工具。现在只调用 finish，按调查摘要、已确认事实、冲突和唯一证据缺口提交精简交接。"));
            }
        }

        @Override
        public List<AgentToolCall> filterToolCalls(AgentRunContext context, List<AgentToolCall> calls) {
            List<AgentToolCall> filtered = new ArrayList<AgentToolCall>();
            for (AgentToolCall call : calls) {
                if ("finish".equals(call.getAction())) {
                    filtered.add(call);
                    continue;
                }
                if (context.getIteration() >= maxIterations || toolCalls + filteredToolCount(filtered) >= MAX_TOOLS_PER_AGENT
                        || filteredToolCount(filtered) >= MAX_TOOLS_PER_ROUND) {
                    continue;
                }
                filtered.add(call);
            }
            return filtered;
        }

        @Override
        public AgentToolCall resolveFinish(AgentRunContext context, List<AgentToolCall> calls, AgentToolCall finish) {
            if (finish != null) {
                return finish;
            }
            if (context.getIteration() >= maxIterations || toolCalls >= MAX_TOOLS_PER_AGENT) {
                AgentToolCall forced = new AgentToolCall();
                forced.setAction("finish");
                forced.setArguments(Collections.<String, Object>singletonMap("report", buildHandoff(null)));
                return forced;
            }
            return null;
        }

        @Override
        public AgentRunDirective onFinish(AgentRunContext context, AiToolCallResult response, AgentToolCall finish) {
            String summary = finish.stringArg("report");
            if (AgentTextUtils.isBlank(summary)) {
                summary = response.getContent();
            }
            return AgentRunDirective.stop(buildHandoff(summary), AgentStopReason.FINISH_TOOL);
        }

        @Override
        public AgentRunDirective afterTools(AgentRunContext context, AiToolCallResult response,
                                            List<AgentToolCall> calls, List<AgentToolResult> results) {
            for (int index = 0; index < calls.size(); index++) {
                AgentToolCall call = calls.get(index);
                AgentToolResult result = results.get(index);
                Map<String, Object> round = new LinkedHashMap<String, Object>();
                round.put("iteration", context.getIteration());
                round.put("thought", "[" + role + "] 独立调查");
                round.put("action", call.getAction());
                round.put("arguments", call.getArguments());
                round.put("toolOk", result.isOk());
                round.put("toolSummary", "[" + role + "] " + result.getSummary());
                round.put("toolEvidence", result.getEvidence());
                rounds.add(round);
            }
            toolCalls += calls.size();
            return AgentRunDirective.continueRun();
        }

        @Override
        public String finalizeRun(AgentRunContext context) {
            return buildHandoff(null);
        }

        private String buildHandoff(String modelSummary) {
            StringBuilder confirmed = new StringBuilder();
            StringBuilder gaps = new StringBuilder();
            int confirmedCount = 0;
            int gapCount = 0;
            for (Map<String, Object> round : rounds) {
                boolean ok = Boolean.TRUE.equals(round.get("toolOk"));
                String item = "- " + AgentTextUtils.safe(round.get("action")) + " "
                        + AgentTextUtils.trim(AgentTextUtils.safe(round.get("arguments")), 200) + "："
                        + AgentTextUtils.trim(AgentTextUtils.safe(round.get("toolSummary")), 300);
                if (ok && confirmedCount < 6) {
                    confirmed.append(item).append("\n  证据：")
                            .append(AgentTextUtils.trim(AgentTextUtils.safe(round.get("toolEvidence")), 600))
                            .append('\n');
                    confirmedCount++;
                } else if (!ok && gapCount < 2) {
                    gaps.append(item).append('\n');
                    gapCount++;
                }
            }
            String summary = AgentTextUtils.isBlank(modelSummary)
                    ? role + "已完成限定范围调查，共执行" + toolCalls + "次工具调用。"
                    : AgentTextUtils.trim(modelSummary, 800);
            return "【调查摘要】\n" + summary + "\n【已确认事实】\n"
                    + (confirmed.length() == 0 ? "无直接命中证据\n" : confirmed.toString())
                    + "【高概率推测】\n无，交由主 Agent 基于证据裁决。\n【冲突】\n无明确冲突。\n【证据缺口】\n"
                    + (gaps.length() == 0 ? "无明确缺口，主 Agent 仅需交叉核对后收口。" : gaps.toString());
        }

        private int filteredToolCount(List<AgentToolCall> calls) {
            int count = 0;
            for (AgentToolCall call : calls) {
                if (!"finish".equals(call.getAction())) {
                    count++;
                }
            }
            return count;
        }

        private List<Map<String, Object>> getRounds() {
            return new ArrayList<Map<String, Object>>(rounds);
        }
    }
}
