import { ref, reactive, computed } from 'vue'
import { message } from 'ant-design-vue'
import {
  listProjects, listVersions, listDatasources, createProject, updateProject, deleteProject,
  listApiRoutes, importGit, importZip, deleteVersion, saveDatasource,
  getProjectMembers, saveProjectMembers, saveProjectDatasourcePolicy
} from '../../api/client'
import { currentProject, analysisForm, analysisResult, confirm } from '../core'
import { reloadDbhubDatasources } from './dbhubStore'
import { loadAiConfigs } from './aiConfigStore'
import { isAdmin } from './authStore'
import { loadUsers, users } from './usersStore'

export const projects = ref([])
export const versions = ref([])
export const datasources = ref([])
export const selectedProjectId = ref(null)
export const datasourceProjectId = ref(null)
export const zipFile = ref(null)
export const zipImporting = ref(false)
export const zipImportProgress = ref('')
export const apiRoutes = ref([])
export const routesLoading = ref(false)
export const selectedApiPrefix = ref('')
export const projectDialogVisible = ref(false)
const SELECTED_PROJECT_KEY = 'bug-agent-selected-project'

export const projectForm = reactive({ id: null, name: '', code: '', description: '', environments: ['prod', 'test'] })
// 项目可见范围：勾选的普通用户 id 列表（管理员天然可见全部，不在此列）
export const projectMemberIds = ref([])
export const projectQuery = reactive({ name: '', code: '' })
export const gitForm = reactive({ repoUrl: '', branchName: '', accessToken: '' })
export const environmentOptions = computed(() => {
  return parseProjectEnvironments(currentProject.value).map(env => ({ value: env, label: env }))
})
export const databasePolicyOptions = [
  { value: 'AUTO', label: '自动判断（推荐）' },
  { value: 'NONE', label: '完全不访问数据库' },
  { value: 'SCHEMA_ONLY', label: '仅核对表结构' },
  { value: 'BUSINESS_DATA', label: '核对当前环境业务数据' }
]
export const datasourceForm = reactive({ env: 'test', dbhubKey: '' })
export const datasourcePolicyForm = reactive({ schemaConsistent: true, schemaReferenceEnv: 'test' })

export const currentVersion = computed(() => {
  if (!currentProject.value) return null
  if (analysisForm.versionId) {
    return versions.value.find((version) => version.id === Number(analysisForm.versionId)) || null
  }
  return versions.value[0] || null
})
export const currentDatasource = computed(() => {
  if (!currentProject.value) return null
  return datasourceAccessPreview.value.businessDatasource || datasourceAccessPreview.value.schemaDatasource || null
})

/** 当前分析环境下的实际数据库能力预览，后端会按同一规则再次校验。 */
export const datasourceAccessPreview = computed(() => {
  const environment = analysisForm.environment || 'prod'
  const policy = analysisForm.databasePolicy || 'AUTO'
  const businessDatasource = findEnabledDatasource(environment)
  const referenceDatasource = currentProject.value?.schemaConsistent
    ? findEnabledDatasource(currentProject.value.schemaReferenceEnv || 'test')
    : null
  const schemaDatasource = businessDatasource || referenceDatasource
  if (policy === 'NONE') {
    return accessPreview('NONE', null, null, '本次完全不访问数据库，只使用源码、日志、堆栈和请求响应。')
  }
  if (policy === 'SCHEMA_ONLY') {
    return schemaDatasource
      ? accessPreview('SCHEMA_ONLY', schemaDatasource, null, schemaOnlyDescription(schemaDatasource, environment))
      : accessPreview('NONE', null, null, '没有可用的结构参考数据源，本次不会访问数据库。')
  }
  if (policy === 'BUSINESS_DATA') {
    return businessDatasource
      ? accessPreview('BUSINESS_DATA', businessDatasource, businessDatasource, '允许核对 ' + environment + ' 环境业务数据。')
      : accessPreview('UNAVAILABLE', schemaDatasource, null, '未配置 ' + environment + ' 环境数据源，不能核对业务数据，请改用自动判断或仅表结构。')
  }
  if (businessDatasource) {
    return accessPreview('BUSINESS_DATA', businessDatasource, businessDatasource, '已匹配 ' + environment + ' 环境数据源，可核对当前环境业务数据。')
  }
  if (schemaDatasource) {
    return accessPreview('SCHEMA_ONLY', schemaDatasource, null, schemaOnlyDescription(schemaDatasource, environment))
  }
  return accessPreview('NONE', null, null, '未配置 ' + environment + ' 环境数据源，也没有结构参考库，本次只使用源码和日志。')
})

function findEnabledDatasource(environment) {
  return datasources.value.find(item => item.enabled && String(item.env).toLowerCase() === String(environment).toLowerCase()) || null
}

