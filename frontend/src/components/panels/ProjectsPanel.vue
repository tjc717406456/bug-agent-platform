<template>
  <section class="panel">
    <div class="panel-title">
      <h2>项目管理</h2>
      <a-button v-if="isAdmin" type="primary" @click="openProjectDialog()"><template #icon><PlusOutlined /></template>新增项目</a-button>
    </div>
    <a-form layout="inline" class="compact-form">
      <a-form-item label="项目名称"><a-input v-model:value="projectQuery.name" allow-clear /></a-form-item>
      <a-form-item label="项目编码"><a-input v-model:value="projectQuery.code" allow-clear /></a-form-item>
      <a-form-item>
        <a-button type="primary" @click="loadAll"><template #icon><ReloadOutlined /></template>查询</a-button>
        <a-button class="left-gap" @click="resetProjectQuery">重置</a-button>
      </a-form-item>
    </a-form>
    <a-table
      class="top-gap"
      :data-source="projects"
      :pagination="false"
      row-key="id"
      :custom-row="projectRowEvents"
      :row-class-name="projectRowClass"
      size="middle"
    >
      <a-table-column title="序号" :width="80">
        <template #default="{ index }">{{ index + 1 }}</template>
      </a-table-column>
      <a-table-column title="项目名称" data-index="name" />
      <a-table-column title="编码" data-index="code" />
      <a-table-column title="环境" data-index="environments" />
      <a-table-column title="说明" data-index="description" />
      <!-- 项目由管理员维护：普通用户只有点击选中，不给编辑/删除入口 -->
      <a-table-column v-if="isAdmin" title="操作" :width="150">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openProjectDialog(record)">编辑</a-button>
          <a-button type="link" danger size="small" @click="deleteProjectAction(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>
  </section>
</template>

<script setup>
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { projects, projectQuery, loadAll, resetProjectQuery, openProjectDialog, deleteProjectAction, projectRowEvents, projectRowClass, isAdmin } = useAppStore()
</script>
