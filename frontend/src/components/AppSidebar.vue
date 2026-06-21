<template>
  <a-layout-sider :width="248" class="sidebar" theme="dark">
    <div class="brand">
      <BugOutlined />
      <span>Bug Agent</span>
    </div>
    <a-menu :selected-keys="[activePanel]" theme="dark" mode="inline" @click="({ key }) => selectPanel(key)">
      <a-menu-item key="projects"><template #icon><FolderOutlined /></template>项目管理</a-menu-item>
      <a-menu-item key="source"><template #icon><UploadOutlined /></template>源码导入</a-menu-item>
      <a-menu-item key="ai-settings"><template #icon><SettingOutlined /></template>AI 配置</a-menu-item>
      <a-menu-item key="dbhub-sources"><template #icon><ApiOutlined /></template>dbhub数据源</a-menu-item>
      <a-menu-item key="project-dbhub"><template #icon><SettingOutlined /></template>项目dbhub绑定</a-menu-item>
      <a-menu-item key="analysis"><template #icon><BarChartOutlined /></template>Bug 分析</a-menu-item>
      <a-menu-item key="history"><template #icon><HistoryOutlined /></template>分析历史</a-menu-item>
    </a-menu>
  </a-layout-sider>
</template>

<script setup>
import { ApiOutlined, BarChartOutlined, BugOutlined, FolderOutlined, HistoryOutlined, SettingOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../store/useAppStore'

const { activePanel, selectPanel } = useAppStore()
</script>
