<template>
  <a-modal v-model:open="analysisDialogVisible" width="1040px" centered destroy-on-close :body-style="{ maxHeight: '76vh', overflowY: 'auto' }">
    <template #title>
      <div class="report-header">
        <span>分析报告{{ analysisResult?.id ? ' #' + analysisResult.id : '' }}</span>
        <span>
          <a-tag v-if="reportStreaming" color="processing">✍️ 报告生成中…</a-tag>
          <a-tag v-if="analysisResult?.autoVerify === 'CONFIRMED'" color="green">✓ 已连库验证</a-tag>
          <a-tag v-else-if="analysisResult?.autoVerify === 'REFUTED'" color="orange">⚠ 验证存疑</a-tag>
          <a-tag v-if="analysisResult?.confidence" color="blue">{{ analysisResult.confidence }}</a-tag>
          <a-tag v-if="analysisResult?.rounds" color="purple">{{ analysisResult.rounds }} 轮</a-tag>
          <a-tag v-if="analysisResult?.totalTokens" color="gold">{{ analysisResult.totalTokens }} tokens</a-tag>
          <a-tag v-if="analysisResult?.elapsedMs" color="cyan">{{ (analysisResult.elapsedMs / 1000).toFixed(1) }}s</a-tag>
        </span>
      </div>
    </template>
    <a-alert v-if="analysisResult?.plainAnswer" :message="analysisResult.plainAnswer" type="success" show-icon />
    <a-tabs v-model:activeKey="activeReportTab" class="top-gap">
      <a-tab-pane key="report" tab="分析报告">
        <pre class="scroll-content" ref="reportBox">{{ reportStreaming ? agentPartialReport : analysisResult?.conclusion }}<span v-if="reportStreaming" class="stream-cursor">▌</span></pre>
      </a-tab-pane>
      <a-tab-pane key="evidence" tab="证据">
        <pre class="scroll-content">{{ analysisResult?.evidenceJson }}</pre>
      </a-tab-pane>
      <a-tab-pane key="followup" tab="💬 追问" v-if="analysisResult?.id && !reportStreaming">
        <div class="followup-pane">
          <div class="followup-chat">
            <p v-if="!followUpChat.length && !followUpAsking" class="followup-empty">
              基于本次{{ analysisResult?.recordType === 'EXPLAIN' ? '讲解' : '分析' }}的证据继续提问，需要时会自动补查代码和数据库。
            </p>
            <div v-for="(item, i) in followUpChat" :key="i" :class="['bubble', item.role === 'q' ? 'bubble-q' : 'bubble-a']">
              <pre>{{ item.text }}</pre>
            </div>
            <div v-if="followUpAsking" class="bubble bubble-a">
              <div v-if="followUpStep && !followUpStreamText" class="followup-step">{{ followUpStep }}</div>
              <pre v-if="followUpStreamText" ref="followUpBox">{{ followUpStreamText }}<span class="stream-cursor">▌</span></pre>
              <a-spin v-else size="small" />
            </div>
          </div>
          <div class="followup-input">
            <a-input
              v-model:value="followUpInput"
              :disabled="followUpAsking"
              placeholder="例如：为什么上周同样的数据没报错？/ 查一下这单具体存的什么值"
              @press-enter="sendFollowUp"
            />
            <a-button v-if="followUpAsking" danger @click="stopFollowUp">停止</a-button>
            <a-button v-else type="primary" @click="sendFollowUp">发送</a-button>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>
    <div class="feedback-box" v-if="feedbackEditable && analysisResult?.recordType !== 'EXPLAIN'">
      <div class="feedback-title">结论反馈（标注后沉淀为回归用例，喂回评估）</div>
      <a-radio-group v-model:value="feedbackForm.verdict">
        <a-radio value="CORRECT">结论正确</a-radio>
        <a-radio value="PARTIAL">部分正确</a-radio>
        <a-radio value="WRONG">结论错误</a-radio>
      </a-radio-group>
      <a-input v-model:value="feedbackForm.expectKeywords" class="top-gap" placeholder="正确结论必须命中的关键词，逗号分隔，如 nick_name,字段,不存在" />
      <a-textarea v-model:value="feedbackForm.actualRootCause" :rows="2" class="top-gap" placeholder="真实根因（结论错或部分对时填）" />
      <a-input v-model:value="feedbackForm.note" class="top-gap" placeholder="备注（可选）" />
      <div class="top-gap">
        <a-button type="primary" @click="submitFeedbackAction"><template #icon><CheckOutlined /></template>提交反馈</a-button>
      </div>
    </div>
    <template #footer>
      <a-button v-if="reportStreaming" danger :loading="agentTaskStopping" @click="stopAgentTask">
        {{ agentTaskStopping ? '正在停止…' : '停止生成' }}
      </a-button>
      <a-button v-else type="primary" @click="analysisDialogVisible = false">{{ feedbackEditable ? '关闭' : '确认' }}</a-button>
    </template>
  </a-modal>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { CheckOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const {
  analysisDialogVisible, analysisResult, activeReportTab, feedbackEditable, feedbackForm, submitFeedbackAction,
  reportStreaming, agentPartialReport, agentTaskStopping, stopAgentTask,
  followUpChat, followUpAsking, followUpStreamText, followUpStep, askFollowUp, stopFollowUp
} = useAppStore()

// 追问输入；换记录时清空
const followUpInput = ref('')
watch(() => analysisResult.value?.id, () => {
  followUpInput.value = ''
})

function sendFollowUp() {
  const text = followUpInput.value.trim()
  if (!text || followUpAsking.value) return
  followUpInput.value = ''
  askFollowUp(text)
}

// 追问流式答案的"贴底跟滚"：距底 40px 内才自动跟，用户往上翻阅时不抢滚轮，拉回底部又粘住
const followUpBox = ref(null)
watch(followUpStreamText, () => {
  const el = followUpBox.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  nextTick(() => {
    if (nearBottom) {
      el.scrollTop = el.scrollHeight
    }
  })
})

// 流式正文的"贴底跟滚"：本来就在底部才跟着滚，用户往上翻阅时不抢滚轮；拉回底部又自动粘住
const reportBox = ref(null)
watch(agentPartialReport, () => {
  if (!reportStreaming.value) return
  const el = reportBox.value ? (reportBox.value.$el || reportBox.value) : null
  if (!el) return
  // 在 DOM 更新前量位置：距底 40px 以内视为"贴着底部"
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  nextTick(() => {
    if (nearBottom) {
      el.scrollTop = el.scrollHeight
    }
  })
})
</script>

<style scoped>
.stream-cursor {
  animation: stream-blink 1s step-end infinite;
  color: #52c41a;
}
@keyframes stream-blink {
  50% { opacity: 0; }
}
.followup-pane {
  display: flex;
  flex-direction: column;
  min-height: 320px;
}
.followup-chat {
  flex: 1;
  max-height: 46vh;
  overflow-y: auto;
  margin-bottom: 10px;
  padding-right: 4px;
}
.followup-empty {
  color: #999;
  text-align: center;
  margin-top: 90px;
}
.followup-chat .bubble {
  margin: 6px 0;
  padding: 8px 12px;
  border-radius: 8px;
  max-width: 92%;
}
.followup-chat .bubble pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  max-height: 260px;
  overflow-y: auto;
}
.followup-chat .bubble-q {
  background: #e6f4ff;
  margin-left: auto;
}
/* 回答气泡走报告同款黑底白字画布，代码/SQL 看着舒服 */
.followup-chat .bubble-a {
  background: #0b1021;
  color: #d6e2ff;
}
.followup-chat .bubble-a pre {
  color: #d6e2ff;
}
.followup-chat .bubble-a .followup-step {
  color: #8ea3c8;
}
.followup-step {
  color: #888;
  font-size: 12px;
  margin-bottom: 4px;
}
.followup-input {
  display: flex;
  gap: 8px;
}
</style>
