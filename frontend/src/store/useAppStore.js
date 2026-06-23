import * as core from './core'
import * as projectStore from './modules/projectStore'
import * as analysisStore from './modules/analysisStore'
import * as historyStore from './modules/historyStore'
import * as dbhubStore from './modules/dbhubStore'
import * as aiConfigStore from './modules/aiConfigStore'

// 切面板：进历史页时自动刷一次列表，属 ui 与 history 的跨域编排，放聚合层
function selectPanel(key) {
  core.activePanel.value = key
  core.saveActivePanel(key)
  if (key === 'history') {
    historyStore.searchHistory()
  }
}

// 各域 store 单向依赖 core，这里聚合成单一入口；键名与拆分前 1:1 不变，组件无需改动
export function useAppStore() {
  return {
    ...core,
    ...projectStore,
    ...analysisStore,
    ...historyStore,
    ...dbhubStore,
    ...aiConfigStore,
    selectPanel
  }
}
