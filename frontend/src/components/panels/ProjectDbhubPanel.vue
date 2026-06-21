<template>
  <section class="panel">
    <div class="panel-title"><h2>项目 dbhub 绑定</h2></div>
    <a-form layout="vertical" class="compact-form">
      <a-row :gutter="16">
        <a-col :span="8">
          <a-form-item label="指定项目">
            <a-select
              v-model:value="datasourceProjectId"
              :options="projectOptions"
              show-search
              option-filter-prop="label"
              placeholder="选择项目"
              style="width: 100%"
              @change="changeDatasourceProject"
            />
          </a-form-item>
        </a-col>
        <a-col :span="8"><a-form-item label="环境"><a-input v-model:value="datasourceForm.env" /></a-form-item></a-col>
        <a-col :span="8">
          <a-form-item label="dbhub Key">
            <a-select v-model:value="datasourceForm.dbhubKey" :options="dbhubKeyOptions" placeholder="选择数据库" style="width: 100%" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-button type="primary" @click="saveDatasourceAction"><template #icon><CheckOutlined /></template>保存绑定</a-button>
    </a-form>
    <a-table class="top-gap" :data-source="datasources" :pagination="false" row-key="id" size="middle">
      <a-table-column title="环境" data-index="env" :width="100" />
      <a-table-column title="dbhub Key" data-index="dbhubKey" />
    </a-table>
  </section>
</template>

<script setup>
import { CheckOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { datasourceProjectId, projectOptions, changeDatasourceProject, datasourceForm, dbhubKeyOptions, saveDatasourceAction, datasources } = useAppStore()
</script>
