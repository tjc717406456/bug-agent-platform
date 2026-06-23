package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 集中管理 Agent 多轮分析用到的各类提示词，主流程只负责按时机调用。
 */
@Component
public class AgentPromptBuilder {

    private final AppProperties appProperties;

    public AgentPromptBuilder(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 角色与规则只在 system 消息里讲一次，后续轮次靠对话历史承载，不再每轮重复拼接。
     */
    public String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是只读 Bug 定位 Agent，只负责定位问题，不修改代码，不生成补丁。\n");
        prompt.append("必须基于证据判断；证据不足就调用工具继续查。本轮若需要多份互不依赖的证据（如同时读多个方法、查多张表），请一次返回多个 tool_calls 并行获取，减少往返；只有当下一步依赖上一步结果时才分轮。\n");
        prompt.append("初始证据里已预取入口与关键调用节点的源码快照，能直接判断时不要再读相同位置。\n");
        prompt.append("如果初始证据带了【异常堆栈定位】，那是报错的直接位置，必须优先核对栈顶业务代码那几行，再回溯调用链确认根因，不要绕开堆栈去猜。\n");
        prompt.append("当入口、调用链、SQL/数据源、返回模型、差异点这五类证据已经足够解释问题时，必须停止查证并调用 finish。\n");
        prompt.append("如果连续两轮没有新增关键事实，必须停止查证并收口。\n");
        prompt.append("同一文件、同一类、同一 SQL 已读过且没有新问题时，不要重复读取。\n");
        prompt.append("数据库只能执行只读 SQL。最终报告要让测试、实施能看懂，并保留开发可追溯证据。\n\n");
        prompt.append("【工具使用方式】\n");
        prompt.append("通过调用提供的工具（tool_calls）行动，不要自己手写 JSON，不要输出 Markdown。\n");
        prompt.append("调用工具前，可在回复正文用一两句话简述这步要查什么、为什么。\n");
        prompt.append("证据足够后调用 finish 工具，report 参数写最终报告；不要继续追查无关细节。\n");
        prompt.append("最终报告必须包含：通俗结论、问题结论、证据链路、关键代码/SQL/数据证据、根因类型、建议处理人、置信度。\n");
        prompt.append("其中【通俗结论】放在最前面，用一句话讲清楚是什么问题，让运维、测试、实施都能看懂（例：xx 字段在数据库不存在、xx 字段存的值超长、必填字段没传值）。\n\n");
        prompt.append("【收口规则】\n");
        prompt.append("如果你已经能明确指出最可能的根因和最小修复点，就不要再查重复证据，直接 finish。\n");
        prompt.append("如果只能给出高概率判断，也要在最终报告里说明剩余风险，不要空转。\n");
        return prompt.toString();
    }

    public String buildInitialUserPrompt(String initialEvidence) {
        return "【初始证据】\n" + initialEvidence + "\n现在开始第 1 轮：基于已有证据决定下一步，继续查证或调用 finish 收敛。";
    }

    /**
     * 侦察轮：让模型只基于初始证据列出候选根因和各自置信分，不查证、不收口，纯产出方向清单。
     * 强约束只回 JSON 数组，便于代码解析后判歧义。
     */
    public String buildHypothesisScoutPrompt(String initialEvidence, int maxCount) {
        return "【初始证据】\n" + initialEvidence + "\n\n"
                + "先不要查证、不要调用工具。只基于以上证据，列出最多 " + maxCount + " 个最可能的根因假设，"
                + "每个给一句简短描述和 0-100 的置信分（越确定越高）。\n"
                + "只回纯 JSON 数组，不要任何多余文字、不要 Markdown，格式：\n"
                + "[{\"cause\":\"根因一句话\",\"score\":85},{\"cause\":\"另一个可能\",\"score\":50}]";
    }

    /**
     * 假设分支的引导语：让这条链聚焦核验某一个候选根因，确认或排除，别发散。
     */
    public String buildHypothesisHintPrompt(String cause) {
        return "【本次重点核验的假设】" + cause + "\n"
                + "请优先围绕这个假设取证：用工具确认它成立还是排除它。若证据推翻了它，如实说明并指出更可能的根因，不要硬凑。";
    }

    /**
     * 汇总裁判：给模型几条针对不同假设的调查结论，让它基于各自证据挑出最可能的真根因，产出最终报告。
     */
    public String buildHypothesisSynthesisPrompt(String branchReports) {
        return "下面是针对同一个 Bug、几个不同根因假设各自独立调查得出的结论：\n\n" + branchReports + "\n\n"
                + "基于各结论给出的证据强度，判断哪个才是真正的根因（证据最直接、链路最完整的胜出），"
                + "必要时融合多条线索。输出一份最终定位报告，包含：通俗结论、问题结论、证据链路、"
                + "关键代码/SQL/数据证据、根因类型、建议处理人、置信度。直接给报告正文，不要复述各假设。";
    }

