import * as core from './core'
import * as projectStore from './modules/projectStore'
import * as analysisStore from './modules/analysisStore'
import * as historyStore from './modules/historyStore'
import * as dbhubStore from './modules/dbhubStore'
import * as aiConfigStore from './modules/aiConfigStore'
import * as authStore from './modules/authStore'
import * as usersStore from './modules/usersStore'

// 切面板：进历史页时自动刷一次列表，属 ui 与 history 的跨域编排，放聚合层
// 管理面板对普通用户直接挡下：菜单虽已隐藏，这里防的是状态被绕过菜单改写
function selectPanel(key) {
  if (core.ADMIN_PANELS.has(key) && !authStore.isAdmin.value) {
    return
  }
  core.activePanel.value = key
  core.saveActivePanel(key)
  if (key === 'history') {
    historyStore.searchHistory()
  }
  if (key === 'users') {
    usersStore.loadUsers()
  }
}

/**
 * 登出/换人时把上一位用户的痕迹清干净：内存状态 + localStorage 里的任务与面板。
 * 放在聚合层而非 authStore，避免 authStore 反向依赖每个业务模块形成环。
 */
function resetAllState() {
  analysisStore.abortAndResetAnalysis()
  projectStore.resetProjectState()
  historyStore.resetHistoryState()
  dbhubStore.resetDbhubState()
  aiConfigStore.resetAiConfigState()
  usersStore.resetUsersState()
  core.currentProject.value = null
  core.analysisResult.value = null
  core.feedbackEditable.value = false
  core.activePanel.value = 'projects'
  core.saveActivePanel('projects')
}

// token 过期与主动登出都走这条清场路径
authStore.registerLogoutCleanup(resetAllState)

// 各域 store 单向依赖 core，这里聚合成单一入口；键名与拆分前 1:1 不变，组件无需改动
export function useAppStore() {
  return {
    ...core,
    ...projectStore,
    ...analysisStore,
    ...historyStore,
    ...dbhubStore,
    ...aiConfigStore,
    ...authStore,
    ...usersStore,
    selectPanel,
    resetAllState
  }
}
