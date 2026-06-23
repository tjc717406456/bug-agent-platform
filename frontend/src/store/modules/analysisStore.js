import { ref, computed } from 'vue'
import { message } from 'ant-design-vue'
import { analyzeBug, submitAgentAnalysisTaskScreenshots, pollAgentAnalysisTask, submitApiExplainTask, uploadLog } from '../../api/client'
import { currentProject, analysisForm, analysisResult, analysisDialogVisible, activeReportTab, feedbackEditable, sleep } from '../core'

export const screenshotFiles = ref([])
export const agentProgress = ref([])
export const agentProgressVisible = ref(false)
export const logFileList = ref([])

// 正在跑的 Agent 任务持久化：刷新/切走再回来能续上轮询、拿回结果（结果在 Redis，TTL 30min）
const ACTIVE_TASK_KEY = 'bug-agent-active-task'
export const agentTaskRunning = ref(false)

function saveActiveTask(taskId) {
  if (typeof window !== 'undefined' && taskId) {
    window.localStorage.setItem(ACTIVE_TASK_KEY, taskId)
  }
}

function clearActiveTask() {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(ACTIVE_TASK_KEY)
  }
}

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
  if (file.size > 50 * 1024 * 1024) {
    message.warning('日志文件不能超过 50MB')
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

// 解析扩展复制出来的结构文本（接口/请求参数/请求体/响应），拆不出接口就返回 null
function parseCapturedText(text) {
  if (!text || text.indexOf('接口:') < 0) {
    return null
  }
  const grab = (label, nextLabels) => {
    const start = text.indexOf(label)
    if (start < 0) return ''
    let end = text.length
    for (const next of nextLabels) {
      const idx = text.indexOf('\n' + next, start + label.length)
      if (idx >= 0 && idx < end) end = idx
    }
    return text.slice(start + label.length, end).trim()
  }
  const apiPath = grab('接口:', ['请求参数:', '请求体:', '响应:'])
  const reqParam = grab('请求参数:', ['请求体:', '响应:'])
  const reqBody = grab('请求体:', ['响应:'])
  const response = grab('响应:', [])
  if (!apiPath) {
    return null
  }
  return {
    apiPath,
    requestBody: reqParam + (reqBody ? '\n请求体: ' + reqBody : ''),
    responseBody: response,
  }
}

// 读剪贴板，按扩展复制的结构自动回填表单
export async function pasteFromClipboard() {
  let text = ''
  try {
    text = await navigator.clipboard.readText()
  } catch (error) {
    message.error('读取剪贴板失败，请允许剪贴板权限')
    return
  }
  const parsed = parseCapturedText(text)
  if (!parsed) {
    message.warning('剪贴板内容不是抓包结构，先在扩展里点「复制」')
    return
  }
  analysisForm.apiPath = parsed.apiPath
  analysisForm.requestBody = parsed.requestBody
  analysisForm.responseBody = parsed.responseBody
  message.success('已从剪贴板回填接口与请求响应')
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
    saveActiveTask(task.taskId)
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
    saveActiveTask(task.taskId)
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
  agentTaskRunning.value = true
  try {
    for (let index = 0; index < 150; index++) {
      const task = await pollAgentAnalysisTask(taskId)
      // 实时刷新进度时间线
      if (Array.isArray(task.progress)) {
        agentProgress.value = task.progress
      }
      if (task.status === 'SUCCESS') {
        clearActiveTask()
        agentTaskRunning.value = false
        return task.result
      }
      if (task.status === 'FAILED' || task.status === 'NOT_FOUND') {
        clearActiveTask()
        agentTaskRunning.value = false
        throw new Error(task.message || 'Agent分析失败')
      }
      await sleep(2000)
    }
    agentTaskRunning.value = false
    throw new Error('Agent分析超时，请稍后查看任务状态')
  } catch (error) {
    agentTaskRunning.value = false
    throw error
  }
}

// 页面加载/切回时，若本地存着未完成的任务，续上轮询，拿回结果并弹窗
export async function resumeAgentTask() {
  if (agentTaskRunning.value || typeof window === 'undefined') return
  const taskId = window.localStorage.getItem(ACTIVE_TASK_KEY)
  if (!taskId) return

  agentProgress.value = []
  agentProgressVisible.value = true
  try {
    analysisResult.value = await waitAgentTask(taskId)
    feedbackEditable.value = false
    agentProgressVisible.value = false
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('Agent分析完成！')
  } catch (error) {
    agentProgressVisible.value = false
    clearActiveTask()
    message.error(error.message || 'Agent分析失败')
  }
}
