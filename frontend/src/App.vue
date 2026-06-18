<template>
  <a-layout class="layout">
    <a-layout-sider :width="248" class="sidebar" theme="dark">
      <div class="brand">
        <BugOutlined />
        <span>Bug Agent</span>
      </div>
      <a-menu :selected-keys="[activePanel]" theme="dark" mode="inline" @click="({ key }) => (activePanel = key)">
        <a-menu-item key="projects"><template #icon><FolderOutlined /></template>项目管理</a-menu-item>
        <a-menu-item key="source"><template #icon><UploadOutlined /></template>源码导入</a-menu-item>
        <a-menu-item key="ai-settings"><template #icon><SettingOutlined /></template>AI 配置</a-menu-item>
        <a-menu-item key="dbhub-sources"><template #icon><ApiOutlined /></template>dbhub数据源</a-menu-item>
        <a-menu-item key="project-dbhub"><template #icon><SettingOutlined /></template>项目dbhub绑定</a-menu-item>
        <a-menu-item key="analysis"><template #icon><BarChartOutlined /></template>Bug 分析</a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout>
      <a-layout-header class="header">
        <div class="header-actions">
          <a-select
            v-model:value="selectedProjectId"
            :options="projectOptions"
            show-search
            option-filter-prop="label"
            allow-clear
            placeholder="选择项目"
            style="width: 240px"
            @change="changeProject"
          />
          <a-button @click="loadAll"><template #icon><ReloadOutlined /></template>刷新</a-button>
        </div>
      </a-layout-header>

      <a-layout-content class="main">
        <section v-show="activePanel === 'projects'" class="panel">
          <div class="panel-title">
            <h2>项目管理</h2>
            <a-button type="primary" @click="openProjectDialog()"><template #icon><PlusOutlined /></template>新增项目</a-button>
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
            <a-table-column title="ID" data-index="id" :width="80" />
            <a-table-column title="项目名称" data-index="name" />
            <a-table-column title="编码" data-index="code" />
            <a-table-column title="说明" data-index="description" />
            <a-table-column title="操作" :width="150">
              <template #default="{ record }">
                <a-button type="link" size="small" @click="openProjectDialog(record)">编辑</a-button>
                <a-button type="link" danger size="small" @click="deleteProjectAction(record)">删除</a-button>
              </template>
            </a-table-column>
          </a-table>
        </section>

        <section v-show="activePanel === 'source'" class="panel">
          <div class="panel-title">
            <h2>源码导入</h2>
            <a-tag v-if="currentProject" color="blue">当前：{{ currentProject.name }}</a-tag>
          </div>
          <a-alert v-if="!currentProject" type="warning" show-icon message="先在项目管理里选择一个项目" />
          <div v-else class="split">
            <a-card title="Git 拉取" :bordered="false">
              <a-form layout="vertical">
                <a-form-item label="仓库地址"><a-input v-model:value="gitForm.repoUrl" /></a-form-item>
                <a-form-item label="分支"><a-input v-model:value="gitForm.branchName" placeholder="可空" /></a-form-item>
                <a-form-item label="Token"><a-input-password v-model:value="gitForm.accessToken" placeholder="首版预留" /></a-form-item>
                <a-button type="primary" @click="importGitAction"><template #icon><DownloadOutlined /></template>导入 Git</a-button>
              </a-form>
            </a-card>
            <a-card title="ZIP 上传" :bordered="false">
              <a-upload-dragger :max-count="1" accept=".zip" :file-list="zipFileList" :before-upload="beforeZipUpload" @remove="removeZip">
                <p class="ant-upload-drag-icon"><InboxOutlined /></p>
                <p class="ant-upload-text">拖入 ZIP 或点击选择</p>
              </a-upload-dragger>
              <a-button type="primary" class="top-gap" @click="importZipAction"><template #icon><UploadOutlined /></template>上传并索引</a-button>
            </a-card>
          </div>
          <a-table class="top-gap" :data-source="versions" :pagination="false" row-key="id" size="middle">
            <a-table-column title="来源" data-index="sourceType" :width="100" />
            <a-table-column title="索引状态" data-index="indexStatus" :width="130" />
            <a-table-column title="更新时间" data-index="updatedAt" :width="200" />
            <a-table-column title="索引消息" data-index="indexMessage" :ellipsis="true" />
            <a-table-column title="操作" :width="90">
              <template #default="{ record }">
                <a-button type="link" danger size="small" @click="deleteVersionAction(record)">删除</a-button>
              </template>
            </a-table-column>
          </a-table>
        </section>

        <section v-show="activePanel === 'ai-settings'" class="panel">
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

        <section v-show="activePanel === 'dbhub-sources'" class="panel">
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

        <section v-show="activePanel === 'project-dbhub'" class="panel">
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

        <section v-show="activePanel === 'analysis'" class="panel">
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
                  </div>
                </a-col>
              </a-row>
            </a-form>
          </div>
        </section>
      </a-layout-content>
    </a-layout>

    <a-modal v-model:open="analysisDialogVisible" width="820px" :footer="null" centered destroy-on-close>
      <template #title>
        <div class="report-header">
          <span>分析报告 #{{ analysisResult?.id }}</span>
          <a-tag v-if="analysisResult" color="blue">{{ analysisResult.confidence }}</a-tag>
        </div>
      </template>
      <a-alert :message="analysisResult?.plainAnswer" type="success" show-icon />
      <a-tabs v-model:activeKey="activeReportTab" class="top-gap">
        <a-tab-pane key="report" tab="分析报告">
          <pre class="scroll-content">{{ analysisResult?.conclusion }}</pre>
        </a-tab-pane>
        <a-tab-pane key="evidence" tab="证据">
          <pre class="scroll-content">{{ analysisResult?.evidenceJson }}</pre>
        </a-tab-pane>
      </a-tabs>
      <div class="feedback-box">
        <div class="feedback-title">结论反馈（标注后沉淀为回归用例，喂回评估）</div>
        <a-radio-group v-model:value="feedbackForm.verdict">
          <a-radio value="CORRECT">结论正确</a-radio>
          <a-radio value="PARTIAL">部分正确</a-radio>
          <a-radio value="WRONG">结论错误</a-radio>
        </a-radio-group>
        <a-input v-model:value="feedbackForm.expectKeywords" class="top-gap" placeholder="正确结论必须命中的关键词，逗号分隔，如 nick_name,字段,不存在" />
        <a-textarea v-model:value="feedbackForm.actualRootCause" :rows="2" class="top-gap" placeholder="真实根因（结论错或部分对时填）" />
        <a-input v-model:value="feedbackForm.note" class="top-gap" placeholder="备注（可选）" />
        <div class="top-gap">
          <a-button type="primary" @click="submitFeedbackAction"><template #icon><CheckOutlined /></template>提交反馈</a-button>
        </div>
      </div>
    </a-modal>

    <a-modal v-model:open="projectDialogVisible" :title="projectForm.id ? '编辑项目' : '新增项目'" width="560px" centered destroy-on-close @ok="saveProjectAction">
      <a-form layout="vertical">
        <a-form-item label="项目名称"><a-input v-model:value="projectForm.name" /></a-form-item>
        <a-form-item label="项目编码"><a-input v-model:value="projectForm.code" /></a-form-item>
        <a-form-item label="说明"><a-textarea v-model:value="projectForm.description" :rows="3" /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="dbhubDialogVisible" title="dbhub 数据源" width="640px" centered destroy-on-close>
      <a-form layout="vertical">
        <a-form-item label="数据源Key"><a-input v-model:value="dbhubForm.key" placeholder="user_bug_demo" /></a-form-item>
        <a-form-item label="主机"><a-input v-model:value="dbhubForm.host" /></a-form-item>
        <a-form-item label="端口"><a-input-number v-model:value="dbhubForm.port" :min="1" :max="65535" style="width: 100%" /></a-form-item>
        <a-form-item label="库名"><a-input v-model:value="dbhubForm.database" /></a-form-item>
        <a-form-item label="用户名"><a-input v-model:value="dbhubForm.user" /></a-form-item>
        <a-form-item label="密码"><a-input-password v-model:value="dbhubForm.password" /></a-form-item>
      </a-form>
      <template #footer>
        <a-button @click="dbhubDialogVisible = false">取消</a-button>
        <a-button @click="testDbhubDatasourceAction"><template #icon><ApiOutlined /></template>测试连接</a-button>
        <a-button type="primary" @click="saveDbhubDatasourceAction"><template #icon><CheckOutlined /></template>保存</a-button>
      </template>
    </a-modal>

    <a-modal v-model:open="aiDialogVisible" title="新增 AI 配置" width="560px" centered destroy-on-close @ok="saveAiAction">
      <a-form layout="vertical">
        <a-form-item label="Provider"><a-input v-model:value="aiForm.provider" /></a-form-item>
        <a-form-item label="Base URL"><a-input v-model:value="aiForm.baseUrl" placeholder="http://host:port/v1" /></a-form-item>
        <a-form-item label="Model"><a-input v-model:value="aiForm.modelName" /></a-form-item>
        <a-form-item label="API Key"><a-input-password v-model:value="aiForm.apiKey" /></a-form-item>
        <a-form-item label="超时秒"><a-input-number v-model:value="aiForm.timeoutSeconds" :min="10" :max="300" style="width: 100%" /></a-form-item>
      </a-form>
    </a-modal>
  </a-layout>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ApiOutlined,
  BarChartOutlined,
  BugOutlined,
  CheckOutlined,
  DownloadOutlined,
  FolderOutlined,
  InboxOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
  UploadOutlined
} from '@ant-design/icons-vue'
import {
  analyzeBug,
  pollAgentAnalysisTask,
  createProject,
  deleteDbhubDatasource,
  deleteProject,
  listAiConfigs,
  createAiConfig,
  activateAiConfig,
  deleteAiConfig,
  importGit,
  importZip,
  listApiRoutes,
  listDbhubDatasources,
  listDatasources,
  listProjects,
  listVersions,
  deleteVersion,
  submitAgentAnalysisTaskScreenshots,
  submitAnalysisFeedback,
  uploadLog,
  saveDbhubDatasource,
  saveDatasource,
  testDbhubDatasource,
  testAiConfig,
  updateProject
} from './api/client'

