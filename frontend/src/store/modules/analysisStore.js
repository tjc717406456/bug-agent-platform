import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { submitAgentAnalysisTaskScreenshots, pollAgentAnalysisTask, stopAgentAnalysisTask, resumeAgentAnalysisTask, submitApiExplainTask, submitFollowUpTask, uploadLog } from '../../api/client'
import { currentProject, analysisForm, analysisResult, analysisDialogVisible, activeReportTab, feedbackEditable, sleep } from '../core'

export const screenshotFiles = ref([])
export const agentProgress = ref([])
// 收口报告流式生成的累计快照，轮询期间渐进渲染，任务结束清空
export const agentPartialReport = ref('')
// 流式收口进行中：分析报告弹窗以"生成中"形态提前打开，正文渐进长出
export const reportStreaming = ref(false)
export const agentProgressVisible = ref(false)

/** 结束流式展示形态：成功时报告弹窗留着换正式结果，停止/失败时连弹窗一起收 */
function endStreamingView(closeDialog) {
  if (reportStreaming.value) {
    reportStreaming.value = false
    if (closeDialog) {
      analysisDialogVisible.value = false
    }
  }
  agentPartialReport.value = ''
}
export const logFileList = ref([])
// 日志上传中标记：上传未拿到 logId 前禁掉分析按钮，避免漏带日志就开跑
export const logUploading = ref(false)
export const logUploadProgress = ref('')
// 本地日志按时间切割弹窗的开关（纯前端处理，原始文件不上传）
export const logSplitVisible = ref(false)
export function openLogSplit() {
  logSplitVisible.value = true
}

// 正在跑的 Agent 任务持久化：刷新/切走再回来能续上轮询、拿回结果（结果在 Redis，TTL 30min）
const ACTIVE_TASK_KEY = 'bug-agent-active-task'
export const agentTaskRunning = ref(false)
// 当前任务 id 与「停止中」标记：停止按钮拿 id 发请求，loading 态防重复点
export const currentTaskId = ref('')
export const agentTaskStopping = ref(false)

function saveActiveTask(taskId) {
  currentTaskId.value = taskId || ''
  if (typeof window !== 'undefined' && taskId) {
    window.localStorage.setItem(ACTIVE_TASK_KEY, taskId)
  }
}

function clearActiveTask() {
  currentTaskId.value = ''
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(ACTIVE_TASK_KEY)
  }
}

