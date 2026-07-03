package com.tjc.bugagent.analysis.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tjc.bugagent.analysis.AnalysisProgressListener;
import com.tjc.bugagent.analysis.AnalysisRecord;
import com.tjc.bugagent.analysis.AnalysisRecordService;
import com.tjc.bugagent.analysis.AnalysisResult;
import com.tjc.bugagent.ai.AiClient;
import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.config.AppProperties;
import com.tjc.bugagent.project.ProjectDatasource;
import com.tjc.bugagent.project.ProjectService;
import com.tjc.bugagent.project.ProjectVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trimHeadTail;

/**
 * 追问式分析：基于一条已落库的分析记录（报告+证据日志）继续回答使用者的追问。
 * 不背原始对话——报告与证据日志就是浓缩上下文，历史记录也能随时追问；
 * 证据答不了就照常调工具补查（小轮次预算），答案两段式纯文字流式生成，前端渐进可见。
 * 多轮追问的问答对存 Redis（TTL 内带上文），Redis 不可用自动退化为单轮，不挡功能。
 */
@Service
public class FollowUpService {
    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    // 追问是小活：证据大多现成，补查几轮足够，不给大预算
    private static final int MAX_ITERATIONS = 8;
    // 会话里最多带最近几个问答对，防上下文越滚越肥
    private static final int MAX_HISTORY_PAIRS = 5;
    private static final String QA_KEY_PREFIX = "agent:followup:qa:";

    private final AnalysisRecordService analysisRecordService;
    private final ProjectService projectService;
    private final AgentToolExecutor agentToolExecutor;
    private final ToolFanoutExecutor toolFanoutExecutor;
    private final AgentConversation conversation;
    private final AgentToolCallParser toolCallParser;
    private final AiClient aiClient;
    private final AppProperties appProperties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public FollowUpService(AnalysisRecordService analysisRecordService,
                           ProjectService projectService,
                           AgentToolExecutor agentToolExecutor,
                           ToolFanoutExecutor toolFanoutExecutor,
                           AgentConversation conversation,
                           AgentToolCallParser toolCallParser,
                           AiClient aiClient,
                           AppProperties appProperties,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper) {
        this.analysisRecordService = analysisRecordService;
        this.projectService = projectService;
        this.agentToolExecutor = agentToolExecutor;
        this.toolFanoutExecutor = toolFanoutExecutor;
        this.conversation = conversation;
        this.toolCallParser = toolCallParser;
        this.aiClient = aiClient;
        this.appProperties = appProperties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 回答一条追问。走 utility 模型（轻活省钱），答案流式快照经 progress 推给前端。
     */
    public AnalysisResult answer(Long recordId, String question, AnalysisProgressListener progress) {
        long startMs = System.currentTimeMillis();
        if (isBlank(question)) {
            throw new IllegalArgumentException("追问内容不能为空");
        }
        AnalysisRecord record = analysisRecordService.get(recordId);
        if (record == null) {
            throw new IllegalArgumentException("分析记录不存在或已删除");
        }
        // 记录里没有 versionId，且旧版本可能已被导入清掉——工具查询一律用项目当前最新版本
        ProjectVersion version = projectService.latestReadyVersion(record.getProjectId());
        if (version == null) {
            throw new IllegalStateException("项目没有可用的已索引版本");
        }
        ProjectDatasource datasource = projectService.firstEnabledDatasource(record.getProjectId());
        AgentToolExecutor.AgentToolContext toolContext = new AgentToolExecutor.AgentToolContext(
                record.getProjectId(), version.getId(), record.getApiPath(), datasource);

        List<Map<String, Object>> messages = buildMessages(record, question);
        progress.onStep("追问已收到，基于已有证据作答");
        log.info("追问开始 recordId={} apiPath={} question={}", recordId, record.getApiPath(), trim(question, 100));

        AtomicInteger tokens = new AtomicInteger();
        String answer = null;
        int iterationsRun = 0;
        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            iterationsRun = iteration;
            if (progress.isCancelled()) {
                throw new AnalysisCancelledException();
            }
            conversation.decayOldToolMessages(messages, appProperties.getAgent().getKeepRecentRounds());
            AiToolCallResult aiResult = aiClient.chatWithMessagesUtility(messages, agentToolExecutor.toolSchemas());
            tokens.addAndGet(aiResult.getTotalTokens());
            if (!isBlank(aiResult.getContent())) {
                log.info("追问第{}轮 · 思考: {}", iteration, trim(safe(aiResult.getContent()), 200));
            }
            if (aiResult.isFailed()) {
                answer = "⚠️ AI 服务暂不可用，追问在第 " + iteration + " 轮中断，请稍后重试。";
                break;
            }
            List<AgentToolCall> calls = toolCallParser.parseToolCalls(aiResult);
            AgentToolCall finish = toolCallParser.findFinish(calls);
            if (finish != null) {
                // 模型直接用纯文字作答（没走工具协议）：正文就是答案，不折腾第二发
                if (finish.isDegraded() && !isBlank(aiResult.getContent())) {
                    answer = aiResult.getContent();
                    progress.onPartialReport(answer);
                    break;
                }
                // finish 参数里带了成段回答就直接用；只是收口信号则流式生成完整回答
                String argReport = finish.stringArg("report");
                if (!isBlank(argReport) && argReport.trim().length() >= 300) {
                    answer = argReport;
                    progress.onPartialReport(answer);
                    break;
                }
                conversation.appendAssistantMessage(messages, aiResult);
                conversation.appendFinishAck(messages, aiResult, finish);
                answer = streamAnswer(messages, progress, tokens);
                break;
            }
            // 补查证据：一轮内多工具并行，回填后继续
            List<AgentToolResult> results = toolFanoutExecutor.executeAll(calls, toolContext);
            conversation.appendAssistantMessage(messages, aiResult);
            conversation.appendToolMessages(messages, aiResult, calls, results);
            for (int i = 0; i < calls.size(); i++) {
                progress.onStep("追问查证 第" + iteration + "轮 · " + safe(calls.get(i).getAction())
                        + " · " + trim(safe(results.get(i).getSummary()), 40));
            }
        }
        if (isBlank(answer)) {
            // 到顶没收口：直接流式逼答
            answer = streamAnswer(messages, progress, tokens);
        }
        if (isBlank(answer)) {
            answer = "本次追问未能生成回答，请换个问法重试。";
        }

        appendHistory(recordId, question, answer);

        AnalysisResult result = new AnalysisResult();
        result.setId(recordId);
        result.setConclusion(answer);
        result.setRounds(iterationsRun);
        result.setTotalTokens(tokens.get());
        result.setElapsedMs(System.currentTimeMillis() - startMs);
        log.info("追问完成 recordId={} 轮数={} token={} 耗时={}ms", recordId, iterationsRun, tokens.get(), result.getElapsedMs());
        return result;
    }

