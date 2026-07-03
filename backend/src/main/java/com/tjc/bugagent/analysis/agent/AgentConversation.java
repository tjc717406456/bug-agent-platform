package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.ai.AiToolCallResult;
import com.tjc.bugagent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tjc.bugagent.analysis.agent.AgentTextUtils.isBlank;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.safe;
import static com.tjc.bugagent.analysis.agent.AgentTextUtils.trim;

/**
 * 维护与模型的 OpenAI 对话消息：构造消息、按 tool_call 协议回填 assistant 与 tool 结果。
 * OpenAI 要求每个 tool_call 都要有配对 tool 消息，回填不全下一轮请求会被服务端拒。
 */
@Component
public class AgentConversation {

    private final AppProperties appProperties;

    public AgentConversation(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // 多模态发图护栏：单次最多带几张、单张最大多少字节，防 base64 撑爆 token
    private static final int MAX_VISION_IMAGES = 3;
    private static final long MAX_VISION_IMAGE_BYTES = 5L * 1024 * 1024;

    public Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /**
     * 构造带截图的多模态 user 消息：文本 + 若干 image_url(base64 data url)，给支持视觉的模型直接识读报错图。
     * 读不到/超限/格式不对的图自动跳过；一张有效图都没有就退回纯文本消息，不影响纯文本模型。
     */
    public Map<String, Object> userMessageWithImages(String text, List<String> imagePaths) {
        List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
        Map<String, Object> textPart = new LinkedHashMap<String, Object>();
        textPart.put("type", "text");
        textPart.put("text", safe(text));
        parts.add(textPart);

        int used = 0;
        for (String path : imagePaths) {
            if (used >= MAX_VISION_IMAGES) {
                break;
            }
            String dataUrl = toDataUrl(path);
            if (dataUrl == null) {
                continue;
            }
            Map<String, Object> imagePart = new LinkedHashMap<String, Object>();
            imagePart.put("type", "image_url");
            Map<String, Object> imageUrl = new LinkedHashMap<String, Object>();
            imageUrl.put("url", dataUrl);
            imagePart.put("image_url", imageUrl);
            parts.add(imagePart);
            used++;
        }
        if (used == 0) {
            return message("user", text);
        }
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", parts);
        return message;
    }

    /** 把本地截图读成 OpenAI 视觉接口要的 base64 data url，读不了或超限返回 null。 */
    private String toDataUrl(String path) {
        if (isBlank(path)) {
            return null;
        }
        try {
            Path file = Paths.get(path.trim());
            if (!Files.exists(file) || Files.size(file) > MAX_VISION_IMAGE_BYTES) {
                return null;
            }
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
            return "data:" + mimeOf(path) + ";base64," + base64;
        } catch (Exception exception) {
            return null;
        }
    }

    private String mimeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    /**
     * 回填模型本轮的 assistant 消息（含 tool_calls）；拿不到原始消息时退化成普通文本消息。
     */
    public void appendAssistantMessage(List<Map<String, Object>> messages, AiToolCallResult aiResult) {
        Map<String, Object> assistant = aiResult.getAssistantMessage();
        if (assistant != null) {
            messages.add(assistant);
        } else {
            messages.add(message("assistant", safe(aiResult.getContent())));
        }
    }

    /**
     * 按 tool_call id 逐条回填工具结果。缺失的兜底成"工具未执行"，避免下一轮请求被服务端拒绝。
     */
    public void appendToolMessages(List<Map<String, Object>> messages, AiToolCallResult aiResult,
                                   List<AgentToolCall> calls, List<AgentToolResult> results) {
        Map<String, String> contentById = new HashMap<String, String>();
        for (int i = 0; i < calls.size(); i++) {
            String id = calls.get(i).getToolCallId();
            if (id != null) {
                contentById.put(id, toolResultContent(results.get(i)));
            }
        }
        if (aiResult.getToolCalls().isEmpty()) {
            return;
        }
        for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
            String id = raw.getId();
            if (id == null) {
                continue;
            }
            Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", id);
            toolMessage.put("content", contentById.getOrDefault(id, "工具未执行"));
            messages.add(toolMessage);
        }
    }