// 点停止：发后端停止请求 + 置前端停止标记，轮询循环据此立即收尾，不干等后台跑完本轮
export async function stopAgentTask() {
  const taskId = currentTaskId.value || (typeof window !== 'undefined' && window.localStorage.getItem(ACTIVE_TASK_KEY))
  if (!taskId) {
    agentProgressVisible.value = false
    return
  }
  agentTaskStopping.value = true
  try {
    await stopAgentAnalysisTask(taskId)
    message.info('已请求停止，正在收尾…')
  } catch (error) {
    agentTaskStopping.value = false
    message.error(error.message || '停止失败')
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
  logUploading.value = true
  logUploadProgress.value = '准备上传…'
  try {
    analysisForm.logId = await uploadLog(file, (loaded, total, elapsedMs) => {
      const percent = Math.min(100, Math.round(loaded * 100 / total))
      const elapsedSeconds = Math.max(elapsedMs / 1000, 0.1)
      const speed = loaded / 1024 / 1024 / elapsedSeconds
      logUploadProgress.value = '已上传 ' + percent + '% · ' + speed.toFixed(1)
        + ' MB/s · ' + elapsedSeconds.toFixed(1) + 's'
    })
    logUploadProgress.value = '上传完成'
    message.success('日志已上传，分析时自动读取')
  } catch (error) {
    message.error(error.message || '日志上传失败')
    logFileList.value = []
    analysisForm.logId = ''
    logUploadProgress.value = ''
  } finally {
    logUploading.value = false
  }
  return false
}

export function removeLog() {
  logFileList.value = []
  analysisForm.logId = ''
  logUploadProgress.value = ''
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

export async function agentAnalyzeAction() {
  if (logUploading.value) {
    message.warning('日志正在上传，请稍候再分析')
    return
  }
  agentProgress.value = []
  agentPartialReport.value = ''
  reportStreaming.value = false
  agentTaskStopping.value = false
  agentProgressVisible.value = true
  try {
    const payload = { ...analysisForm, projectId: currentProject.value.id }
    payload.versionId = payload.versionId ? Number(payload.versionId) : null
    // 深度模式只在开启时传 true；false 会强制单链、把全局 AUTO 也盖掉，关着就传 null 跟随全局
    payload.deepMode = analysisForm.deepMode ? true : null
    payload.multiAgentMode = analysisForm.multiAgentMode ? true : null
    const task = await submitAgentAnalysisTaskScreenshots(payload, screenshotFiles.value)
    saveActiveTask(task.taskId)
    const result = await waitAgentTask(task.taskId)
    agentProgressVisible.value = false
    if (!result) {
      message.info('分析已停止')
      return
    }
    analysisResult.value = result
    feedbackEditable.value = false
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
  agentPartialReport.value = ''
  reportStreaming.value = false
  agentTaskStopping.value = false
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
    const result = await waitAgentTask(task.taskId)
    agentProgressVisible.value = false
    if (!result) {
      message.info('分析已停止')
      return
    }
    analysisResult.value = result
    feedbackEditable.value = false
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('接口分析完成！')
  } catch (error) {
    agentProgressVisible.value = false
    message.error(error.message || '接口分析失败')
  }
}

export async function waitAgentTask(taskId) {
  // 没拿到 taskId 直接报准确原因，别拿 undefined 去 poll 出个误导的"分析失败"
  if (!taskId) {
    throw new Error('Agent任务ID为空')
  }
  agentTaskRunning.value = true
  try {
    // 不设总时长预算：轮询正常、任务还是 RUNNING 就一直陪跑（长分析本来就可能超5分钟，有停止按钮兜底）；
    // 只有连续 poll 异常 ≥5 次（后端真失联）才报错
    let pollFailures = 0
    let resumed = false
    while (true) {
      // 点了停止：前端立即停轮询收尾，不等后台跑完本轮（后台会自行置 CANCELLED）
      if (agentTaskStopping.value) {
        clearActiveTask()
        agentTaskRunning.value = false
        agentTaskStopping.value = false
        endStreamingView(true)
        return null
      }
      let task
      try {
        task = await pollAgentAnalysisTask(taskId)
        pollFailures = 0
      } catch (pollError) {
        // 登录过期不是通信故障：立刻干净收尾，别重试 5 次再抛出误导性的"通信失败"
        if (pollError.code === 'UNAUTHORIZED') {
          clearActiveTask()
          agentTaskRunning.value = false
          endStreamingView(true)
          return null
        }
        pollFailures++
        if (pollFailures >= 5) {
          agentTaskRunning.value = false
          endStreamingView(true)
          throw new Error('与后端连续5次通信失败，请检查服务状态；任务可能仍在后台运行，刷新页面可续上')
        }
        await sleep(2000)
        continue
      }
      // 实时刷新进度时间线
      if (Array.isArray(task.progress)) {
        agentProgress.value = task.progress
      }
      const nextPartial = task.partialReport || ''
      // 自检打回：初稿快照被后端清空但任务还在跑 → 切回进度弹窗看补证过程，别让用户对着空白报告干等
      if (!nextPartial && reportStreaming.value && task.status === 'RUNNING') {
        reportStreaming.value = false
        analysisDialogVisible.value = false
        agentProgressVisible.value = true
      }
      // 收口阶段的流式报告快照，渐进渲染；没有就保持为空
      agentPartialReport.value = nextPartial
      // 首片快照到达：切到分析报告弹窗渐进展示，进度弹窗退场
      if (agentPartialReport.value && !reportStreaming.value) {
        reportStreaming.value = true
        analysisResult.value = null
        feedbackEditable.value = false
        activeReportTab.value = 'report'
        agentProgressVisible.value = false
        analysisDialogVisible.value = true
      }
      if (task.status === 'SUCCESS') {
        clearActiveTask()
        agentTaskRunning.value = false
        // 成功：报告弹窗留着，正式结果随后换入
        endStreamingView(false)
        return task.result
      }
      // 后台已停止：当正常收尾，返回 null（无结果），别当失败弹红
      if (task.status === 'CANCELLED') {
        clearActiveTask()
        agentTaskRunning.value = false
        agentTaskStopping.value = false
        endStreamingView(true)
        return null
      }
      if (task.status === 'INTERRUPTED' && !resumed && task.checkpoint && task.checkpoint.phase !== 'COMPLETED') {
        resumed = true
        agentProgress.value = [...agentProgress.value, `任务中断，正在从第 ${task.checkpoint.iteration || 0} 轮恢复`].slice(-60)
        await resumeAgentAnalysisTask(taskId)
        continue
      }
      if (task.status === 'FAILED' || task.status === 'NOT_FOUND' || task.status === 'INTERRUPTED') {
        clearActiveTask()
        agentTaskRunning.value = false
        endStreamingView(true)
        throw new Error(task.message || 'Agent分析失败')
      }
      // 流式生成阶段加密到 500ms，报告蹦字更跟手；平时 2s 一拍省请求
      await sleep(reportStreaming.value ? 500 : 2000)
    }
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
  agentPartialReport.value = ''
  reportStreaming.value = false
  agentTaskStopping.value = false
  saveActiveTask(taskId)
  agentProgressVisible.value = true
  try {
    const result = await waitAgentTask(taskId)
    agentProgressVisible.value = false
    if (!result) {
      message.info('分析已停止')
      return
    }
    analysisResult.value = result
    feedbackEditable.value = false
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('Agent分析完成！')
  } catch (error) {
    agentProgressVisible.value = false
    clearActiveTask()
    message.error(error.message || 'Agent分析失败')
  }
}

// ===== 追问式分析：基于已落库的分析记录继续提问 =====
// 对话气泡列表：[{ role: 'q'|'a', text }]
export const followUpChat = ref([])
// 追问进行中（禁发送、显示停止）
export const followUpAsking = ref(false)
// 追问答案的流式快照，气泡里渐进渲染
export const followUpStreamText = ref('')
// 追问查证进度（工具步骤一行），气泡上方小字展示
export const followUpStep = ref('')
let followUpTaskId = ''

// 切换到别的记录/发起新分析时清空追问会话（后端历史还在 Redis，重新问会带上文）
watch(analysisResult, (next, prev) => {
  if (next?.id !== prev?.id) {
    followUpChat.value = []
    followUpStreamText.value = ''
    followUpStep.value = ''
    followUpAsking.value = false
  }
})

/** 发送一条追问：提交任务后独立轮询（不走主分析的 waitAgentTask，避免弹窗切换逻辑串台）。 */
export async function askFollowUp(question) {
  const recordId = analysisResult.value?.id
  const text = (question || '').trim()
  if (!recordId || !text || followUpAsking.value) {
    return
  }
  followUpChat.value = [...followUpChat.value, { role: 'q', text }]
  followUpAsking.value = true
  followUpStreamText.value = ''
  followUpStep.value = ''
  try {
    const task = await submitFollowUpTask(recordId, text)
    followUpTaskId = task.taskId
    let pollFailures = 0
    let resumed = false
    while (true) {
      let status
      try {
        status = await pollAgentAnalysisTask(followUpTaskId)
        pollFailures = 0
      } catch (pollError) {
        // 登录过期：静默退出，全局已经提示过一次，不再往对话里塞失败气泡
        if (pollError.code === 'UNAUTHORIZED') {
          return
        }
        pollFailures++
        if (pollFailures >= 5) {
          throw new Error('与后端连续5次通信失败，请检查服务状态')
        }
        await sleep(2000)
        continue
      }
      // 最新一条查证步骤 + 流式答案快照
      if (Array.isArray(status.progress) && status.progress.length) {
        followUpStep.value = status.progress[status.progress.length - 1]
      }
      followUpStreamText.value = status.partialReport || ''
      if (status.status === 'SUCCESS') {
        followUpChat.value = [...followUpChat.value, { role: 'a', text: status.result?.conclusion || '（无回答）' }]
        break
      }
      if (status.status === 'CANCELLED') {
        followUpChat.value = [...followUpChat.value, { role: 'a', text: '（已停止）' }]
        break
      }
      if (status.status === 'INTERRUPTED' && !resumed && status.checkpoint && status.checkpoint.phase !== 'COMPLETED') {
        resumed = true
        followUpStep.value = `任务中断，正在从第 ${status.checkpoint.iteration || 0} 轮恢复`
        await resumeAgentAnalysisTask(followUpTaskId)
        continue
      }
      if (status.status === 'FAILED' || status.status === 'NOT_FOUND' || status.status === 'INTERRUPTED') {
        throw new Error(status.message || '追问失败')
      }
      await sleep(followUpStreamText.value ? 500 : 1000)
    }
  } catch (error) {
    followUpChat.value = [...followUpChat.value, { role: 'a', text: '⚠️ ' + (error.message || '追问失败') }]
  } finally {
    followUpAsking.value = false
    followUpStreamText.value = ''
    followUpStep.value = ''
    followUpTaskId = ''
  }
}

/** 停止当前追问。 */
export async function stopFollowUp() {
  if (!followUpTaskId) {
    return
  }
  try {
    await stopAgentAnalysisTask(followUpTaskId)
  } catch (error) {
    message.error(error.message || '停止失败')
  }
}

/**
 * 登出清场：先让正在跑的轮询循环自行终止（它每轮开头查 agentTaskStopping），
 * 再抹掉本地任务痕迹——否则同一浏览器换个人登录会续上前一个人的分析任务。
 */
export function abortAndResetAnalysis() {
  agentTaskStopping.value = true
  clearActiveTask()
  agentTaskRunning.value = false
  agentProgress.value = []
  agentPartialReport.value = ''
  reportStreaming.value = false
  agentProgressVisible.value = false
  analysisDialogVisible.value = false
  screenshotFiles.value = []
  logFileList.value = []
  logUploadProgress.value = ''
  analysisForm.logId = ''
  followUpChat.value = []
  followUpStreamText.value = ''
  followUpStep.value = ''
  followUpAsking.value = false
  followUpTaskId = ''
}
