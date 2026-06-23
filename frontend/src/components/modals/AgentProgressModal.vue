<template>
  <a-modal v-model:open="agentProgressVisible" title="Agent 分析进度（自主查证过程）" :footer="null" :closable="false" :mask-closable="false" centered width="600px">
    <div class="progress-box">
      <a-spin />
      <a-timeline class="top-gap" v-if="agentProgress.length">
        <a-timeline-item
          v-for="(step, i) in agentProgress"
          :key="i"
          :color="classify(step).color"
        >
          <span class="step-icon">{{ classify(step).icon }}</span>{{ step }}
        </a-timeline-item>
      </a-timeline>
      <p v-else class="hint-text">正在启动分析…</p>
    </div>
  </a-modal>
</template>

<script setup>
import { useAppStore } from '../../store/useAppStore'

const { agentProgressVisible, agentProgress } = useAppStore()

// 按步骤内容分类上色 + 图标，让 agent 的思考过程一眼看清是哪种动作
function classify(step) {
  const s = String(step)
  if (s.includes('✓') || s.includes('生成最终报告') || s.includes('证据足够')) return { icon: '✅', color: 'blue' }
  if (s.includes('假设') || s.includes('并行验证') || s.includes('相近根因')) return { icon: '🔀', color: 'purple' }
  if (s.includes('取证阶段') || s.includes('已理解')) return { icon: '🔓', color: 'cyan' }
  if (s.includes('自检') || s.includes('复核') || s.includes('补证')) return { icon: '🧐', color: 'orange' }
  if (s.includes('预算')) return { icon: '⏳', color: 'gold' }
  if (s.includes('历史参考') || s.includes('侦察') || s.includes('截图') || s.includes('预取')) return { icon: '📎', color: 'gray' }
  if (/第\d+轮/.test(s)) return { icon: '🔧', color: 'green' }
  return { icon: '·', color: 'green' }
}
</script>

<style scoped>
.progress-box {
  max-height: 56vh;
  overflow-y: auto;
}
.progress-box .hint-text {
  margin-top: 12px;
}
.step-icon {
  margin-right: 6px;
}
</style>