    private String toolResultContent(AgentToolResult result) {
        StringBuilder content = new StringBuilder();
        // 区分三态：成功 / 真失败 / 查无结果(让模型知道是该换思路而非工具坏了)
        String status = result.isOk() ? "成功" : (result.isHardFailure() ? "失败" : "无结果");
        content.append(status).append(": ").append(safe(result.getSummary()));
        String evidence = safe(result.getEvidence());
        if (!evidence.isEmpty()) {
            // 读整段方法的 get_code_detail 给更大窗口，别让长方法尾部被截掉
            int limit = "get_code_detail".equals(result.getTool())
                    ? appProperties.getAgent().getCodeResultLimit()
                    : appProperties.getAgent().getToolResultLimit();
            content.append("\n").append(trim(evidence, limit));
        }
        return content.toString();
    }

    /**
     * 上下文衰减：只保留最近 keepRecent 轮的工具结果全文，更早轮次的折叠成首行摘要，
     * 削掉深挖案例每轮重发全历史的 O(n²) token 膨胀。报告/收敛都从 rounds[] 重建，折叠 messages 不影响判定。
     * 幂等：折叠后内容无换行，再次调用自动跳过。keepRecent <= 0 时不衰减。
     */
    public void decayOldToolMessages(List<Map<String, Object>> messages, int keepRecent) {
        if (keepRecent <= 0) {
            return;
        }
        int assistantSeen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            Object role = message.get("role");
            if ("assistant".equals(role)) {
                // 只有真发过 tool_calls 的才算一轮；nudge/复核打回的纯文本 assistant 不占折叠窗口
                if (message.get("tool_calls") != null) {
                    assistantSeen++;
                }
            } else if ("tool".equals(role) && assistantSeen >= keepRecent) {
                Object raw = message.get("content");
                if (raw instanceof String) {
                    String text = (String) raw;
                    int nl = text.indexOf('\n');
                    if (nl > 0) {
                        // 提示可重调取回：解除与"已读过不要重复读"规则的冲突，防模型凭模糊印象编字段名/行号；
                        // 同参数重调命中工具缓存秒回，代价只剩一个往返
                        message.put("content", text.substring(0, nl) + " …(早期轮次详情已折叠，需要细节时用相同参数重调该工具即可取回)");
                    }
                }
            }
        }
    }

    /**
     * 两段式收口：闭合本轮全部 tool_call，finish 回"信号已收到"，同轮夹带的其他调用标不再执行，
     * 满足协议后紧接着就能发"纯文字写报告"的流式请求。
     */
    public void appendFinishAck(List<Map<String, Object>> messages, AiToolCallResult aiResult, AgentToolCall finish) {
        String finishId = finish.getToolCallId();
        for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
            String id = raw.getId();
            if (id == null) {
                continue;
            }
            Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", id);
            toolMessage.put("content", id.equals(finishId)
                    ? "收口信号已收到，请按接下来的指示用纯文字输出完整报告。"
                    : "已收口，该调用不再执行。");
            messages.add(toolMessage);
        }
    }

    /**
     * 闭合被拦下的本轮全部 tool_call，满足"每个 tool_call 必须有 tool 响应"的协议。
     * finish 给复核提示，同轮夹带的其他调用统一标暂缓。
     */
    public void appendFinishHold(List<Map<String, Object>> messages, AiToolCallResult aiResult, AgentToolCall finish) {
        String finishId = finish.getToolCallId();
        for (com.tjc.bugagent.ai.AiToolCall raw : aiResult.getToolCalls()) {
            String id = raw.getId();
            if (id == null) {
                continue;
            }
            Map<String, Object> toolMessage = new LinkedHashMap<String, Object>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", id);
            toolMessage.put("content", id.equals(finishId)
                    ? "结论已收到，先做独立复核再决定是否采纳。"
                    : "本轮先复核初步结论，该调用暂缓。");
            messages.add(toolMessage);
        }
    }
}