const activePanel = ref('projects')
const projects = ref([])
const versions = ref([])
const datasources = ref([])
const currentProject = ref(null)
const selectedProjectId = ref(null)
const datasourceProjectId = ref(null)
const zipFile = ref(null)
const screenshotFiles = ref([])
const analysisResult = ref(null)
const analysisDialogVisible = ref(false)
const activeReportTab = ref('report')
const dbhubDatasources = ref([])
const selectedDbhubDatasources = ref([])
const selectedDbhubKeys = ref([])
const projectDialogVisible = ref(false)
const dbhubDialogVisible = ref(false)
const aiConfigs = ref([])
const selectedAiConfigId = ref(null)
const aiDialogVisible = ref(false)
const apiRoutes = ref([])
const routesLoading = ref(false)
const selectedApiPrefix = ref('')

const projectForm = reactive({ id: null, name: '', code: '', description: '' })
const projectQuery = reactive({ name: '', code: '' })
const gitForm = reactive({ repoUrl: '', branchName: '', accessToken: '' })
const aiForm = reactive({ provider: 'openai-compatible', baseUrl: '', modelName: '', apiKey: '', timeoutSeconds: 60, enabled: true })
const dbhubForm = reactive({ key: '', host: 'localhost', port: 3306, user: 'root', password: '1234', database: '' })
const dbhubQuery = reactive({ user: '', database: '' })
const datasourceForm = reactive({ env: 'test', dbhubKey: '' })
const analysisForm = reactive({ versionId: '', apiPath: '', userDescription: '', requestBody: '', responseBody: '', stackTrace: '', traceId: '', requestTime: '', logText: '' })
const logFileList = ref([])
const feedbackForm = reactive({ verdict: '', expectKeywords: '', actualRootCause: '', note: '' })
// 出新分析结果就清空上一次的反馈输入
watch(() => analysisResult.value?.id, () => {
  Object.assign(feedbackForm, { verdict: '', expectKeywords: '', actualRootCause: '', note: '' })
})

