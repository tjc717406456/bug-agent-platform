import { ref, computed } from 'vue'
import { message } from 'ant-design-vue'
import { analyzeBug, submitAgentAnalysisTaskScreenshots, pollAgentAnalysisTask, submitApiExplainTask, uploadLog } from '../../api/client'
import { currentProject, analysisForm, analysisResult, analysisDialogVisible, activeReportTab, feedbackEditable, sleep } from '../core'

export const screenshotFiles = ref([])
export const agentProgress = ref([])
export const agentProgressVisible = ref(false)
export const logFileList = ref([])

export const screenshotFileList = computed(() => screenshotFiles.value.map((file, index) => ({ uid: String(index), name: file.name })))

export function beforeScreenshotUpload(file) {
  screenshotFiles.value = [...screenshotFiles.value, file]
  return false
}

export function removeScreenshot(file) {
  const index = Number(file.uid)
  screenshotFiles.value = screenshotFiles.value.filter((_, idx) => idx !== index)
}

export async function beforeLogUpload(file) {
  if (file.size > 10 * 1024 * 1024) {
    message.warning('日志文件不能超过 10MB')
    return false
  }
  logFileList.value = [file]
  try {
    analysisForm.logId = await uploadLog(file)
    message.success('日志已上传，分析时自动读取')
  } catch (error) {
    message.error(error.message || '日志上传失败')
    logFileList.value = []
    analysisForm.logId = ''
  }
  return false
}

export function removeLog() {
  logFileList.value = []
  analysisForm.logId = ''
}

export async function analyzeAction() {
  const close = message.loading('正在分析中...', 0)
  try {
    const payload = { ...analysisForm, projectId: currentProject.value.id }
    payload.versionId = payload.versionId ? Number(payload.versionId) : null
    analysisResult.value = await analyzeBug(payload)
    feedbackEditable.value = false
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('分析完成')
  } finally {
    close()
  }
}

export async function agentAnalyzeAction() {
  agentProgress.value = []
  agentProgressVisible.value = true
  try {
    const payload = { ...analysisForm, projectId: currentProject.value.id }
    payload.versionId = payload.versionId ? Number(payload.versionId) : null
    const task = await submitAgentAnalysisTaskScreenshots(payload, screenshotFiles.value)
    analysisResult.value = await waitAgentTask(task.taskId)
    feedbackEditable.value = false
    agentProgressVisible.value = false
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('Agent分析完成！')
  } catch (error) {
    agentProgressVisible.value = false
    message.error(error.message || 'Agent分析失败')
  }
}

export async function apiAnalyzeAction() {
  if (!currentProject.value) {
    message.warning('请先选择项目')
    return
  }
  if (!analysisForm.apiPath) {
    message.warning('请先选择接口')
    return
  }
  agentProgress.value = []
  agentProgressVisible.value = true
  try {
    // 接口讲解只需项目+版本+接口；问题描述选填，填了就当关注点引导讲解方向
    const payload = {
      projectId: currentProject.value.id,
      versionId: analysisForm.versionId ? Number(analysisForm.versionId) : null,
      apiPath: analysisForm.apiPath,
      userDescription: analysisForm.userDescription
    }
    const task = await submitApiExplainTask(payload)
    analysisResult.value = await waitAgentTask(task.taskId)
    feedbackEditable.value = false
    agentProgressVisible.value = false
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('接口分析完成！')
  } catch (error) {
    agentProgressVisible.value = false
    message.error(error.message || '接口分析失败')
  }
}

export async function waitAgentTask(taskId) {
  for (let index = 0; index < 150; index++) {
    const task = await pollAgentAnalysisTask(taskId)
    // 实时刷新进度时间线
    if (Array.isArray(task.progress)) {
      agentProgress.value = task.progress
    }
    if (task.status === 'SUCCESS') {
      return task.result
    }
    if (task.status === 'FAILED' || task.status === 'NOT_FOUND') {
      throw new Error(task.message || 'Agent分析失败')
    }
    await sleep(2000)
  }
  throw new Error('Agent分析超时，请稍后查看任务状态')
}
