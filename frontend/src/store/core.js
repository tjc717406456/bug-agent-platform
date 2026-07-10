import { reactive, ref } from 'vue'
import { Modal } from 'ant-design-vue'

// 跨域共享的根状态：当前选中项目、分析表单、结果弹窗被多个面板同时读写，
// 集中放这里让各域 store 单向依赖，避免模块间互相 import 形成环
export const currentProject = ref(null)

// 当前侧边栏页持久化，刷新后不回弹「项目管理」
const PANEL_STORAGE_KEY = 'bug-agent-active-panel'
const PANEL_KEYS = new Set(['projects', 'source', 'ai-settings', 'dbhub-sources', 'project-dbhub', 'analysis', 'history', 'users'])
// 只有管理员能进的面板：AI 密钥、生产库凭据、用户管理，以及项目维护类（源码导入、数据源绑定）
export const ADMIN_PANELS = new Set(['ai-settings', 'dbhub-sources', 'users', 'source', 'project-dbhub'])

function loadActivePanel() {
  if (typeof window === 'undefined') return 'projects'
  const saved = window.localStorage.getItem(PANEL_STORAGE_KEY)
  return PANEL_KEYS.has(saved) ? saved : 'projects'
}

export const activePanel = ref(loadActivePanel())

export function saveActivePanel(panel) {
  if (typeof window !== 'undefined' && PANEL_KEYS.has(panel)) {
    window.localStorage.setItem(PANEL_STORAGE_KEY, panel)
  }
}

/**
 * 上一个用户是管理员、停在管理面板，换普通用户登录后 localStorage 还留着那个面板，
 * 会直接落到一个看不见内容的空页。登录/恢复会话后调一次把它拨回项目页。
 */
export function sanitizeActivePanel(isAdmin) {
  if (!isAdmin && ADMIN_PANELS.has(activePanel.value)) {
    activePanel.value = 'projects'
    saveActivePanel('projects')
  }
}

// 分析输入表单：analysis 域主导，project 域联动版本/路由选择，history 回看时复用
export const analysisForm = reactive({ versionId: '', apiPath: '', userDescription: '', requestBody: '', responseBody: '', stackTrace: '', traceId: '', requestTime: '', logText: '', logId: '', deepMode: false })
export const analysisResult = ref(null)
export const analysisDialogVisible = ref(false)
export const activeReportTab = ref('report')
// 反馈标注只在历史页查看时可填；刚分析完不显示，让开发去历史确认
export const feedbackEditable = ref(false)

// Modal.confirm 的 Promise 封装：取消时 reject，保持调用方 await 流程不变
export function confirm(content, title = '确认') {
  return new Promise((resolve, reject) => {
    Modal.confirm({ title, content, okText: '确定', cancelText: '取消', onOk: () => resolve(true), onCancel: () => reject(new Error('cancelled')) })
  })
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