const currentVersion = computed(() => {
  if (!currentProject.value) return null
  if (analysisForm.versionId) {
    return versions.value.find((version) => version.id === Number(analysisForm.versionId)) || null
  }
  return versions.value[0] || null
})
const currentDatasource = computed(() => {
  if (!currentProject.value) return null
  return datasources.value.find((item) => item.enabled) || datasources.value[0] || null
})

const projectOptions = computed(() => projects.value.map((project) => ({ value: project.id, label: project.name })))
const versionOptions = computed(() => versions.value.map((version) => ({ value: version.id, label: versionOptionLabel(version) })))
const dbhubKeyOptions = computed(() => dbhubDatasources.value.map((item) => ({ value: item.key, label: item.key })))
const apiPrefixOptions = computed(() => apiPrefixes.value.map((prefix) => ({ value: prefix, label: prefix })))
const apiRouteOptions = computed(() => filteredApiRoutes.value.map((route) => ({ value: route.path, label: route.path })))

const apiPrefixes = computed(() => {
  const prefixes = new Set()
  apiRoutes.value.forEach((route) => {
    const prefix = routePrefix(route.path)
    if (prefix) {
      prefixes.add(prefix)
    }
  })
  return Array.from(prefixes).sort()
})

const filteredApiRoutes = computed(() => {
  if (!selectedApiPrefix.value) {
    return []
  }
  return apiRoutes.value.filter((route) => routePrefix(route.path) === selectedApiPrefix.value)
})
const filteredDbhubDatasources = computed(() => {
  const user = dbhubQuery.user.trim().toLowerCase()
  const database = dbhubQuery.database.trim().toLowerCase()
  return dbhubDatasources.value.filter((item) => {
    const matchedUser = !user || String(item.user || '').toLowerCase().includes(user)
    const matchedDatabase = !database || String(item.database || '').toLowerCase().includes(database)
    return matchedUser && matchedDatabase
  })
})

