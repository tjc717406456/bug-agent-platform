import { ref, reactive } from 'vue'
import { message } from 'ant-design-vue'
import { listAiConfigs, createAiConfig, activateAiConfig, deleteAiConfig, testAiConfig } from '../../api/client'
import { confirm } from '../core'

export const aiConfigs = ref([])
export const selectedAiConfigId = ref(null)
export const aiDialogVisible = ref(false)
export const aiForm = reactive({ provider: 'openai-compatible', baseUrl: '', modelName: '', apiKey: '', timeoutSeconds: 60, enabled: true })

export async function loadAiConfigs() {
  aiConfigs.value = await listAiConfigs().catch(() => [])
  const active = aiConfigs.value.find((item) => item.enabled)
  selectedAiConfigId.value = active ? active.id : (aiConfigs.value[0]?.id ?? null)
  // 有配置但没启用项时，默认激活第一条，保证分析有可用 AI
  if (!active && selectedAiConfigId.value) {
    await activateAiConfig(selectedAiConfigId.value).catch(() => {})
  }
}

export function openAiDialog() {
  Object.assign(aiForm, { provider: 'openai-compatible', baseUrl: '', modelName: '', apiKey: '', timeoutSeconds: 60, enabled: true })
  aiDialogVisible.value = true
}

export async function saveAiAction() {
  if (!aiForm.baseUrl || !aiForm.modelName || !aiForm.apiKey) {
    message.warning('Base URL、Model、API Key 不能为空')
    return
  }
  await createAiConfig(aiForm)
  message.success('AI 配置已新增')
  aiDialogVisible.value = false
  await loadAiConfigs()
}

export async function activateAiAction(id) {
  await activateAiConfig(id)
  selectedAiConfigId.value = id
  message.success('已切换 Agent 分析使用的 AI')
  await loadAiConfigs()
}

export async function deleteAiAction(row) {
  await confirm(`确认删除配置 ${row.provider} / ${row.modelName} 吗？`, '删除 AI 配置')
  await deleteAiConfig(row.id)
  message.success('AI 配置已删除')
  await loadAiConfigs()
}

export async function testAiAction() {
  const result = await testAiConfig()
  message.info(result)
}
