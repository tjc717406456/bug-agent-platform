<template>
  <section class="panel">
    <div class="panel-title">
      <h2>dbhub 数据源管理</h2>
      <div>
        <a-button danger :disabled="!selectedDbhubKeys.length" @click="deleteSelectedDbhubDatasources">删除选中</a-button>
        <a-button type="primary" class="left-gap" @click="openDbhubDialog()"><template #icon><PlusOutlined /></template>新增数据源</a-button>
      </div>
    </div>
    <a-form layout="inline" class="compact-form">
      <a-form-item label="用户名"><a-input v-model:value="dbhubQuery.user" allow-clear /></a-form-item>
      <a-form-item label="库名"><a-input v-model:value="dbhubQuery.database" allow-clear /></a-form-item>
      <a-form-item><a-button @click="resetDbhubQuery">重置</a-button></a-form-item>
    </a-form>
    <a-table
      class="top-gap"
      :data-source="filteredDbhubDatasources"
      :pagination="false"
      row-key="key"
      :row-selection="dbhubRowSelection"
      :custom-row="dbhubRowEvents"
      size="middle"
    >
      <a-table-column title="Key" data-index="key" :width="140" />
      <a-table-column title="主机" data-index="host" :width="160" />
      <a-table-column title="端口" data-index="port" :width="80" />
      <a-table-column title="库名" data-index="database" />
      <a-table-column title="用户" data-index="user" :width="140" />
      <a-table-column title="操作" :width="100">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openDbhubDialog(record)">编辑</a-button>
        </template>
      </a-table-column>
    </a-table>
  </section>
</template>

<script setup>
import { PlusOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { selectedDbhubKeys, deleteSelectedDbhubDatasources, openDbhubDialog, dbhubQuery, resetDbhubQuery, filteredDbhubDatasources, dbhubRowSelection, dbhubRowEvents } = useAppStore()
</script>
