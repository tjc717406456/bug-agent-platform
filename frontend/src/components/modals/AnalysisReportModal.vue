<template>
  <a-modal v-model:open="analysisDialogVisible" width="1040px" centered destroy-on-close :body-style="{ maxHeight: '76vh', overflowY: 'auto' }">
    <template #title>
      <div class="report-header">
        <span>分析报告{{ analysisResult?.id ? ' #' + analysisResult.id : '' }}</span>
        <span>
          <a-tag v-if="analysisResult?.autoVerify === 'CONFIRMED'" color="green">✓ 已连库验证</a-tag>
          <a-tag v-else-if="analysisResult?.autoVerify === 'REFUTED'" color="orange">⚠ 验证存疑</a-tag>
          <a-tag v-if="analysisResult" color="blue">{{ analysisResult.confidence }}</a-tag>
          <a-tag v-if="analysisResult?.rounds" color="purple">{{ analysisResult.rounds }} 轮</a-tag>
          <a-tag v-if="analysisResult?.totalTokens" color="gold">{{ analysisResult.totalTokens }} tokens</a-tag>
          <a-tag v-if="analysisResult?.elapsedMs" color="cyan">{{ (analysisResult.elapsedMs / 1000).toFixed(1) }}s</a-tag>
        </span>
      </div>
    </template>
    <a-alert v-if="analysisResult?.plainAnswer" :message="analysisResult.plainAnswer" type="success" show-icon />
    <a-tabs v-model:activeKey="activeReportTab" class="top-gap">
      <a-tab-pane key="report" tab="分析报告">
        <pre class="scroll-content">{{ analysisResult?.conclusion }}</pre>
      </a-tab-pane>
      <a-tab-pane key="evidence" tab="证据">
        <pre class="scroll-content">{{ analysisResult?.evidenceJson }}</pre>
      </a-tab-pane>
    </a-tabs>
    <div class="feedback-box" v-if="feedbackEditable">
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
      <a-button type="primary" @click="analysisDialogVisible = false">{{ feedbackEditable ? '关闭' : '确认' }}</a-button>
    </template>
  </a-modal>
</template>

<script setup>
import { CheckOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { analysisDialogVisible, analysisResult, activeReportTab, feedbackEditable, feedbackForm, submitFeedbackAction } = useAppStore()
</script>
