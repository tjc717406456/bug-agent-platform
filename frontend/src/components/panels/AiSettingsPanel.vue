<template>
  <section class="panel">
    <div class="panel-title">
      <h2>AI 配置</h2>
      <div>
        <a-button @click="testAiAction"><template #icon><ApiOutlined /></template>测试当前</a-button>
        <a-button class="left-gap" @click="testEmbeddingAction"><template #icon><ApiOutlined /></template>测试向量</a-button>
        <a-button type="primary" class="left-gap" @click="openAiDialog"><template #icon><PlusOutlined /></template>新增配置</a-button>
      </div>
    </div>
    <p class="panel-tip">主/辅模型同一时间只启用一个，决定 Agent 分析走哪个 AI；向量(embedding)是独立开关，开了才做语义召回。</p>
    <a-table class="top-gap" :data-source="aiConfigs" :pagination="false" row-key="id" size="middle">
      <a-table-column title="启用" :width="64">
        <template #default="{ record }">
          <a-switch v-if="record.role === 'EMBEDDING'" :checked="!!record.enabled" size="small" @change="() => activateAiAction(record.id)" />
          <a-radio v-else :checked="selectedAiConfigId === record.id" @change="() => activateAiAction(record.id)" />
        </template>
      </a-table-column>
      <a-table-column title="Provider" data-index="provider" :width="160" />
      <a-table-column title="Model" data-index="modelName" :width="170" />
      <a-table-column title="Base URL" data-index="baseUrl" :ellipsis="true" />
      <a-table-column title="超时秒" data-index="timeoutSeconds" :width="80" />
      <a-table-column title="角色" :width="80">
        <template #default="{ record }">
          <a-tag :color="record.role === 'UTILITY' ? 'blue' : record.role === 'EMBEDDING' ? 'orange' : 'purple'">{{ record.role === 'UTILITY' ? '辅助' : record.role === 'EMBEDDING' ? '向量' : '主分析' }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="视觉" :width="70">
        <template #default="{ record }">
          <a-tag v-if="record.supportsVision" color="green">支持</a-tag>
          <span v-else style="color:#aaa">—</span>
        </template>
      </a-table-column>
      <a-table-column title="操作" :width="120">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openEditAiDialog(record)">编辑</a-button>
          <a-button type="link" danger size="small" @click="deleteAiAction(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>
  </section>
</template>

<script setup>
import { ApiOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { testAiAction, testEmbeddingAction, openAiDialog, openEditAiDialog, aiConfigs, selectedAiConfigId, activateAiAction, deleteAiAction } = useAppStore()
</script>
