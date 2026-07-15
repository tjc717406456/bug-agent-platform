<template>
  <!-- 会话恢复完成前先转圈：否则会先闪一下登录页再跳进主界面 -->
  <div v-if="!authReady" class="boot-screen"><a-spin size="large" tip="正在加载…" /></div>

  <LoginPage v-else-if="!isAuthenticated" />

  <a-layout v-else class="layout">
    <AppSidebar />

    <a-layout>
      <a-layout-header class="header">
        <div class="header-actions">
          <a-select
            v-model:value="selectedProjectId"
            :options="projectOptions"
            show-search
            option-filter-prop="label"
            allow-clear
            placeholder="选择项目"
            style="width: 240px"
            @change="changeProject"
          />
          <a-button @click="loadAll"><template #icon><ReloadOutlined /></template>刷新</a-button>
          <span class="user-box">
            <a-tag :color="isAdmin ? 'red' : 'blue'">{{ isAdmin ? '管理员' : '普通用户' }}</a-tag>
            <span class="user-name">{{ currentUser?.displayName || currentUser?.username }}</span>
            <a-button size="small" @click="changePwdVisible = true">修改密码</a-button>
            <a-button size="small" @click="logoutAction">退出</a-button>
          </span>
        </div>
      </a-layout-header>

      <a-layout-content class="main">
        <ProjectsPanel v-show="activePanel === 'projects'" />
        <!-- 管理面板双重把关：菜单隐藏 + 这里不渲染，普通用户改状态也进不来 -->
        <SourcePanel v-if="isAdmin" v-show="activePanel === 'source'" />
        <AiSettingsPanel v-if="isAdmin" v-show="activePanel === 'ai-settings'" />
        <DbhubSourcesPanel v-if="isAdmin" v-show="activePanel === 'dbhub-sources'" />
        <UsersPanel v-if="isAdmin" v-show="activePanel === 'users'" />
        <ProjectDbhubPanel v-if="isAdmin" v-show="activePanel === 'project-dbhub'" />
        <AnalysisPanel v-show="activePanel === 'analysis'" />
        <HistoryPanel v-show="activePanel === 'history'" />
      </a-layout-content>
    </a-layout>

    <AgentProgressModal />
    <AnalysisReportModal />
    <ProjectModal />
    <DbhubModal />
    <AiConfigModal />
    <LogSplitModal />
    <UserModal v-if="isAdmin" />
    <ChangePasswordModal />
  </a-layout>
</template>