const zipFileList = computed(() => (zipFile.value ? [{ uid: 'zip', name: zipFile.value.name }] : []))
const screenshotFileList = computed(() => screenshotFiles.value.map((file, index) => ({ uid: String(index), name: file.name })))

const dbhubRowSelection = computed(() => ({
  selectedRowKeys: selectedDbhubKeys.value,
  onChange: (keys, rows) => {
    selectedDbhubKeys.value = keys
    selectedDbhubDatasources.value = rows
  }
}))

function projectRowEvents(record) {
  return {
    onClick: () => selectProject(record),
    onDblclick: () => openProjectDialog(record)
  }
}

function projectRowClass(record) {
  return currentProject.value?.id === record.id ? 'active-row' : ''
}

function dbhubRowEvents(record) {
  return { onDblclick: () => openDbhubDialog(record) }
}

// Element 的 ElMessageBox.confirm 等价封装，取消时 reject，保持原有 await 流程
function confirm(content, title = '确认') {
  return new Promise((resolve, reject) => {
    Modal.confirm({ title, content, okText: '确定', cancelText: '取消', onOk: () => resolve(true), onCancel: () => reject(new Error('cancelled')) })
  })
}

async function loadAll() {
  await loadProjects()
  dbhubDatasources.value = await listDbhubDatasources().catch(() => [])
  await loadProjectRelated()
  await loadAiConfigs()
}

async function loadAiConfigs() {
  aiConfigs.value = await listAiConfigs().catch(() => [])
  const active = aiConfigs.value.find((item) => item.enabled)
  selectedAiConfigId.value = active ? active.id : (aiConfigs.value[0]?.id ?? null)
  // 有配置但没启用项时，默认激活第一条，保证分析有可用 AI
  if (!active && selectedAiConfigId.value) {
    await activateAiConfig(selectedAiConfigId.value).catch(() => {})
  }
}

