<template>
  <a-layout-sider :width="248" class="sidebar" theme="dark">
    <div class="brand">
      <BugOutlined />
      <span>Bug Agent</span>
    </div>
    <a-menu :selected-keys="[activePanel]" theme="dark" mode="inline" @click="({ key }) => selectPanel(key)">
      <a-menu-item key="projects"><template #icon><FolderOutlined /></template>项目管理</a-menu-item>
      <a-menu-item key="source"><template #icon><UploadOutlined /></template>源码导入</a-menu-item>
      <!-- AI 密钥与生产库凭据是全局资源，只对管理员开放 -->
      <a-menu-item v-if="isAdmin" key="ai-settings"><template #icon><SettingOutlined /></template>AI 配置</a-menu-item>
      <a-menu-item v-if="isAdmin" key="dbhub-sources"><template #icon><ApiOutlined /></template>dbhub数据源</a-menu-item>
      <a-menu-item key="project-dbhub"><template #icon><SettingOutlined /></template>项目dbhub绑定</a-menu-item>
      <a-menu-item key="analysis"><template #icon><BarChartOutlined /></template>Bug 分析</a-menu-item>
      <a-menu-item key="history"><template #icon><HistoryOutlined /></template>分析历史</a-menu-item>
      <a-menu-item v-if="isAdmin" key="users"><template #icon><TeamOutlined /></template>用户管理</a-menu-item>
    </a-menu>
  </a-layout-sider>
</template>

<script setup>
import { ApiOutlined, BarChartOutlined, BugOutlined, FolderOutlined, HistoryOutlined, SettingOutlined, TeamOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../store/useAppStore'

const { activePanel, selectPanel, isAdmin } = useAppStore()
</script>