    public String buildForceFinishInstruction(String forceFinishReason) {
        return "【强制收口要求】当前触发收口条件：" + forceFinishReason + "。本轮必须调用 finish，不要再调用查证工具。";
    }

    /**
     * 预算提示：把"已用第几轮/共几轮"亮给模型，过半后提醒抓紧收口，提升收敛、防空转。
     */
    public String buildBudgetReminder(int used, int max) {
        return "【预算提示】已用 " + used + "/" + max + " 轮，剩余不多。证据已足够就立刻 finish；"
                + "只能给高概率判断也要在报告里说明剩余风险，别再追无关细节空转。";
    }

    /**
     * 模型只用文字描述计划、没真发工具调用时的纠偏提示，逼它走 tool_calls 协议。
     */
    public String buildToolNudge() {
        return "你刚才只用文字描述了下一步计划，没有真正发起工具调用。必须通过 tool_calls 调用工具"
                + "（如 get_code_detail、trace_call_chain、search_code、describe_tables）来获取证据，不要用文字罗列计划。"
                + "现在立即发起工具调用；若证据已足够，直接调用 finish 并在 report 参数里写出完整结论。";
    }

    /**
     * 接口讲解角色：只讲流程不挑 bug，用工具下钻调用链和源码把链路说透。
     */
    public String buildApiExplainSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是只读接口讲解 Agent，给定接口路径，讲清这个接口是干什么的、完整流程，不修改代码，不挑 Bug。\n");
        prompt.append("初始证据已预取入口与关键调用节点的源码快照，先基于它判断，链路没讲透再调用工具补查。\n");
        prompt.append("用 trace_call_chain 拿完整调用链，get_code_detail / search_code 读关键方法，describe_tables / query_database 看涉及的表结构和样例数据。\n");
        prompt.append("同一文件、同一方法已读过就不要重复读；流程讲清楚就调用 finish 收口，不要无限下钻无关细节。\n");
        prompt.append("如果初始证据里带了【用户描述】，那是提问人的关注点，讲解要优先围绕它展开，先回答清楚他关心的部分。\n\n");
        prompt.append("【工具使用方式】\n");
        prompt.append("通过 tool_calls 行动，不要自己手写 JSON，不要输出 Markdown。本轮多份互不依赖的证据可一次返回多个 tool_calls 并行获取。\n");
        prompt.append("讲清楚后调用 finish，report 参数写最终讲解。\n\n");
        prompt.append("【最终讲解必须包含】\n");
        prompt.append("1.【通俗说明】一句话讲清这个接口干什么，让运维、测试、实施都能看懂。\n");
        prompt.append("2.【完整流程】入口 Controller → Service → Mapper → SQL → 表，逐层说清每步干了什么。\n");
        prompt.append("3.【关键代码】各环节核心逻辑片段（带文件:行号）。\n");
        prompt.append("4.【数据流向】入参怎么传递处理、读写了哪些表、返回了什么。\n");
        return prompt.toString();
    }

    public String buildApiExplainUserPrompt(String initialEvidence) {
        return "【初始证据】\n" + initialEvidence + "\n现在开始：基于已有调用链与源码讲清这个接口的流程，证据不足就调用工具补查，讲透后调用 finish。";
    }

    /**
     * 把同项目历史确认案例拼成方向参考。空列表返回 null，主流程据此不插这条消息。
     * 只给摘要并明确"不得照抄"，防模型把旧结论当本次答案。
     */
    public String buildSimilarCasesPrompt(List<SimilarCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("【同项目历史参考（仅供方向，必须用本次证据独立验证，不得照抄）】\n");
        int limit = appProperties.getAgent().getSimilarCaseLimit();
        for (SimilarCase similar : cases) {
            String line = "- 接口 " + similar.getApiPath()
                    + " | 确认根因：" + similar.getRootCause()
                    + " | 关键词：" + similar.getKeywords()
                    + " | 置信度：" + similar.getConfidence() + "\n";
            // 拼到字数上限就停，避免历史参考喧宾夺主挤占证据
            if (prompt.length() + line.length() > limit) {
                break;
            }
            prompt.append(line);
        }
        prompt.append("说明：以上为过去已确认案例，帮你锁定排查方向与术语；本次结论必须基于当前证据，若证据不支持请忽略。");
        return prompt.toString();
    }
}