<script setup>
import { onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import AppSidebar from './components/AppSidebar.vue'
import LoginPage from './components/LoginPage.vue'
import ProjectsPanel from './components/panels/ProjectsPanel.vue'
import SourcePanel from './components/panels/SourcePanel.vue'
import AiSettingsPanel from './components/panels/AiSettingsPanel.vue'
import DbhubSourcesPanel from './components/panels/DbhubSourcesPanel.vue'
import ProjectDbhubPanel from './components/panels/ProjectDbhubPanel.vue'
import AnalysisPanel from './components/panels/AnalysisPanel.vue'
import HistoryPanel from './components/panels/HistoryPanel.vue'
import UsersPanel from './components/panels/UsersPanel.vue'
import AgentProgressModal from './components/modals/AgentProgressModal.vue'
import AnalysisReportModal from './components/modals/AnalysisReportModal.vue'
import ProjectModal from './components/modals/ProjectModal.vue'
import DbhubModal from './components/modals/DbhubModal.vue'
import AiConfigModal from './components/modals/AiConfigModal.vue'
import LogSplitModal from './components/modals/LogSplitModal.vue'
import UserModal from './components/modals/UserModal.vue'
import ChangePasswordModal from './components/modals/ChangePasswordModal.vue'
import { useAppStore } from './store/useAppStore'

const {
  activePanel, selectedProjectId, projectOptions, changeProject, loadAll, resumeAgentTask,
  authReady, isAuthenticated, isAdmin, currentUser, restoreSession, logoutAction, sanitizeActivePanel,
  changePwdVisible
} = useAppStore()

/** 拉基础数据、续上未完成任务。必须在拿到有效 token 之后跑，否则满屏 401。 */
async function bootstrap() {
  try {
    sanitizeActivePanel(isAdmin.value)
    await loadAll()
    await resumeAgentTask()
  } catch (error) {
    // 别静默失败：初始化拉不到数据要让人看得见，否则页面只是一片 No data
    message.error('加载数据失败：' + (error.message || error))
  }
}

/**
 * 由登录态驱动初始化，而不是靠 LoginPage 的 emit。
 * 登录成功时 currentUser 一赋值，App 的重渲染就会先于子组件 await 的续体执行，
 * 那时 LoginPage 已被卸载，emit 发给的是个死组件——用 watch 才既覆盖登录也覆盖刷新恢复。
 */
watch(isAuthenticated, (authed) => {
  if (authed) {
    bootstrap()
  }
})

onMounted(restoreSession)
</script>

<style>
/* 拆组件后子组件用到的布局类需全局可见，故不加 scoped */
.active-row > td {
  background: #e6f4ff;
}
.left-gap {
  margin-left: 8px;
}
/* 分析台收紧：表单项间距小一点、上传框矮一点，整页不出滚动条 */
.analysis-form .ant-form-item {
  margin-bottom: 12px;
}
.analysis-form .ant-upload-drag {
  padding: 6px;
}
.analysis-form .ant-upload-drag-icon {
  margin: 0 0 2px;
  font-size: 22px;
}
.analysis-form .ant-upload-text {
  font-size: 12px;
  margin: 0;
}
.analysis-evidence-row .ant-form-item {
  margin-bottom: 12px;
}
.analysis-evidence-row .evidence-upload .ant-upload-drag {
  height: 84px;
  min-height: 0;
}
.analysis-evidence-row .evidence-upload .ant-upload-btn {
  padding: 0 12px !important;
}
.analysis-evidence-row .evidence-upload .ant-upload-drag-container {
  display: block;
}
.evidence-upload-content {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  height: 82px;
  color: #303133;
  line-height: 1.35;
  text-align: left;
}
.evidence-upload-content .anticon {
  flex: 0 0 auto;
  color: #1677ff;
  font-size: 26px;
}
.evidence-upload-content small {
  color: #8c8c8c;
  font-size: 12px;
}
.analysis-command-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-left: 92px;
  padding: 14px 16px;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  background: #f8fafc;
}
.analysis-mode-options,
.analysis-mode-switches,
.analysis-submit-actions,
.analysis-switch-item {
  display: flex;
  align-items: center;
}
.analysis-mode-options {
  gap: 18px;
}
.analysis-mode-switches {
  gap: 22px;
}
.analysis-switch-item {
  gap: 7px;
  color: #303133;
  white-space: nowrap;
}
.analysis-command-label {
  color: #7a8494;
  font-size: 13px;
  white-space: nowrap;
}
.analysis-submit-actions {
  gap: 10px;
}
.analysis-submit-actions .ant-btn {
  min-width: 112px;
}
.bug-analysis-button {
  background: #52c41a;
  border-color: #52c41a;
}
.api-explain-button {
  color: #fff;
  background: #fa8c16;
  border-color: #fa8c16;
}
.api-explain-button:hover,
.api-explain-button:focus {
  color: #fff !important;
  background: #ff9c32 !important;
  border-color: #ff9c32 !important;
}
@media (max-width: 1100px) {
  .analysis-command-bar {
    align-items: flex-start;
    flex-direction: column;
    margin-left: 0;
  }
  .analysis-submit-actions {
    align-self: flex-end;
  }
}
.boot-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-box {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-box .user-name {
  color: #555;
  font-size: 13px;
}
</style>