    /** 追问上下文：角色 + 报告/证据 + 最近几轮问答 + 本次问题。 */
    private List<Map<String, Object>> buildMessages(AnalysisRecord record, String question) {
        boolean explain = "EXPLAIN".equals(record.getRecordType());
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(conversation.message("system",
                (explain
                        ? "你是刚完成该接口流程讲解的 agent，现在回答使用者对这份讲解的追问。\n"
                        : "你是刚完成该接口 Bug 分析的定位 agent，现在回答使用者对这份报告的追问。\n")
                        + "规则：优先基于下面的" + (explain ? "讲解" : "分析报告") + "与已查证据回答；现有证据答不了就调用工具补查（数据库只读）；"
                        + "不确定就明说，不要编造。注意：代码/数据库查询基于项目当前版本，可能与" + (explain ? "讲解" : "分析") + "当时略有差异。\n"
                        + "能回答时调用 finish 收口（report 留空即可），完整回答会让你随后单独用纯文字输出；"
                        + "回答要直接针对问题，带上关键证据（代码位置/SQL/数据），不用重复整份" + (explain ? "讲解" : "报告") + "。"));
        StringBuilder context = new StringBuilder();
        context.append("【接口】").append(safe(record.getApiPath())).append("\n");
        if (!isBlank(record.getUserDescription())) {
            context.append(explain ? "【当时的关注点】" : "【当时的问题描述】").append(record.getUserDescription()).append("\n");
        }
        context.append(explain ? "【接口讲解】\n" : "【分析报告】\n").append(safe(record.getConclusion())).append("\n\n");
        // 证据日志保头留尾：头是入口定位，尾是末轮根因查证
        context.append("【已查证据】\n").append(trimHeadTail(safe(record.getEvidenceJson()),
                appProperties.getAgent().getInitialEvidenceLimit()));
        messages.add(conversation.message("user", context.toString()));
        for (Map<String, String> pair : loadHistory(record.getId())) {
            messages.add(conversation.message("user", "追问：" + pair.get("q")));
            messages.add(conversation.message("assistant", pair.get("a")));
        }
        messages.add(conversation.message("user", "追问：" + question));
        return messages;
    }

    /** 两段式第二段：纯文字流式生成回答，快照节流推前端。失败返回 null。 */
    private String streamAnswer(List<Map<String, Object>> messages, AnalysisProgressListener progress, AtomicInteger tokens) {
        List<Map<String, Object>> ask = new ArrayList<Map<String, Object>>(messages);
        ask.add(conversation.message("user", "现在不要调用任何工具，直接用纯文字写出对追问的完整回答，"
                + "直击问题、带关键证据；证据不足就给最可能的判断并说明依据。"));
        final long[] lastPushMs = {0};
        AiToolCallResult prose = aiClient.chatProseStreamUtility(ask, snapshot -> {
            long now = System.currentTimeMillis();
            if (now - lastPushMs[0] >= 400) {
                lastPushMs[0] = now;
                progress.onPartialReport(snapshot);
            }
        });
        tokens.addAndGet(prose.getTotalTokens());
        if (prose.isFailed() || isBlank(prose.getContent())) {
            return null;
        }
        progress.onPartialReport(prose.getContent());
        return prose.getContent();
    }

    /** 读取最近的问答对；Redis 不可用退化为无历史（单轮照答）。 */
    private List<Map<String, String>> loadHistory(Long recordId) {
        try {
            String json = redisTemplate.opsForValue().get(QA_KEY_PREFIX + recordId);
            if (isBlank(json)) {
                return new ArrayList<Map<String, String>>();
            }
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception exception) {
            log.debug("追问历史读取失败，按无历史处理: {}", exception.getMessage());
            return new ArrayList<Map<String, String>>();
        }
    }

    /** 追加问答对并刷新 TTL；答案截断存储，别把 Redis 撑肥。写失败只影响多轮上文，不影响本次回答。 */
    private void appendHistory(Long recordId, String question, String answer) {
        try {
            List<Map<String, String>> history = loadHistory(recordId);
            Map<String, String> pair = new LinkedHashMap<String, String>();
            pair.put("q", trim(question, 500));
            pair.put("a", trim(answer, 3000));
            history.add(pair);
            while (history.size() > MAX_HISTORY_PAIRS) {
                history.remove(0);
            }
            redisTemplate.opsForValue().set(QA_KEY_PREFIX + recordId, objectMapper.writeValueAsString(history),
                    appProperties.getTaskTtlSeconds(), TimeUnit.SECONDS);
        } catch (Exception exception) {
            log.debug("追问历史写入失败: {}", exception.getMessage());
        }
    }
}