async function loadProjects() {
  projects.value = await listProjects(projectQuery)
  if (!currentProject.value && projects.value.length) {
    currentProject.value = projects.value[0]
    selectedProjectId.value = currentProject.value.id
  }
  if (!datasourceProjectId.value && currentProject.value) {
    datasourceProjectId.value = currentProject.value.id
  }
  if (currentProject.value) {
    const matched = projects.value.find((project) => project.id === currentProject.value.id)
    currentProject.value = matched || projects.value[0] || null
    selectedProjectId.value = currentProject.value?.id || null
  }
  if (!currentProject.value) {
    selectedProjectId.value = null
    datasourceProjectId.value = null
    versions.value = []
    datasources.value = []
  }
}

async function loadProjectRelated() {
  if (!currentProject.value) return
  versions.value = await listVersions(currentProject.value.id)
  datasources.value = await listDatasources(currentProject.value.id)
  if (!analysisForm.versionId && versions.value.length) {
    analysisForm.versionId = versions.value[0].id
  }
  await loadApiRoutes()
}

async function saveProjectAction() {
  if (!projectForm.name || !projectForm.code) {
    message.warning('项目名称和项目编码不能为空')
    return
  }
  const saved = projectForm.id ? await updateProject(projectForm.id, projectForm) : await createProject(projectForm)
  message.success(projectForm.id ? '项目已修改' : '项目已创建')
  projectDialogVisible.value = false
  resetProjectForm()
  currentProject.value = saved
  selectedProjectId.value = saved.id
  datasourceProjectId.value = saved.id
  await loadAll()
}

function openProjectDialog(row) {
  if (row) {
    Object.assign(projectForm, { id: row.id, name: row.name, code: row.code, description: row.description || '' })
  } else {
    resetProjectForm()
  }
  projectDialogVisible.value = true
}

function resetProjectForm() {
  Object.assign(projectForm, { id: null, name: '', code: '', description: '' })
}

async function deleteProjectAction(row) {
  await confirm(`确认删除项目 ${row.name} 吗？项目版本、数据源绑定、代码索引和分析记录会一起删除。`, '删除项目')
  await deleteProject(row.id)
  message.success('项目已删除')
  if (selectedProjectId.value === row.id) {
    selectedProjectId.value = null
  }
  if (datasourceProjectId.value === row.id) {
    datasourceProjectId.value = null
  }
  if (currentProject.value?.id === row.id) {
    currentProject.value = null
    analysisForm.versionId = ''
    analysisForm.apiPath = ''
    selectedApiPrefix.value = ''
    apiRoutes.value = []
  }
  await loadAll()
}

async function resetProjectQuery() {
  Object.assign(projectQuery, { name: '', code: '' })
  await loadAll()
}

async function selectProject(row) {
  currentProject.value = row
  selectedProjectId.value = row?.id || null
  datasourceProjectId.value = row?.id || null
  await loadProjectRelated()
}

async function changeProject(projectId) {
  currentProject.value = projects.value.find((project) => project.id === projectId) || null
  datasourceProjectId.value = currentProject.value?.id || null
  analysisResult.value = null
  analysisForm.apiPath = ''
  selectedApiPrefix.value = ''
  analysisForm.versionId = ''
  apiRoutes.value = []
  await loadProjectRelated()
}

async function changeDatasourceProject(projectId) {
  const project = projects.value.find((item) => item.id === projectId) || null
  if (project) {
    datasourceProjectId.value = project.id
    currentProject.value = project
    selectedProjectId.value = project.id
    await loadProjectRelated()
  }
}

async function changeAnalysisVersion(versionId) {
  analysisForm.versionId = versionId || ''
  analysisForm.apiPath = ''
  selectedApiPrefix.value = ''
  await loadApiRoutes()
}

async function loadApiRoutes(keyword = '') {
  keyword = typeof keyword === 'string' ? keyword : ''
  if (!currentProject.value) return
  const versionId = analysisForm.versionId ? Number(analysisForm.versionId) : currentVersion.value?.id
  if (!versionId) return
  routesLoading.value = true
  try {
    apiRoutes.value = await listApiRoutes(currentProject.value.id, versionId, keyword).catch(() => [])
  } finally {
    routesLoading.value = false
  }
}

