<template>
  <a-modal v-model:open="agentProgressVisible" title="Agent 分析进度" :footer="null" :closable="false" :mask-closable="false" centered width="560px">
    <div class="progress-box">
      <a-spin />
      <a-timeline class="top-gap" v-if="agentProgress.length">
        <a-timeline-item v-for="(step, i) in agentProgress" :key="i" :color="i === agentProgress.length - 1 ? 'blue' : 'green'">{{ step }}</a-timeline-item>
      </a-timeline>
      <p v-else class="hint-text">正在启动分析…</p>
    </div>
  </a-modal>
</template>

<script setup>
import { useAppStore } from '../../store/useAppStore'

const { agentProgressVisible, agentProgress } = useAppStore()
</script>

<style scoped>
.progress-box {
  max-height: 52vh;
  overflow-y: auto;
}
.progress-box .hint-text {
  margin-top: 12px;
}
</style>
