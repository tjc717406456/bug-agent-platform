package com.tjc.bugagent.analysis.agent;

import com.tjc.bugagent.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文衰减 decayOldToolMessages 的纯逻辑测试：保留最近 N 轮全文、折叠更早轮、幂等、关闭开关。
 */
class AgentConversationTest {

    private final AgentConversation conversation = new AgentConversation(new AppProperties());

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** 造 N 轮：每轮 1 条带 tool_calls 的 assistant + 1 条带详情(含换行)的 tool，贴近真实查证轮形状。 */
    private List<Map<String, Object>> buildRounds(int n) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(msg("system", "sys"));
        messages.add(msg("user", "init"));
        for (int i = 1; i <= n; i++) {
            Map<String, Object> assistant = msg("assistant", "think " + i);
            assistant.put("tool_calls", new ArrayList<Object>());
            messages.add(assistant);
            messages.add(msg("tool", "成功: 摘要" + i + "\n详情正文第" + i + "轮，很长很长的证据"));
        }
        return messages;
    }

    @Test
    void plainAssistantWithoutToolCallsDoesNotCountAsRound() {
        List<Map<String, Object>> messages = buildRounds(4);
        // 中间插两条纠偏/复核类的纯文本 assistant，不该占折叠窗口
        messages.add(3, msg("assistant", "nudge"));
        messages.add(3, msg("assistant", "critique"));
        conversation.decayOldToolMessages(messages, 4);
        for (Map<String, Object> m : messages) {
            if ("tool".equals(m.get("role"))) {
                assertTrue(((String) m.get("content")).contains("\n详情"), "4轮全在窗口内，纯文本 assistant 不该把首轮挤出去");
            }
        }
    }

    @Test
    void keepsRecentFoldsOld() {
        List<Map<String, Object>> messages = buildRounds(10);
        conversation.decayOldToolMessages(messages, 3);
        // 收集 tool 消息按轮序
        List<String> tools = new ArrayList<String>();
        for (Map<String, Object> m : messages) {
            if ("tool".equals(m.get("role"))) {
                tools.add((String) m.get("content"));
            }
        }
        // 最近 3 轮(第8/9/10)留全文(含换行)，更早的折叠成首行
        assertTrue(tools.get(9).contains("\n详情"), "最后一轮应保留详情");
        assertTrue(tools.get(7).contains("\n详情"), "倒数第三轮应保留详情");
        assertTrue(tools.get(6).contains("已折叠"), "倒数第四轮应被折叠");
        assertFalseContainsDetail(tools.get(0));
    }

    private void assertFalseContainsDetail(String content) {
        assertTrue(content.contains("已折叠") && !content.contains("\n详情"), "早期轮应只剩摘要行: " + content);
    }

    @Test
    void idempotent() {
        List<Map<String, Object>> messages = buildRounds(8);
        conversation.decayOldToolMessages(messages, 2);
        String snapshot = collectTools(messages);
        conversation.decayOldToolMessages(messages, 2);
        assertEquals(snapshot, collectTools(messages), "二次衰减不应再改动");
    }

    @Test
    void zeroDisablesDecay() {
        List<Map<String, Object>> messages = buildRounds(8);
        String before = collectTools(messages);
        conversation.decayOldToolMessages(messages, 0);
        assertEquals(before, collectTools(messages), "keepRecent=0 不衰减");
    }

    private String collectTools(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            if ("tool".equals(m.get("role"))) {
                sb.append((String) m.get("content")).append("|");
            }
        }
        return sb.toString();
    }
}
