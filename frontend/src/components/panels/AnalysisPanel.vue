<template>
  <section class="panel">
    <div class="panel-title analysis-header">
      <div>
        <h2>Bug 分析工作台</h2>
        <p>先选项目，再选接口地址，减少手填。</p>
      </div>
      <div class="analysis-status">
        <a-tag>{{ currentProject?.name || '未选项目' }}</a-tag>
        <a-tag v-if="currentDatasource" color="orange">{{ currentDatasource.dbhubKey }}</a-tag>
      </div>
    </div>
    <a-alert v-if="!currentProject" type="warning" show-icon message="先选择项目并完成源码索引" />
    <div v-else class="analysis-section">
      <div class="section-title">
        <h3>问题输入</h3>
        <span>接口、版本、证据</span>
        <a-button size="small" style="margin-left:auto" @click="pasteFromClipboard">📋 从剪贴板回填</a-button>
      </div>
      <a-form layout="horizontal" :label-col="{ style: { width: '92px' } }" :wrapper-col="{ flex: 'auto' }" class="analysis-form">
        <a-row :gutter="16">
          <a-col :span="6">
            <a-form-item label="项目">
              <a-select
                v-model:value="selectedProjectId"
                :options="projectOptions"
                show-search
                option-filter-prop="label"
                placeholder="选择项目"
                style="width: 100%"
                @change="changeProject"
              />
            </a-form-item>
          </a-col>
          <a-col :span="9">
            <a-form-item label="接口地址">
              <a-select
                v-model:value="selectedApiPrefix"
                :options="apiPrefixOptions"
                show-search
                option-filter-prop="label"
                allow-clear
                placeholder="选择前缀"
                :loading="routesLoading"
                style="width: 100%"
                @focus="loadApiRoutes()"
                @change="changeApiPrefix"
              />
            </a-form-item>
          </a-col>
          <a-col :span="9">
            <a-form-item label="接口二级">
              <a-select
                v-model:value="analysisForm.apiPath"
                :options="apiRouteOptions"
                show-search
                option-filter-prop="label"
                allow-clear
                placeholder="选择接口"
                :disabled="!selectedApiPrefix"
                style="width: 100%"
                @change="changeApiPath"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="问题描述">
          <a-textarea v-model:value="analysisForm.userDescription" :rows="4" placeholder="可选，例如：列表只查出3条，实际数据库有5条，怀疑筛选条件或字段类型不对" />
        </a-form-item>
        <a-row :gutter="16">
          <a-col :span="8"><a-form-item label="请求参数"><a-textarea v-model:value="analysisForm.requestBody" :rows="3" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="响应结果"><a-textarea v-model:value="analysisForm.responseBody" :rows="3" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="异常堆栈"><a-textarea v-model:value="analysisForm.stackTrace" :rows="3" placeholder="可选" /></a-form-item></a-col>
        </a-row>
        <a-form-item label="日志">
          <a-row :gutter="16">
            <a-col :span="16">
              <a-textarea v-model:value="analysisForm.logText" :rows="4" placeholder="可选，粘贴一段日志，或右侧上传 .log 文件，自动抠出堆栈、SQL、traceId、时间" />
            </a-col>
            <a-col :span="8">
              <a-upload-dragger :max-count="1" accept=".log,.txt" :file-list="logFileList" :before-upload="beforeLogUpload" @remove="removeLog">
                <p class="ant-upload-drag-icon"><InboxOutlined /></p>
                <p class="ant-upload-text">拖入 .log 文件或点击选择（≤10MB）</p>
              </a-upload-dragger>
              <a-button type="link" size="small" block @click="openLogSplit">
                <template #icon><ScissorOutlined /></template>大文件超 10MB？按时间切割
              </a-button>
            </a-col>
          </a-row>
        </a-form-item>
        <a-row :gutter="16" align="middle">
          <a-col :span="16">
            <a-form-item label="错误截图" style="margin-bottom:0">
              <a-upload-dragger
                multiple
                :max-count="3"
                accept="image/png,image/jpeg,image/jpg,image/webp"
                :file-list="screenshotFileList"
                :before-upload="beforeScreenshotUpload"
                @remove="removeScreenshot"
              >
                <p class="ant-upload-drag-icon"><InboxOutlined /></p>
                <p class="ant-upload-text">可选，拖入截图或点击选择，最多3张</p>
              </a-upload-dragger>
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <div class="actions-col">
              <a-button type="primary" @click="analyzeAction"><template #icon><BarChartOutlined /></template>开始分析</a-button>
              <a-button class="left-gap" style="background:#52c41a;border-color:#52c41a;color:#fff" @click="agentAnalyzeAction"><template #icon><BarChartOutlined /></template>Agent分析</a-button>
              <a-button class="left-gap" style="background:#fa8c16;border-color:#fa8c16;color:#fff" @click="apiAnalyzeAction"><template #icon><BarChartOutlined /></template>接口分析</a-button>
            </div>
          </a-col>
        </a-row>
      </a-form>
    </div>
  </section>
</template>

<script setup>
import { BarChartOutlined, InboxOutlined, ScissorOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const {
  currentProject,
  currentDatasource,
  selectedProjectId,
  projectOptions,
  changeProject,
  selectedApiPrefix,
  apiPrefixOptions,
  routesLoading,
  loadApiRoutes,
  changeApiPrefix,
  analysisForm,
  apiRouteOptions,
  changeApiPath,
  logFileList,
  beforeLogUpload,
  removeLog,
  screenshotFileList,
  beforeScreenshotUpload,
  removeScreenshot,
  analyzeAction,
  agentAnalyzeAction,
  apiAnalyzeAction,
  pasteFromClipboard,
  openLogSplit
} = useAppStore()
</script>