function accessPreview(accessLevel, schemaDatasource, businessDatasource, description) {
  const labels = {
    NONE: '不访问数据库',
    SCHEMA_ONLY: '仅核对表结构',
    BUSINESS_DATA: '当前环境业务数据',
    UNAVAILABLE: '数据源不匹配'
  }
  return { accessLevel, accessLabel: labels[accessLevel] || accessLevel, schemaDatasource, businessDatasource, description }
}

function schemaOnlyDescription(datasource, environment) {
  return '问题环境为 ' + environment + '，仅使用 ' + datasource.env + ' 库核对字段和类型；不会读取行数、样例、配置值或业务记录。'
}

export const projectOptions = computed(() => projects.value.map((project) => ({ value: project.id, label: project.name })))
export const versionOptions = computed(() => versions.value.map((version) => ({ value: version.id, label: versionOptionLabel(version) })))

export const apiPrefixes = computed(() => {
  const prefixes = new Set()
  apiRoutes.value.forEach((route) => {
    const prefix = routePrefix(route.path)
    if (prefix) {
      prefixes.add(prefix)
    }
  })
  return Array.from(prefixes).sort()
})
export const apiPrefixOptions = computed(() => apiPrefixes.value.map((prefix) => ({ value: prefix, label: prefix })))

export const filteredApiRoutes = computed(() => {
  if (!selectedApiPrefix.value) {
    return []
  }
  return apiRoutes.value.filter((route) => routePrefix(route.path) === selectedApiPrefix.value)
})
export const apiRouteOptions = computed(() => filteredApiRoutes.value.map((route) => ({ value: route.path, label: route.path })))

export const zipFileList = computed(() => (zipFile.value ? [{ uid: 'zip', name: zipFile.value.name }] : []))

export function projectRowEvents(record) {
  return {
    onClick: () => selectProject(record),
    onDblclick: () => openProjectDialog(record)
  }
}

export function projectRowClass(record) {
  return currentProject.value?.id === record.id ? 'active-row' : ''
}

// 首屏加载：项目、全局 dbhub 数据源、当前项目关联数据、AI 配置
export async function loadAll() {
  await loadProjects()
  await reloadDbhubDatasources(true)
  await loadProjectRelated()
  await loadAiConfigs()
}

export async function loadProjects() {
  projects.value = await listProjects(projectQuery)
  const previousProject = currentProject.value
  const preferredId = previousProject?.id || loadSelectedProjectId()
  const matched = projects.value.find(project => Number(project.id) === Number(preferredId))
  const filtered = Boolean(projectQuery.name?.trim() || projectQuery.code?.trim())
  // 查询条件可能暂时过滤掉当前项目，此时保持选择；完整列表确认项目不存在时才回退第一项
  currentProject.value = matched || (filtered ? previousProject : null) || projects.value[0] || null
  selectedProjectId.value = currentProject.value?.id || null
  saveSelectedProjectId(selectedProjectId.value)
  if (!datasourceProjectId.value && currentProject.value) {
    datasourceProjectId.value = currentProject.value.id
  }
  if (!currentProject.value) {
    selectedProjectId.value = null
    datasourceProjectId.value = null
    versions.value = []
    datasources.value = []
  }
}

export async function loadProjectRelated() {
  if (!currentProject.value) return
  versions.value = await listVersions(currentProject.value.id)
  datasources.value = await listDatasources(currentProject.value.id)
  syncDatasourcePolicyForm()
  restoreAnalysisEnvironment(currentProject.value.id)
  if (!analysisForm.versionId && versions.value.length) {
    analysisForm.versionId = versions.value[0].id
  }
  await loadApiRoutes()
}

export async function saveProjectAction() {
  if (!projectForm.name || !projectForm.code || !projectForm.environments?.length) {
    message.warning('项目名称、项目编码和项目环境不能为空')
    return
  }
  const payload = { ...projectForm, environments: normalizeEnvironmentList(projectForm.environments).join(',') }
  const saved = projectForm.id ? await updateProject(projectForm.id, payload) : await createProject(payload)
  // 可见范围全量替换：勾选列表即最终授权状态
  await saveProjectMembers(saved.id, projectMemberIds.value)
  message.success(projectForm.id ? '项目已修改' : '项目已创建')
  projectDialogVisible.value = false
  resetProjectForm()
  currentProject.value = saved
  selectedProjectId.value = saved.id
  saveSelectedProjectId(saved.id)
  datasourceProjectId.value = saved.id
  await loadAll()
}

