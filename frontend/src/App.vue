<template>
  <a-layout class="layout">
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
        </div>
      </a-layout-header>

      <a-layout-content class="main">
        <ProjectsPanel v-show="activePanel === 'projects'" />
        <SourcePanel v-show="activePanel === 'source'" />
        <AiSettingsPanel v-show="activePanel === 'ai-settings'" />
        <DbhubSourcesPanel v-show="activePanel === 'dbhub-sources'" />
        <ProjectDbhubPanel v-show="activePanel === 'project-dbhub'" />
        <AnalysisPanel v-show="activePanel === 'analysis'" />
        <HistoryPanel v-show="activePanel === 'history'" />
      </a-layout-content>
    </a-layout>

    <AgentProgressModal />
    <AnalysisReportModal />
    <ProjectModal />
    <DbhubModal />
    <AiConfigModal />
  </a-layout>
</template>

<script setup>
import { onMounted } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import AppSidebar from './components/AppSidebar.vue'
import ProjectsPanel from './components/panels/ProjectsPanel.vue'
import SourcePanel from './components/panels/SourcePanel.vue'
import AiSettingsPanel from './components/panels/AiSettingsPanel.vue'
import DbhubSourcesPanel from './components/panels/DbhubSourcesPanel.vue'
import ProjectDbhubPanel from './components/panels/ProjectDbhubPanel.vue'
import AnalysisPanel from './components/panels/AnalysisPanel.vue'
import HistoryPanel from './components/panels/HistoryPanel.vue'
import AgentProgressModal from './components/modals/AgentProgressModal.vue'
import AnalysisReportModal from './components/modals/AnalysisReportModal.vue'
import ProjectModal from './components/modals/ProjectModal.vue'
import DbhubModal from './components/modals/DbhubModal.vue'
import AiConfigModal from './components/modals/AiConfigModal.vue'
import { useAppStore } from './store/useAppStore'

const { activePanel, selectedProjectId, projectOptions, changeProject, loadAll } = useAppStore()

onMounted(loadAll)
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
.analysis-actions {
  padding-top: 0;
}
.actions-col {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
</style>