function changeApiPrefix(prefix) {
  selectedApiPrefix.value = prefix || ''
  analysisForm.apiPath = ''
}

function versionOptionLabel(version) {
  return `${version.id} / ${version.sourceType} / ${version.indexStatus}`
}

function changeApiPath(value) {
  analysisForm.apiPath = value || ''
  selectedApiPrefix.value = value ? routePrefix(value) : selectedApiPrefix.value
}

function routePrefix(path) {
  if (!path) return ''
  const parts = String(path).split('/').filter(Boolean)
  return parts.length ? `/${parts[0]}` : ''
}

async function importGitAction() {
  await importGit(currentProject.value.id, gitForm)
  message.success('Git 导入任务已提交')
  await loadProjectRelated()
}

function beforeZipUpload(file) {
  zipFile.value = file
  return false
}

function removeZip() {
  zipFile.value = null
}

function beforeScreenshotUpload(file) {
  screenshotFiles.value = [...screenshotFiles.value, file]
  return false
}

function removeScreenshot(file) {
  const index = Number(file.uid)
  screenshotFiles.value = screenshotFiles.value.filter((_, idx) => idx !== index)
}

async function beforeLogUpload(file) {
  if (file.size > 10 * 1024 * 1024) {
    message.warning('日志文件不能超过 10MB')
    return false
  }
  logFileList.value = [file]
  try {
    analysisForm.logText = await uploadLog(file)
    message.success('日志已解析并填入')
  } catch (error) {
    message.error(error.message || '日志上传失败')
    logFileList.value = []
  }
  return false
}

function removeLog() {
  logFileList.value = []
}

async function deleteVersionAction(row) {
  await confirm('确认删除这个版本吗？对应的代码索引和磁盘源码会一起删除。', '删除版本')
  await deleteVersion(currentProject.value.id, row.id)
  message.success('版本已删除')
  await loadProjectRelated()
}

async function importZipAction() {
  if (!zipFile.value) {
    message.warning('请选择 ZIP 文件')
    return
  }
  await importZip(currentProject.value.id, zipFile.value)
  message.success('ZIP 导入任务已提交')
  await loadProjectRelated()
}

function openAiDialog() {
  Object.assign(aiForm, { provider: 'openai-compatible', baseUrl: '', modelName: '', apiKey: '', timeoutSeconds: 60, enabled: true })
  aiDialogVisible.value = true
}

async function saveAiAction() {
  if (!aiForm.baseUrl || !aiForm.modelName || !aiForm.apiKey) {
    message.warning('Base URL、Model、API Key 不能为空')
    return
  }
  await createAiConfig(aiForm)
  message.success('AI 配置已新增')
  aiDialogVisible.value = false
  await loadAiConfigs()
}

async function activateAiAction(id) {
  await activateAiConfig(id)
  selectedAiConfigId.value = id
  message.success('已切换 Agent 分析使用的 AI')
  await loadAiConfigs()
}

async function deleteAiAction(row) {
  await confirm(`确认删除配置 ${row.provider} / ${row.modelName} 吗？`, '删除 AI 配置')
  await deleteAiConfig(row.id)
  message.success('AI 配置已删除')
  await loadAiConfigs()
}

async function testAiAction() {
  const result = await testAiConfig()
  message.info(result)
}

async function saveDatasourceAction() {
  if (!datasourceProjectId.value) {
    message.warning('先选择要配置 dbhub 的项目')
    return
  }
  await saveDatasource(datasourceProjectId.value, datasourceForm)
  message.success('数据源已保存')
  await loadProjectRelated()
}

async function saveDbhubDatasourceAction() {
  if (!dbhubForm.key || !dbhubForm.database) {
    message.warning('数据源 Key 和库名不能为空')
    return
  }
  await saveDbhubDatasource(dbhubForm)
  message.success('dbhub 数据源已保存')
  dbhubDatasources.value = await listDbhubDatasources()
  dbhubDialogVisible.value = false
}