export async function openProjectDialog(row) {
  // 项目由管理员维护，普通用户双击行/点按钮都不给开弹窗
  if (!isAdmin.value) return
  if (row) {
    Object.assign(projectForm, {
      id: row.id,
      name: row.name,
      code: row.code,
      description: row.description || '',
      environments: parseProjectEnvironments(row)
    })
  } else {
    resetProjectForm()
  }
  projectDialogVisible.value = true
  // 勾选项要用的用户列表与已授权名单异步补齐，不挡弹窗打开
  if (!users.value.length) {
    loadUsers().catch(() => {})
  }
  projectMemberIds.value = row ? await getProjectMembers(row.id).catch(() => []) : []
}

export function resetProjectForm() {
  Object.assign(projectForm, { id: null, name: '', code: '', description: '', environments: ['prod', 'test'] })
  projectMemberIds.value = []
}

export async function deleteProjectAction(row) {
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

export async function resetProjectQuery() {
  Object.assign(projectQuery, { name: '', code: '' })
  await loadAll()
}

export async function selectProject(row) {
  currentProject.value = row
  selectedProjectId.value = row?.id || null
  saveSelectedProjectId(selectedProjectId.value)
  datasourceProjectId.value = row?.id || null
  await loadProjectRelated()
}

export async function changeProject(projectId) {
  currentProject.value = projects.value.find(project => Number(project.id) === Number(projectId)) || null
  datasourceProjectId.value = currentProject.value?.id || null
  saveSelectedProjectId(currentProject.value?.id)
  analysisResult.value = null
  analysisForm.apiPath = ''
  selectedApiPrefix.value = ''
  analysisForm.versionId = ''
  analysisForm.databasePolicy = 'AUTO'
  apiRoutes.value = []
  restoreAnalysisEnvironment(projectId)
  await loadProjectRelated()
}

export async function changeDatasourceProject(projectId) {
  const project = projects.value.find((item) => item.id === projectId) || null
  if (project) {
    datasourceProjectId.value = project.id
    currentProject.value = project
    selectedProjectId.value = project.id
    saveSelectedProjectId(project.id)
    syncDatasourcePolicyForm()
    await loadProjectRelated()
  }
}

/** 保存项目本次选择的分析环境，下次进入时直接恢复。 */
export function changeAnalysisEnvironment(environment) {
  analysisForm.environment = environment || environmentOptions.value[0]?.value || ''
  if (currentProject.value?.id && typeof window !== 'undefined') {
    window.localStorage.setItem('bug-agent-analysis-env-' + currentProject.value.id, analysisForm.environment)
  }
}

function restoreAnalysisEnvironment(projectId) {
  const options = environmentOptions.value.map(item => item.value)
  const saved = projectId && typeof window !== 'undefined'
    ? window.localStorage.getItem('bug-agent-analysis-env-' + projectId)
    : null
  analysisForm.environment = options.includes(saved) ? saved : (options[0] || '')
}

function syncDatasourcePolicyForm() {
  const environments = environmentOptions.value.map(item => item.value)
  const reference = currentProject.value?.schemaReferenceEnv
  if (!environments.includes(datasourceForm.env)) {
    datasourceForm.env = environments[0] || ''
  }
  Object.assign(datasourcePolicyForm, {
    schemaConsistent: currentProject.value?.schemaConsistent !== false,
    schemaReferenceEnv: environments.includes(reference) ? reference : (environments[0] || '')
  })
}

function parseProjectEnvironments(project) {
  return normalizeEnvironmentList(String(project?.environments || '').split(','))
}

function normalizeEnvironmentList(environments) {
  return [...new Set((environments || [])
    .map(item => String(item).trim().toLowerCase())
    .filter(Boolean))]
}

function loadSelectedProjectId() {
  if (typeof window === 'undefined') return null
  const value = window.localStorage.getItem(SELECTED_PROJECT_KEY)
  return value ? Number(value) : null
}

function saveSelectedProjectId(projectId) {
  if (typeof window === 'undefined') return
  if (projectId == null) {
    window.localStorage.removeItem(SELECTED_PROJECT_KEY)
    return
  }
  window.localStorage.setItem(SELECTED_PROJECT_KEY, String(projectId))
}

export async function changeAnalysisVersion(versionId) {
  analysisForm.versionId = versionId || ''
  analysisForm.apiPath = ''
  selectedApiPrefix.value = ''
  await loadApiRoutes()
}

export async function loadApiRoutes(keyword = '') {
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

export function changeApiPrefix(prefix) {
  selectedApiPrefix.value = prefix || ''
  analysisForm.apiPath = ''
}

export function versionOptionLabel(version) {
  return `${version.id} / ${version.sourceType} / ${version.indexStatus}`
}

export function changeApiPath(value) {
  analysisForm.apiPath = value || ''
  selectedApiPrefix.value = value ? routePrefix(value) : selectedApiPrefix.value
}

export function routePrefix(path) {
  if (!path) return ''
  const parts = String(path).split('/').filter(Boolean)
  return parts.length ? `/${parts[0]}` : ''
}

export async function importGitAction() {
  await importGit(currentProject.value.id, gitForm)
  message.success('Git 导入任务已提交')
  await loadProjectRelated()
}

export function beforeZipUpload(file) {
  if (!file.name.toLowerCase().endsWith('.zip')) {
    message.warning('请选择 ZIP 文件')
    return false
  }
  if (file.size > 200 * 1024 * 1024) {
    message.warning('ZIP 文件不能超过 200MB')
    return false
  }
  zipFile.value = file
  zipImportProgress.value = ''
  return false
}

export function removeZip() {
  zipFile.value = null
  zipImportProgress.value = ''
}

export async function deleteVersionAction(row) {
  await confirm('确认删除这个版本吗？对应的代码索引和磁盘源码会一起删除。', '删除版本')
  await deleteVersion(currentProject.value.id, row.id)
  message.success('版本已删除')
  await loadProjectRelated()
}

export async function importZipAction() {
  if (!zipFile.value) {
    message.warning('请选择 ZIP 文件')
    return
  }
  const projectId = currentProject.value.id
  zipImporting.value = true
  zipImportProgress.value = '准备上传…'
  try {
    const versionId = await importZip(projectId, zipFile.value, (loaded, total, elapsedMs) => {
      const percent = Math.min(100, Math.round(loaded * 100 / total))
      const elapsedSeconds = Math.max(elapsedMs / 1000, 0.1)
      const speed = loaded / 1024 / 1024 / elapsedSeconds
      zipImportProgress.value = percent >= 100
        ? '上传完成，服务器正在保存 ZIP…'
        : '上传 ' + percent + '% · ' + speed.toFixed(1) + ' MB/s · ' + elapsedSeconds.toFixed(1) + 's'
    })
    zipImportProgress.value = '上传完成，后端正在解压源码…'
    message.success('ZIP 上传完成，正在后台解压并建立索引')
    await waitZipImport(projectId, versionId)
  } catch (error) {
    message.error(error.message || 'ZIP 导入失败')
  } finally {
    zipImporting.value = false
  }
}

async function waitZipImport(projectId, versionId) {
  while (currentProject.value?.id === projectId) {
    const latestVersions = await listVersions(projectId)
    versions.value = latestVersions
    const version = latestVersions.find(item => item.id === Number(versionId))
    if (!version) {
      throw new Error('ZIP 导入版本不存在')
    }
    zipImportProgress.value = version.indexMessage || '后台正在处理源码…'
    if (version.indexStatus === 'SUCCESS') {
      zipImportProgress.value = '源码索引完成'
      zipFile.value = null
      message.success('源码索引完成')
      await loadProjectRelated()
      return
    }
    if (version.indexStatus === 'FAILED') {
      throw new Error(version.indexMessage || '源码索引失败')
    }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  zipImportProgress.value = '导入任务仍在后台执行，可切回项目查看状态'
}

export async function saveDatasourceAction() {
  if (!datasourceProjectId.value) {
    message.warning('先选择要配置 dbhub 的项目')
    return
  }
  await saveDatasource(datasourceProjectId.value, datasourceForm)
  message.success('数据源已保存')
  await loadProjectRelated()
}

/** 保存跨环境结构复用规则。 */
export async function saveDatasourcePolicyAction() {
  if (!datasourceProjectId.value) {
    message.warning('先选择要配置的项目')
    return
  }
  const saved = await saveProjectDatasourcePolicy(datasourceProjectId.value, datasourcePolicyForm)
  const index = projects.value.findIndex(item => item.id === saved.id)
  if (index >= 0) {
    projects.value[index] = saved
  }
  if (currentProject.value?.id === saved.id) {
    currentProject.value = saved
  }
  syncDatasourcePolicyForm()
  message.success('数据库结构策略已保存')
}

/** 登出清场：换用户后不能残留上一位的项目列表与选择 */
export function resetProjectState() {
  projects.value = []
  versions.value = []
  datasources.value = []
  apiRoutes.value = []
  selectedProjectId.value = null
  saveSelectedProjectId(null)
  datasourceProjectId.value = null
  selectedApiPrefix.value = ''
  zipFile.value = null
  zipImporting.value = false
  zipImportProgress.value = ''
  projectDialogVisible.value = false
  projectMemberIds.value = []
  analysisForm.environment = 'prod'
  analysisForm.databasePolicy = 'AUTO'
  Object.assign(datasourcePolicyForm, { schemaConsistent: true, schemaReferenceEnv: 'test' })
}
