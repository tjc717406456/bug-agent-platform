<template>
  <el-container class="layout">
    <el-aside width="248px" class="sidebar">
      <div class="brand">
        <el-icon><Cpu /></el-icon>
        <span>Bug Agent</span>
      </div>
      <el-menu :default-active="activePanel" class="menu" @select="activePanel = $event">
        <el-menu-item index="projects">
          <el-icon><Folder /></el-icon>
          <span>项目管理</span>
        </el-menu-item>
        <el-menu-item index="source">
          <el-icon><UploadFilled /></el-icon>
          <span>源码导入</span>
        </el-menu-item>
        <el-menu-item index="ai-settings">
          <el-icon><Setting /></el-icon>
          <span>AI 配置</span>
        </el-menu-item>
        <el-menu-item index="dbhub-sources">
          <el-icon><Connection /></el-icon>
          <span>dbhub数据源</span>
        </el-menu-item>
        <el-menu-item index="project-dbhub">
          <el-icon><Setting /></el-icon>
          <span>项目dbhub绑定</span>
        </el-menu-item>
        <el-menu-item index="analysis">
          <el-icon><DataAnalysis /></el-icon>
          <span>Bug 分析</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div>
          <h1>多项目后端 Bug 分析 Agent</h1>
          <p>CodeGraph + dbhub + AI，按证据输出诊断报告。</p>
        </div>
        <div class="header-actions">
          <el-select v-model="selectedProjectId" placeholder="选择项目" filterable @change="changeProject">
            <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
          </el-select>
          <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <section v-show="activePanel === 'projects'" class="panel">
          <div class="panel-title">
            <h2>项目管理</h2>
            <el-button type="primary" :icon="Plus" @click="createProjectAction">新建项目</el-button>
          </div>
          <el-form :model="projectForm" label-width="90px" class="compact-form">
            <el-row :gutter="16">
              <el-col :span="6"><el-form-item label="项目名"><el-input v-model="projectForm.name" /></el-form-item></el-col>
              <el-col :span="6"><el-form-item label="项目编码"><el-input v-model="projectForm.code" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="说明"><el-input v-model="projectForm.description" /></el-form-item></el-col>
            </el-row>
          </el-form>
          <el-table :data="projects" highlight-current-row @current-change="selectProject">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="name" label="项目名" />
            <el-table-column prop="code" label="编码" />
            <el-table-column prop="description" label="说明" />
          </el-table>
        </section>

        <section v-show="activePanel === 'source'" class="panel">
          <div class="panel-title">
            <h2>源码导入</h2>
            <el-tag v-if="currentProject">当前：{{ currentProject.name }}</el-tag>
          </div>
          <el-alert v-if="!currentProject" type="warning" show-icon title="先在项目管理里选择一个项目" />
          <div v-else class="split">
            <el-card shadow="never">
              <template #header>Git 拉取</template>
              <el-form :model="gitForm" label-width="90px">
                <el-form-item label="仓库地址"><el-input v-model="gitForm.repoUrl" /></el-form-item>
                <el-form-item label="分支"><el-input v-model="gitForm.branchName" placeholder="可空" /></el-form-item>
                <el-form-item label="Token"><el-input v-model="gitForm.accessToken" type="password" show-password placeholder="首版预留" /></el-form-item>
                <el-button type="primary" :icon="Download" @click="importGitAction">导入 Git</el-button>
              </el-form>
            </el-card>
            <el-card shadow="never">
              <template #header>ZIP 上传</template>
              <el-upload drag :auto-upload="false" :limit="1" accept=".zip" :on-change="onZipSelected">
                <el-icon class="upload-icon"><UploadFilled /></el-icon>
                <div>拖入 ZIP 或点击选择</div>
              </el-upload>
              <el-button type="primary" :icon="Upload" class="top-gap" @click="importZipAction">上传并索引</el-button>
            </el-card>
          </div>
          <el-table :data="versions" class="top-gap">
            <el-table-column prop="id" label="版本ID" width="90" />
            <el-table-column prop="sourceType" label="来源" width="100" />
            <el-table-column prop="branchName" label="分支" width="140" />
            <el-table-column prop="indexStatus" label="索引状态" width="130" />
            <el-table-column prop="indexMessage" label="索引消息" />
            <el-table-column prop="sourcePath" label="源码路径" />
          </el-table>
        </section>

        <section v-show="activePanel === 'ai-settings'" class="panel">
          <div class="panel-title"><h2>AI 配置</h2></div>
          <el-form :model="aiForm" label-width="100px" class="compact-form">
            <el-form-item label="Provider"><el-input v-model="aiForm.provider" /></el-form-item>
            <el-form-item label="Base URL"><el-input v-model="aiForm.baseUrl" /></el-form-item>
            <el-form-item label="Model"><el-input v-model="aiForm.modelName" /></el-form-item>
            <el-form-item label="API Key"><el-input v-model="aiForm.apiKey" type="password" show-password /></el-form-item>
            <el-form-item label="超时秒"><el-input-number v-model="aiForm.timeoutSeconds" :min="10" :max="300" /></el-form-item>
            <el-button type="primary" :icon="Check" @click="saveAiAction">保存 AI</el-button>
            <el-button :icon="Connection" @click="testAiAction">测试连接</el-button>
          </el-form>
        </section>

        <section v-show="activePanel === 'dbhub-sources'" class="panel">
          <div class="panel-title">
            <h2>dbhub 数据源管理</h2>
            <div>
              <el-button type="danger" :disabled="!selectedDbhubDatasources.length" @click="deleteSelectedDbhubDatasources">删除选中</el-button>
              <el-button type="primary" :icon="Plus" @click="openDbhubDialog()">新增数据源</el-button>
            </div>
          </div>
          <el-form :model="dbhubQuery" label-width="90px" class="compact-form">
            <el-row :gutter="16">
              <el-col :span="8"><el-form-item label="用户名"><el-input v-model="dbhubQuery.user" clearable /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="库名"><el-input v-model="dbhubQuery.database" clearable /></el-form-item></el-col>
              <el-col :span="8"><el-button :icon="Refresh" @click="resetDbhubQuery">重置</el-button></el-col>
            </el-row>
          </el-form>
          <el-table :data="filteredDbhubDatasources" class="top-gap" @selection-change="changeDbhubSelection" @row-dblclick="openDbhubDialog">
            <el-table-column type="selection" width="48" />
            <el-table-column prop="key" label="Key" width="140" />
            <el-table-column prop="host" label="主机" width="160" />
            <el-table-column prop="port" label="端口" width="80" />
            <el-table-column prop="database" label="库名" />
            <el-table-column prop="user" label="用户" width="140" />
            <el-table-column label="操作" width="100">
              <template #default="scope">
                <el-button link type="primary" @click="openDbhubDialog(scope.row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section v-show="activePanel === 'project-dbhub'" class="panel">
          <div class="panel-title"><h2>项目 dbhub 绑定</h2></div>
          <el-form :model="datasourceForm" label-width="110px" class="compact-form">
            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="指定项目">
                  <el-select v-model="datasourceProjectId" placeholder="选择项目" filterable style="width: 100%" @change="changeDatasourceProject">
                    <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8"><el-form-item label="环境"><el-input v-model="datasourceForm.env" /></el-form-item></el-col>
              <el-col :span="8">
                <el-form-item label="dbhub Key">
                  <el-select v-model="datasourceForm.dbhubKey" placeholder="选择数据库" style="width: 100%">
                    <el-option v-for="datasource in dbhubDatasources" :key="datasource.key" :label="datasource.key" :value="datasource.key" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <el-button type="primary" :icon="Check" @click="saveDatasourceAction">保存绑定</el-button>
          </el-form>
          <el-table :data="datasources" class="top-gap">
            <el-table-column prop="env" label="环境" width="100" />
            <el-table-column prop="dbhubKey" label="dbhub Key" />
          </el-table>
        </section>

        <section v-show="activePanel === 'analysis'" class="panel">
          <div class="panel-title analysis-header">
            <div>
              <h2>Bug 分析工作台</h2>
              <p>先选项目和版本，再选接口地址，减少手填和版本选错。</p>
            </div>
            <div class="analysis-status">
              <el-tag type="info">{{ currentProject?.name || '未选项目' }}</el-tag>
              <el-tag v-if="currentVersion" type="success">版本 {{ currentVersion.id }}</el-tag>
              <el-tag v-if="currentDatasource" type="warning">{{ currentDatasource.dbhubKey }}</el-tag>
            </div>
          </div>
          <el-alert v-if="!currentProject" type="warning" show-icon title="先选择项目并完成源码索引" />
          <el-row v-else :gutter="16" class="analysis-grid">
            <el-col :span="15">
              <div class="analysis-section">
                <div class="section-title">
                  <h3>问题输入</h3>
                  <span>接口、版本、证据</span>
                </div>
                <el-form :model="analysisForm" label-width="110px" class="analysis-form">
                  <el-row :gutter="16">
                    <el-col :span="12">
                      <el-form-item label="项目">
                        <el-select v-model="selectedProjectId" placeholder="选择项目" filterable @change="changeProject">
                          <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="12">
                      <el-form-item label="版本">
                        <el-select v-model="analysisForm.versionId" placeholder="最新成功版本" filterable clearable @change="changeAnalysisVersion">
                          <el-option v-for="version in versions" :key="version.id" :label="versionOptionLabel(version)" :value="version.id" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-form-item label="接口地址">
                    <div class="api-path-picker">
                      <el-select
                        v-model="selectedApiPrefix"
                        placeholder="选择前缀"
                        filterable
                        clearable
                        :loading="routesLoading"
                        @focus="loadApiRoutes()"
                        @change="changeApiPrefix"
                      >
                        <el-option v-for="prefix in apiPrefixes" :key="prefix" :label="prefix" :value="prefix" />
                      </el-select>
                      <el-select
                        v-model="analysisForm.apiPath"
                        placeholder="选择接口"
                        filterable
                        clearable
                        :disabled="!selectedApiPrefix"
                        @change="changeApiPath"
                      >
                        <el-option v-for="route in filteredApiRoutes" :key="route.id" :label="route.path" :value="route.path" />
                      </el-select>
                    </div>
                  </el-form-item>
                  <el-form-item label="问题描述"><el-input v-model="analysisForm.userDescription" type="textarea" :rows="3" placeholder="可选，例如：列表只查出3条，实际数据库有5条，怀疑筛选条件或字段类型不对" /></el-form-item>
                  <el-form-item label="请求参数"><el-input v-model="analysisForm.requestBody" type="textarea" :rows="4" /></el-form-item>
                  <el-form-item label="响应结果"><el-input v-model="analysisForm.responseBody" type="textarea" :rows="4" /></el-form-item>
                  <el-form-item label="异常堆栈"><el-input v-model="analysisForm.stackTrace" type="textarea" :rows="4" placeholder="可选" /></el-form-item>
                  <el-form-item label="错误截图">
                    <el-upload drag multiple :auto-upload="false" :limit="3" accept="image/png,image/jpeg,image/jpg,image/webp" :on-change="onScreenshotSelected" :on-remove="onScreenshotRemoved">
                      <el-icon class="upload-icon"><UploadFilled /></el-icon>
                      <div>可选，拖入截图或点击选择，最多3张</div>
                    </el-upload>
                  </el-form-item>
                  <el-row :gutter="16">
                    <el-col :span="12"><el-form-item label="Trace ID"><el-input v-model="analysisForm.traceId" placeholder="可选" /></el-form-item></el-col>
                    <el-col :span="12"><el-form-item label="请求时间"><el-input v-model="analysisForm.requestTime" placeholder="可选" /></el-form-item></el-col>
                  </el-row>
                  <div class="analysis-actions">
                    <el-button type="primary" :icon="DataAnalysis" @click="analyzeAction">开始分析</el-button>
                    <el-button type="success" :icon="DataAnalysis" @click="agentAnalyzeAction">Agent分析</el-button>
                  </div>
                </el-form>
              </div>
            </el-col>
            <el-col :span="9">
              <div class="analysis-section evidence-panel">
                <div class="section-title">
                  <h3>证据概览</h3>
                  <span>当前版本的链路摘要</span>
                </div>
                <el-descriptions :column="1" size="small" border>
                  <el-descriptions-item label="接口">
                    <span class="mono">{{ analysisForm.apiPath || '未选择' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="版本">
                    <span>{{ currentVersionLabel }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="数据源">
                    <span>{{ currentDatasource?.dbhubKey || '未绑定' }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="路由状态">
                    <el-tag :type="analysisForm.apiPath ? 'success' : 'info'">{{ analysisForm.apiPath ? '已填写' : '未填写' }}</el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="截图">
                    <span>{{ screenshotFiles.length }} 张</span>
                  </el-descriptions-item>
                </el-descriptions>
                <div class="evidence-block">
                  <div class="evidence-title">最近版本接口</div>
                  <div class="route-chip-list">
                    <el-tag v-for="route in apiRoutes.slice(0, 8)" :key="route.id" effect="plain" class="route-chip" @click="selectApiRoute(route.path)">{{ route.path }}</el-tag>
                  </div>
                </div>
                <div class="evidence-block">
                  <div class="evidence-title">操作提示</div>
                  <p class="hint-text">先选版本，再搜接口。接口没命中时，先看版本是否选对，不要直接怪 AI。</p>
                </div>
              </div>
            </el-col>
          </el-row>
        </section>
      </el-main>
    </el-container>

    <el-dialog v-model="analysisDialogVisible" width="72%" class="analysis-dialog" destroy-on-close>
      <template #header>
        <div class="report-header">
          <span>分析报告 #{{ analysisResult?.id }}</span>
          <el-tag v-if="analysisResult">{{ analysisResult.confidence }}</el-tag>
        </div>
      </template>
      <div class="analysis-result-summary">
        <el-alert :title="analysisResult?.plainAnswer" type="success" show-icon :closable="false" />
      </div>
      <el-tabs v-model="activeReportTab">
        <el-tab-pane label="分析报告" name="report">
          <pre class="scroll-content">{{ analysisResult?.conclusion }}</pre>
        </el-tab-pane>
        <el-tab-pane label="证据" name="evidence">
          <pre class="scroll-content">{{ analysisResult?.evidenceJson }}</pre>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <el-dialog v-model="dbhubDialogVisible" title="dbhub 数据源" width="640px" destroy-on-close>
      <el-form :model="dbhubForm" label-width="100px">
        <el-form-item label="数据源Key"><el-input v-model="dbhubForm.key" placeholder="user_bug_demo" /></el-form-item>
        <el-form-item label="主机"><el-input v-model="dbhubForm.host" /></el-form-item>
        <el-form-item label="端口"><el-input-number v-model="dbhubForm.port" :min="1" :max="65535" style="width: 100%" /></el-form-item>
        <el-form-item label="库名"><el-input v-model="dbhubForm.database" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="dbhubForm.user" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="dbhubForm.password" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dbhubDialogVisible = false">取消</el-button>
        <el-button :icon="Connection" @click="testDbhubDatasourceAction">测试连接</el-button>
        <el-button type="primary" :icon="Check" @click="saveDbhubDatasourceAction">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Connection, Cpu, DataAnalysis, Download, Folder, Plus, Refresh, Setting, Upload, UploadFilled } from '@element-plus/icons-vue'
import {
  analyzeBug,
  pollAgentAnalysisTask,
  createProject,
  deleteDbhubDatasource,
  getAiConfig,
  importGit,
  importZip,
  listApiRoutes,
  listDbhubDatasources,
  listDatasources,
  listProjects,
  listVersions,
  saveAiConfig,
  submitAgentAnalysisTaskScreenshots,
  saveDbhubDatasource,
  saveDatasource,
  testDbhubDatasource,
  testAiConfig
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
const dbhubDialogVisible = ref(false)
const apiRoutes = ref([])
const routesLoading = ref(false)
const selectedApiPrefix = ref("")

const projectForm = reactive({ name: '', code: '', description: '' })
const gitForm = reactive({ repoUrl: '', branchName: '', accessToken: '' })
const aiForm = reactive({ provider: 'openai-compatible', baseUrl: '', modelName: '', apiKey: '', timeoutSeconds: 60, enabled: true })
const dbhubForm = reactive({ key: '', host: 'localhost', port: 3306, user: 'root', password: '1234', database: '' })
const dbhubQuery = reactive({ user: '', database: '' })
const datasourceForm = reactive({ env: 'test', dbhubKey: '' })
const analysisForm = reactive({ versionId: '', apiPath: '', userDescription: '', requestBody: '', responseBody: '', stackTrace: '', traceId: '', requestTime: '' })
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
const currentVersionLabel = computed(() => {
  if (!currentVersion.value) return '未选择'
  return `${currentVersion.value.id} / ${currentVersion.value.sourceType} / ${currentVersion.value.indexStatus}`
})

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

async function loadAll() {
  projects.value = await listProjects()
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
  dbhubDatasources.value = await listDbhubDatasources().catch(() => [])
  await loadProjectRelated()
  const config = await getAiConfig().catch(() => null)
  if (config) {
    Object.assign(aiForm, { ...config, apiKey: '' })
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

async function createProjectAction() {
  await createProject(projectForm)
  ElMessage.success('项目已创建')
  Object.assign(projectForm, { name: '', code: '', description: '' })
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

function selectApiRoute(path) {
  selectedApiPrefix.value = routePrefix(path)
  analysisForm.apiPath = path
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
  ElMessage.success('Git 导入任务已提交')
  await loadProjectRelated()
}

function onZipSelected(uploadFile) {
  zipFile.value = uploadFile.raw
}

function onScreenshotSelected(uploadFile, uploadFiles) {
  screenshotFiles.value = uploadFiles.map((item) => item.raw).filter(Boolean)
}

function onScreenshotRemoved(uploadFile, uploadFiles) {
  screenshotFiles.value = uploadFiles.map((item) => item.raw).filter(Boolean)
}

async function importZipAction() {
  if (!zipFile.value) {
    ElMessage.warning('请选择 ZIP 文件')
    return
  }
  await importZip(currentProject.value.id, zipFile.value)
  ElMessage.success('ZIP 导入任务已提交')
  await loadProjectRelated()
}

async function saveAiAction() {
  await saveAiConfig(aiForm)
  ElMessage.success('AI 配置已保存')
}

async function testAiAction() {
  const result = await testAiConfig()
  ElMessage.info(result)
}

async function saveDatasourceAction() {
  if (!datasourceProjectId.value) {
    ElMessage.warning('先选择要配置 dbhub 的项目')
    return
  }
  await saveDatasource(datasourceProjectId.value, datasourceForm)
  ElMessage.success('数据源已保存')
  await loadProjectRelated()
}

async function saveDbhubDatasourceAction() {
  if (!dbhubForm.key || !dbhubForm.database) {
    ElMessage.warning('数据源 Key 和库名不能为空')
    return
  }
  await saveDbhubDatasource(dbhubForm)
  ElMessage.success('dbhub 数据源已保存')
  dbhubDatasources.value = await listDbhubDatasources()
  dbhubDialogVisible.value = false
}

async function testDbhubDatasourceAction() {
  if (!dbhubForm.key || !dbhubForm.database) {
    ElMessage.warning('先填写完整数据源信息')
    return
  }
  const result = await testDbhubDatasource(dbhubForm)
  ElMessage.success(result)
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
  Object.assign(dbhubForm, {
    key: row.key,
    host: row.host,
    port: row.port,
    user: row.user,
    password: '',
    database: row.database
  })
}

function resetDbhubForm() {
  Object.assign(dbhubForm, { key: '', host: 'localhost', port: 3306, user: 'root', password: '1234', database: '' })
}

function resetDbhubQuery() {
  Object.assign(dbhubQuery, { user: '', database: '' })
}

function changeDbhubSelection(rows) {
  selectedDbhubDatasources.value = rows
}

async function deleteSelectedDbhubDatasources() {
  if (!selectedDbhubDatasources.value.length) {
    ElMessage.warning('请先勾选要删除的数据源')
    return
  }
  const keys = selectedDbhubDatasources.value.map((item) => item.key)
  await ElMessageBox.confirm(`确认删除 ${keys.join('、')} 吗？`, '删除数据源', { type: 'warning' })
  await Promise.all(keys.map((key) => deleteDbhubDatasource(key)))
  ElMessage.success('数据源已删除')
  selectedDbhubDatasources.value = []
  dbhubDatasources.value = await listDbhubDatasources()
}

async function analyzeAction() {
  const loading = ElMessage({
    message: '正在分析中...',
    type: 'info',
    duration: 0,
    icon: 'el-icon-loading'
  })
  try {
    const payload = { ...analysisForm, projectId: currentProject.value.id }
    payload.versionId = payload.versionId ? Number(payload.versionId) : null
    analysisResult.value = await analyzeBug(payload)
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    ElMessage.success('分析完成')
  } finally {
    loading.close()
  }
}

async function agentAnalyzeAction() {
  const loading = ElMessage({
    message: 'Agent任务已提交，正在分析...',
    type: 'warning',
    duration: 0,
    icon: 'el-icon-loading'
  })
  try {
    const payload = { ...analysisForm, projectId: currentProject.value.id }
    payload.versionId = payload.versionId ? Number(payload.versionId) : null
    const task = await submitAgentAnalysisTaskScreenshots(payload, screenshotFiles.value)
    analysisResult.value = await waitAgentTask(task.taskId)
    activeReportTab.value = 'report'
    analysisDialogVisible.value = true
    ElMessage.success('Agent分析完成！')
  } finally {
    loading.close()
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

onMounted(loadAll)
</script>