async function testDbhubDatasourceAction() {
  if (!dbhubForm.key || !dbhubForm.database) {
    message.warning('先填写完整数据源信息')
    return
  }
  const result = await testDbhubDatasource(dbhubForm)
  message.success(result)
}

function openDbhubDialog(row) {
  if (row) {
    fillDbhubForm(row)
  } else {
    resetDbhubForm()
  }
  dbhubDialogVisible.value = true
}

function fillDbhubForm(row) {
  Object.assign(dbhubForm, { key: row.key, host: row.host, port: row.port, user: row.user, password: '', database: row.database })
}

function resetDbhubForm() {
  Object.assign(dbhubForm, { key: '', host: 'localhost', port: 3306, user: 'root', password: '1234', database: '' })
}

function resetDbhubQuery() {
  Object.assign(dbhubQuery, { user: '', database: '' })
}

async function deleteSelectedDbhubDatasources() {
  if (!selectedDbhubDatasources.value.length) {
    message.warning('请先勾选要删除的数据源')
    return
  }
  const keys = selectedDbhubDatasources.value.map((item) => item.key)
  await confirm(`确认删除 ${keys.join('、')} 吗？`, '删除数据源')
  await Promise.all(keys.map((key) => deleteDbhubDatasource(key)))
  message.success('数据源已删除')
  selectedDbhubDatasources.value = []
  selectedDbhubKeys.value = []
  dbhubDatasources.value = await listDbhubDatasources()
}

async function analyzeAction() {
  const close = message.loading('正在分析中...', 0)
  try {
    const payload = { ...analysisForm, projectId: currentProject.value.id }
    payload.versionId = payload.versionId ? Number(payload.versionId) : null
    analysisResult.value = await analyzeBug(payload)
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('分析完成')
  } finally {
    close()
  }
}

async function agentAnalyzeAction() {
  const close = message.loading('Agent任务已提交，正在分析...', 0)
  try {
    const payload = { ...analysisForm, projectId: currentProject.value.id }
    payload.versionId = payload.versionId ? Number(payload.versionId) : null
    const task = await submitAgentAnalysisTaskScreenshots(payload, screenshotFiles.value)
    analysisResult.value = await waitAgentTask(task.taskId)
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    message.success('Agent分析完成！')
  } finally {
    close()
  }
}

async function waitAgentTask(taskId) {
  for (let index = 0; index < 90; index++) {
    const task = await pollAgentAnalysisTask(taskId)
    if (task.status === 'SUCCESS') {
      return task.result
    }
    if (task.status === 'FAILED' || task.status === 'NOT_FOUND') {
      throw new Error(task.message || 'Agent分析失败')
    }
    await sleep(2000)
  }
  throw new Error('Agent分析超时，请稍后查看任务状态')
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function submitFeedbackAction() {
  if (!analysisResult.value?.id) {
    message.warning('没有可反馈的分析记录')
    return
  }
  if (!feedbackForm.verdict) {
    message.warning('请先选择结论是否正确')
    return
  }
  const expectKeywords = feedbackForm.expectKeywords
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
  await submitAnalysisFeedback(analysisResult.value.id, {
    verdict: feedbackForm.verdict,
    actualRootCause: feedbackForm.actualRootCause,
    expectKeywords,
    note: feedbackForm.note
  })
  message.success('反馈已记录，已沉淀为回归用例')
}

onMounted(loadAll)
</script>

<style scoped>
.active-row > td {
  background: #e6f4ff;
}
.left-gap {
  margin-left: 8px;
}
/* 分析台收紧：表单项间距小一点、上传框矮一点，整页不出滚动条 */
.analysis-form :deep(.ant-form-item) {
  margin-bottom: 12px;
}
.analysis-form :deep(.ant-upload-drag) {
  padding: 6px;
}
.analysis-form :deep(.ant-upload-drag-icon) {
  margin: 0 0 2px;
  font-size: 22px;
}
.analysis-form :deep(.ant-upload-text) {
  font-size: 12px;
  margin: 0;
}
.analysis-actions {
  padding-top: 0;
}
.actions-col {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
</style>
