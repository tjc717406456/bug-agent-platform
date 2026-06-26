<template>
  <a-modal v-model:open="agentProgressVisible" title="Agent 分析进度（自主查证过程）" :closable="false" :mask-closable="false" centered width="600px">
    <div class="progress-box">
      <div class="elapsed-bar"><a-spin /><span class="elapsed">⏱ 已用时 {{ elapsedText }}</span></div>
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
    <template #footer>
      <a-button danger :loading="agentTaskStopping" @click="stopAgentTask">
        {{ agentTaskStopping ? '正在停止…' : '停止分析' }}
      </a-button>
    </template>
  </a-modal>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { useAppStore } from '../../store/useAppStore'

const { agentProgressVisible, agentProgress, agentTaskStopping, stopAgentTask } = useAppStore()

// 弹窗打开即开始走秒，关闭停表；纯前端计时，给个实时耗时感知（最终准确耗时以收尾的 "✓ 完成 · Ns" 为准）
const elapsed = ref(0)
let timer = null
const elapsedText = computed(() => {
  const s = elapsed.value
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s`
})
watch(agentProgressVisible, (open) => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (open) {
    elapsed.value = 0
    timer = setInterval(() => { elapsed.value += 1 }, 1000)
  }
}, { immediate: true })
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

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
.elapsed-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.elapsed-bar .elapsed {
  color: #1677ff;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.step-icon {
  margin-right: 6px;
}
</style>
