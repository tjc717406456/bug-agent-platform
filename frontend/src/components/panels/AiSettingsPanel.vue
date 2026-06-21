<template>
  <section class="panel">
    <div class="panel-title">
      <h2>AI 配置</h2>
      <div>
        <a-button @click="testAiAction"><template #icon><ApiOutlined /></template>测试当前</a-button>
        <a-button type="primary" class="left-gap" @click="openAiDialog"><template #icon><PlusOutlined /></template>新增配置</a-button>
      </div>
    </div>
    <p class="panel-tip">选中的配置决定 Agent 分析走哪个 AI，同一时间只启用一个。</p>
    <a-table class="top-gap" :data-source="aiConfigs" :pagination="false" row-key="id" size="middle">
      <a-table-column title="启用" :width="64">
        <template #default="{ record }">
          <a-radio :checked="selectedAiConfigId === record.id" @change="() => activateAiAction(record.id)" />
        </template>
      </a-table-column>
      <a-table-column title="Provider" data-index="provider" :width="160" />
      <a-table-column title="Model" data-index="modelName" :width="170" />
      <a-table-column title="Base URL" data-index="baseUrl" :ellipsis="true" />
      <a-table-column title="超时秒" data-index="timeoutSeconds" :width="90" />
      <a-table-column title="操作" :width="90">
        <template #default="{ record }">
          <a-button type="link" danger size="small" @click="deleteAiAction(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>
  </section>
</template>

<script setup>
import { ApiOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { testAiAction, openAiDialog, aiConfigs, selectedAiConfigId, activateAiAction, deleteAiAction } = useAppStore()
</script>
