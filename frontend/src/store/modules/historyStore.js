import { ref, reactive, computed } from 'vue'
import { message } from 'ant-design-vue'
import { listAnalysisRecords, getAnalysisRecord, batchDeleteAnalysisRecords, submitAnalysisFeedback } from '../../api/client'
import { analysisResult, analysisDialogVisible, activeReportTab, feedbackEditable, activePanel, confirm } from '../core'

export const analysisRecords = ref([])
export const historyQuery = reactive({ projectId: null, apiPath: '', recordType: '' })
export const selectedHistoryKeys = ref([])
export const historyPagination = reactive({ current: 1, pageSize: 20, total: 0, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'] })
export const feedbackForm = reactive({ verdict: '', expectKeywords: '', actualRootCause: '', note: '' })

export const historyRowSelection = computed(() => ({
  selectedRowKeys: selectedHistoryKeys.value,
  onChange: (keys) => {
    selectedHistoryKeys.value = keys
  }
}))

export function resetFeedback() {
  Object.assign(feedbackForm, { verdict: '', expectKeywords: '', actualRootCause: '', note: '' })
}

export async function loadHistory() {
  const result = await listAnalysisRecords({
    projectId: historyQuery.projectId,
    apiPath: historyQuery.apiPath,
    recordType: historyQuery.recordType,
    page: historyPagination.current - 1,
    size: historyPagination.pageSize
  }).catch(() => ({ records: [], total: 0 }))
  analysisRecords.value = result.records || []
  historyPagination.total = result.total || 0
}

// 筛选/查询：回到第一页并清空勾选
export function searchHistory() {
  historyPagination.current = 1
  selectedHistoryKeys.value = []
  loadHistory()
}

export function onHistoryTableChange(pagination) {
  historyPagination.current = pagination.current
  historyPagination.pageSize = pagination.pageSize
  selectedHistoryKeys.value = []
  loadHistory()
}

export async function deleteSelectedRecords() {
  if (!selectedHistoryKeys.value.length) {
    message.warning('请先勾选要删除的记录')
    return
  }
  await confirm(`确认删除选中的 ${selectedHistoryKeys.value.length} 条分析记录吗？`, '删除分析记录')
  await batchDeleteAnalysisRecords(selectedHistoryKeys.value)
  message.success('已删除')
  selectedHistoryKeys.value = []
  loadHistory()
}

export async function viewRecord(row) {
  const detail = await getAnalysisRecord(row.id)
  analysisResult.value = {
    id: detail.id,
    // 类型必须带上：报告弹窗靠它决定"讲解不显示反馈框"、追问人设等
    recordType: detail.recordType,
    plainAnswer: '',
    conclusion: detail.conclusion,
    confidence: detail.confidence,
    evidenceJson: detail.evidenceJson,
    autoVerify: detail.autoVerify
  }
  // 预填已有标注，方便修改；关键词 JSON 转回逗号串
  let keywords = ''
  try {
    keywords = (JSON.parse(detail.expectKeywords || '[]') || []).join(',')
  } catch (error) {
    keywords = ''
  }
  Object.assign(feedbackForm, {
    verdict: detail.feedbackVerdict || '',
    expectKeywords: keywords,
    actualRootCause: detail.actualRootCause || '',
    note: ''
  })
  feedbackEditable.value = true
  activeReportTab.value = 'report'
  analysisDialogVisible.value = true
}

export async function submitFeedbackAction() {
  if (!analysisResult.value?.id) {
    message.warning('没有可反馈的分析记录')
    return
  }
  if (!feedbackForm.verdict) {
    message.warning('请先选择结论是否正确')
    return
  }
  const expectKeywords = feedbackForm.expectKeywords
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
  await submitAnalysisFeedback(analysisResult.value.id, {
    verdict: feedbackForm.verdict,
    actualRootCause: feedbackForm.actualRootCause,
    expectKeywords,
    note: feedbackForm.note
  })
  message.success('反馈已记录，已沉淀为回归用例')
  if (activePanel.value === 'history') {
    await loadHistory()
  }
}

/** 登出清场：历史记录属于上一位用户，必须抹掉 */
export function resetHistoryState() {
  analysisRecords.value = []
  selectedHistoryKeys.value = []
  historyPagination.current = 1
  historyPagination.total = 0
  Object.assign(historyQuery, { projectId: null, apiPath: '', recordType: '' })
  resetFeedback()
}
