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
        <a-col :span="8">
          <a-form-item label="环境">
            <a-select
              v-model:value="datasourceForm.env"
              :options="environmentOptions"
              placeholder="选择项目环境"
              style="width: 100%"
            />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="dbhub Key">
            <a-select v-model:value="datasourceForm.dbhubKey" :options="dbhubKeyOptions" placeholder="选择数据库" style="width: 100%" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-button type="primary" @click="saveDatasourceAction"><template #icon><CheckOutlined /></template>保存绑定</a-button>
    </a-form>
    <a-divider orientation="left">跨环境结构策略</a-divider>
    <a-form layout="inline" class="compact-form">
      <a-form-item label="各环境表结构一致">
        <a-switch v-model:checked="datasourcePolicyForm.schemaConsistent" />
      </a-form-item>
      <a-form-item label="结构参考环境">
        <a-select
          v-model:value="datasourcePolicyForm.schemaReferenceEnv"
          :options="environmentOptions"
          :disabled="!datasourcePolicyForm.schemaConsistent"
          style="width: 190px"
        />
      </a-form-item>
      <a-form-item>
        <a-button @click="saveDatasourcePolicyAction">保存结构策略</a-button>
      </a-form-item>
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

const {
  datasourceProjectId,
  projectOptions,
  changeDatasourceProject,
  datasourceForm,
  datasourcePolicyForm,
  environmentOptions,
  dbhubKeyOptions,
  saveDatasourceAction,
  saveDatasourcePolicyAction,
  datasources
} = useAppStore()
</script>
