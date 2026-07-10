<template>
  <section class="panel">
    <div class="panel-title">
      <h2>分析历史</h2>
      <div>
        <a-button danger :disabled="!selectedHistoryKeys.length" @click="deleteSelectedRecords">删除选中</a-button>
        <a-button class="left-gap" @click="loadHistory"><template #icon><ReloadOutlined /></template>刷新</a-button>
      </div>
    </div>
    <p class="panel-tip">bug 修复后回来标注结论对错，逻辑类问题的真因会沉淀进评估飞轮。绿标「已连库验证」的无需手动标。</p>
    <a-form layout="inline" class="compact-form">
      <a-form-item label="项目">
        <a-select v-model:value="historyQuery.projectId" :options="projectOptions" show-search option-filter-prop="label" allow-clear placeholder="全部项目" style="width: 200px" @change="searchHistory" />
      </a-form-item>
      <a-form-item label="接口">
        <a-input v-model:value="historyQuery.apiPath" allow-clear placeholder="接口路径模糊匹配" @pressEnter="searchHistory" />
      </a-form-item>
      <a-form-item label="类型">
        <a-select v-model:value="historyQuery.recordType" allow-clear placeholder="全部" style="width: 120px" @change="searchHistory">
          <a-select-option value="ANALYSIS">分析</a-select-option>
          <a-select-option value="EXPLAIN">讲解</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item><a-button type="primary" @click="searchHistory"><template #icon><ReloadOutlined /></template>查询</a-button></a-form-item>
    </a-form>
    <a-table
      class="top-gap"
      :data-source="analysisRecords"
      row-key="id"
      size="middle"
      :row-selection="historyRowSelection"
      :pagination="historyPagination"
      @change="onHistoryTableChange"
    >
      <a-table-column title="序号" :width="70">
        <template #default="{ index }">{{ (historyPagination.current - 1) * historyPagination.pageSize + index + 1 }}</template>
      </a-table-column>
      <a-table-column title="接口" data-index="apiPath" :width="180" />
      <a-table-column title="类型" :width="80">
        <template #default="{ record }">
          <a-tag v-if="record.recordType === 'EXPLAIN'" color="cyan">讲解</a-tag>
          <a-tag v-else color="geekblue">分析</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="结论" data-index="conclusion" :ellipsis="true" />
      <a-table-column title="置信度" data-index="confidence" :width="90" />
      <a-table-column title="验证" :width="110">
        <template #default="{ record }">
          <a-tag v-if="record.autoVerify === 'CONFIRMED'" color="green">已验证</a-tag>
          <a-tag v-else-if="record.autoVerify === 'REFUTED'" color="orange">存疑</a-tag>
          <span v-else>-</span>
        </template>
      </a-table-column>
      <a-table-column title="标注" :width="90">
        <template #default="{ record }">
          <a-tag v-if="record.feedbackVerdict" color="blue">已标注</a-tag>
          <span v-else>-</span>
        </template>
      </a-table-column>
      <a-table-column title="发起人" data-index="createdByName" :width="100">
        <template #default="{ record }">{{ record.createdByName || '-' }}</template>
      </a-table-column>
      <a-table-column title="时间" data-index="createdAt" :width="170" />
      <a-table-column title="操作" :width="80">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="viewRecord(record)">查看</a-button>
        </template>
      </a-table-column>
    </a-table>
  </section>
</template>

<script setup>
import { ReloadOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { selectedHistoryKeys, deleteSelectedRecords, loadHistory, historyQuery, projectOptions, searchHistory, analysisRecords, historyRowSelection, historyPagination, onHistoryTableChange, viewRecord } = useAppStore()
</script>
