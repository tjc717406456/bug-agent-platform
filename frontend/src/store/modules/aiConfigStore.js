import { ref, reactive } from 'vue'
import { message } from 'ant-design-vue'
import { listAiConfigs, createAiConfig, updateAiConfig, activateAiConfig, deleteAiConfig, testAiConfig } from '../../api/client'
import { confirm } from '../core'

export const aiConfigs = ref([])
export const selectedAiConfigId = ref(null)
export const aiDialogVisible = ref(false)
// 编辑中的配置 id，null 表示新增
export const editingAiId = ref(null)
export const aiForm = reactive({ provider: 'openai-compatible', baseUrl: '', modelName: '', apiKey: '', timeoutSeconds: 60, enabled: true, supportsVision: false })

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
  editingAiId.value = null
  Object.assign(aiForm, { provider: 'openai-compatible', baseUrl: '', modelName: '', apiKey: '', timeoutSeconds: 60, enabled: true, supportsVision: false })
  aiDialogVisible.value = true
}

// 编辑：用行数据回填表单，API Key 列表是脱敏的，置空表示不修改
export function openEditAiDialog(row) {
  editingAiId.value = row.id
  Object.assign(aiForm, {
    provider: row.provider,
    baseUrl: row.baseUrl,
    modelName: row.modelName,
    apiKey: '',
    timeoutSeconds: row.timeoutSeconds,
    enabled: row.enabled,
    supportsVision: !!row.supportsVision,
  })
  aiDialogVisible.value = true
}

export async function saveAiAction() {
  if (!aiForm.baseUrl || !aiForm.modelName) {
    message.warning('Base URL、Model 不能为空')
    return
  }
  // 新增必须填 API Key；编辑留空表示沿用原值
  if (!editingAiId.value && !aiForm.apiKey) {
    message.warning('API Key 不能为空')
    return
  }
  if (editingAiId.value) {
    await updateAiConfig(editingAiId.value, aiForm)
    message.success('AI 配置已保存')
  } else {
    await createAiConfig(aiForm)
    message.success('AI 配置已新增')
  }
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
