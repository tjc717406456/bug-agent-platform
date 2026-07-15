<template>
  <section class="panel">
    <div class="panel-title analysis-header">
      <div>
        <h2>Bug 分析工作台</h2>
        <p>先选项目，再选接口地址，减少手填。</p>
      </div>
      <div class="analysis-status">
        <a-tag>{{ currentProject?.name || '未选项目' }}</a-tag>
        <a-tag color="blue">{{ analysisForm.environment }}</a-tag>
        <a-tag :color="datasourceAccessPreview.accessLevel === 'BUSINESS_DATA' ? 'green' : 'orange'">
          {{ datasourceAccessPreview.accessLabel }}
        </a-tag>
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
        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="问题环境">
              <a-select
                v-model:value="analysisForm.environment"
                :options="environmentOptions"
                style="width: 100%"
                @change="changeAnalysisEnvironment"
              />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="数据库验证">
              <a-select v-model:value="analysisForm.databasePolicy" :options="databasePolicyOptions" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-alert
              :type="datasourceAccessPreview.accessLevel === 'UNAVAILABLE' ? 'error' : 'info'"
              show-icon
              :message="datasourceAccessPreview.description"
            />
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
        <a-row :gutter="16" class="analysis-evidence-row">
          <a-col :xs="24" :xl="12">
            <a-form-item label="日志">
              <a-textarea v-model:value="analysisForm.logText" :rows="3" placeholder="可选，粘贴一段日志，或右侧上传 .log 文件，自动抠出堆栈、SQL、traceId、时间" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="12" :xl="6">
            <a-form-item label="日志文件">
              <a-upload-dragger class="evidence-upload" :max-count="1" accept=".log,.txt" :file-list="logFileList" :before-upload="beforeLogUpload" @remove="removeLog">
                <div class="evidence-upload-content">
                  <InboxOutlined />
                  <span>拖入或选择 .log 文件<br><small>最大 50MB</small></span>
                </div>
              </a-upload-dragger>
              <div v-if="logUploadProgress" style="margin-top:6px;text-align:center;color:#1677ff">{{ logUploadProgress }}</div>
              <a-button type="link" size="small" block @click="openLogSplit">
                <template #icon><ScissorOutlined /></template>大文件超 50MB？按时间切割
              </a-button>
            </a-form-item>
          </a-col>
          <a-col :xs="24" :md="12" :xl="6">
            <a-form-item label="错误截图">
              <a-upload-dragger
                class="evidence-upload screenshot-upload"
                multiple
                :max-count="3"
                accept="image/png,image/jpeg,image/jpg,image/webp"
                :file-list="screenshotFileList"
                :before-upload="beforeScreenshotUpload"
                @remove="removeScreenshot"
              >
                <div class="evidence-upload-content">
                  <InboxOutlined />
                  <span>拖入或选择错误截图<br><small>可选，最多 3 张</small></span>
                </div>
              </a-upload-dragger>
            </a-form-item>
          </a-col>
        </a-row>
        <div class="analysis-command-bar">
          <div class="analysis-mode-options">
            <span class="analysis-command-label">分析模式</span>
            <div class="analysis-mode-switches">
              <a-tooltip title="开启后先侦察候选根因，方向存疑时并行分链深挖（更准但更烧 token），方向明确仍走单链；关闭跟随系统配置">
                <span class="analysis-switch-item">
                  <a-switch v-model:checked="analysisForm.deepMode" size="small" />深度分析
                </span>
              </a-tooltip>
              <a-tooltip title="开启后主 Agent 先自行分析，遇到明确的源码或日志证据缺口时才按需委派子 Agent；与深度分析同时开启时优先按需多 Agent">
                <span class="analysis-switch-item">
                  <a-switch v-model:checked="analysisForm.multiAgentMode" size="small" />多 Agent 调查
                </span>
              </a-tooltip>
            </div>
          </div>
          <div class="analysis-submit-actions">
            <a-button type="primary" class="bug-analysis-button" :disabled="logUploading" :loading="logUploading" @click="agentAnalyzeAction"><template #icon><BarChartOutlined /></template>{{ logUploading ? '日志上传中' : 'Bug分析' }}</a-button>
            <a-tooltip title="只需要选择对应接口地址即可">
              <a-button class="api-explain-button" @click="apiAnalyzeAction"><template #icon><BarChartOutlined /></template>接口讲解</a-button>
            </a-tooltip>
          </div>
        </div>
      </a-form>
    </div>
  </section>
</template>

<script setup>
import { BarChartOutlined, InboxOutlined, ScissorOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const {
  currentProject,
  datasourceAccessPreview,
  environmentOptions,
  databasePolicyOptions,
  changeAnalysisEnvironment,
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
  logUploading,
  logUploadProgress,
  beforeLogUpload,
  removeLog,
  screenshotFileList,
  beforeScreenshotUpload,
  removeScreenshot,
  agentAnalyzeAction,
  apiAnalyzeAction,
  pasteFromClipboard,
  openLogSplit
} = useAppStore()
</script>
