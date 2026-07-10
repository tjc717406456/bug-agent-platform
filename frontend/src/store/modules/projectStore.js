import { ref, reactive, computed } from 'vue'
import { message } from 'ant-design-vue'
import {
  listProjects, listVersions, listDatasources, createProject, updateProject, deleteProject,
  listApiRoutes, importGit, importZip, deleteVersion, saveDatasource,
  getProjectMembers, saveProjectMembers
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
export const apiRoutes = ref([])
export const routesLoading = ref(false)
export const selectedApiPrefix = ref('')
export const projectDialogVisible = ref(false)

export const projectForm = reactive({ id: null, name: '', code: '', description: '' })
// 项目可见范围：勾选的普通用户 id 列表（管理员天然可见全部，不在此列）
export const projectMemberIds = ref([])
export const projectQuery = reactive({ name: '', code: '' })
export const gitForm = reactive({ repoUrl: '', branchName: '', accessToken: '' })
export const datasourceForm = reactive({ env: 'test', dbhubKey: '' })

export const currentVersion = computed(() => {
  if (!currentProject.value) return null
  if (analysisForm.versionId) {
    return versions.value.find((version) => version.id === Number(analysisForm.versionId)) || null
  }
  return versions.value[0] || null
})
export const currentDatasource = computed(() => {
  if (!currentProject.value) return null
  return datasources.value.find((item) => item.enabled) || datasources.value[0] || null
})

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

export async function loadProjectRelated() {
  if (!currentProject.value) return
  versions.value = await listVersions(currentProject.value.id)
  datasources.value = await listDatasources(currentProject.value.id)
  if (!analysisForm.versionId && versions.value.length) {
    analysisForm.versionId = versions.value[0].id
  }
  await loadApiRoutes()
}

export async function saveProjectAction() {
  if (!projectForm.name || !projectForm.code) {
    message.warning('项目名称和项目编码不能为空')
    return
  }
  const saved = projectForm.id ? await updateProject(projectForm.id, projectForm) : await createProject(projectForm)
  // 可见范围全量替换：勾选列表即最终授权状态
  await saveProjectMembers(saved.id, projectMemberIds.value)
  message.success(projectForm.id ? '项目已修改' : '项目已创建')
  projectDialogVisible.value = false
  resetProjectForm()
  currentProject.value = saved
  selectedProjectId.value = saved.id
  datasourceProjectId.value = saved.id
  await loadAll()
}

export async function openProjectDialog(row) {
  // 项目由管理员维护，普通用户双击行/点按钮都不给开弹窗
  if (!isAdmin.value) return
  if (row) {
    Object.assign(projectForm, { id: row.id, name: row.name, code: row.code, description: row.description || '' })
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
  Object.assign(projectForm, { id: null, name: '', code: '', description: '' })
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
  datasourceProjectId.value = row?.id || null
  await loadProjectRelated()
}

export async function changeProject(projectId) {
  currentProject.value = projects.value.find((project) => project.id === projectId) || null
  datasourceProjectId.value = currentProject.value?.id || null
  analysisResult.value = null
  analysisForm.apiPath = ''
  selectedApiPrefix.value = ''
  analysisForm.versionId = ''
  apiRoutes.value = []
  await loadProjectRelated()
}

export async function changeDatasourceProject(projectId) {
  const project = projects.value.find((item) => item.id === projectId) || null
  if (project) {
    datasourceProjectId.value = project.id
    currentProject.value = project
    selectedProjectId.value = project.id
    await loadProjectRelated()
  }
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
  zipFile.value = file
  return false
}

export function removeZip() {
  zipFile.value = null
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
  await importZip(currentProject.value.id, zipFile.value)
  message.success('ZIP 导入任务已提交')
  await loadProjectRelated()
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

/** 登出清场：换用户后不能残留上一位的项目列表与选择 */
export function resetProjectState() {
  projects.value = []
  versions.value = []
  datasources.value = []
  apiRoutes.value = []
  selectedProjectId.value = null
  datasourceProjectId.value = null
  selectedApiPrefix.value = ''
  zipFile.value = null
  projectDialogVisible.value = false
  projectMemberIds.value = []
}
