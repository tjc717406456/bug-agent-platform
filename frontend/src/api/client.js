import { getToken, notifyUnauthorized } from './authToken'

const API_PREFIX = '/api'

async function request(path, options = {}) {
  // Authorization 必须无条件附上：FormData 请求（截图/日志/ZIP 上传）也要带，
  // 只有 Content-Type 需要跳过——那由浏览器按 multipart 边界自己生成
  const headers = { ...(options.headers || {}) }
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch(`${API_PREFIX}${path}`, { ...options, headers })

  // 401 = 未登录/过期，清 token 跳登录页；403 = 越权，只提示不登出
  if (response.status === 401) {
    notifyUnauthorized()
    const error = new Error('登录已过期，请重新登录')
    error.code = 'UNAUTHORIZED'
    throw error
  }
  const body = await response.json().catch(() => null)
  if (!response.ok || !body?.success) {
    const error = new Error(body?.message || `HTTP ${response.status}`)
    if (response.status === 403) {
      error.code = 'FORBIDDEN'
    }
    throw error
  }
  return body.data
}

export function listProjects(params = {}) {
  const query = new URLSearchParams()
  if (params.name) {
    query.set('name', params.name)
  }
  if (params.code) {
    query.set('code', params.code)
  }
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return request(`/projects${suffix}`)
}

export function createProject(payload) {
  return request('/projects', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateProject(projectId, payload) {
  return request(`/projects/${encodeURIComponent(projectId)}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function deleteProject(projectId) {
  return request(`/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE' })
}

export function listVersions(projectId) {
  return request(`/projects/${projectId}/versions`)
}

export function getProjectMembers(projectId) {
  return request(`/projects/${projectId}/members`)
}

export function saveProjectMembers(projectId, userIds) {
  return request(`/projects/${projectId}/members`, { method: 'PUT', body: JSON.stringify(userIds) })
}

export function deleteVersion(projectId, versionId) {
  return request(`/projects/${encodeURIComponent(projectId)}/versions/${encodeURIComponent(versionId)}`, { method: 'DELETE' })
}

export function listApiRoutes(projectId, versionId, keyword = '') {
  const params = new URLSearchParams()
  if (keyword) {
    params.set('keyword', keyword)
  }
  const query = params.toString() ? `?${params.toString()}` : ''
  return request(`/projects/${projectId}/versions/${versionId}/codegraph/routes${query}`)
}

export function importGit(projectId, payload) {
  return request(`/projects/${projectId}/sources/git`, { method: 'POST', body: JSON.stringify(payload) })
}

export function importZip(projectId, file, onProgress) {
  const form = new FormData()
  form.append('file', file)
  const url = API_PREFIX + '/projects/' + encodeURIComponent(projectId) + '/sources/zip'
  const startedAt = Date.now()
  console.info('HTTP请求', { url, method: 'POST', fileName: file.name, fileSize: file.size })
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    const token = getToken()
    if (token) {
      xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    }
    xhr.upload.onprogress = event => {
      if (event.lengthComputable && onProgress) {
        onProgress(event.loaded, event.total, Date.now() - startedAt)
      }
    }
    xhr.onload = () => {
      const elapsedMs = Date.now() - startedAt
      let body = null
      try {
        body = JSON.parse(xhr.responseText)
      } catch (error) {
        body = null
      }
      console.info('HTTP响应', { url, status: xhr.status, elapsedMs, success: body?.success })
      if (xhr.status === 401) {
        notifyUnauthorized()
        const error = new Error('登录已过期，请重新登录')
        error.code = 'UNAUTHORIZED'
        reject(error)
        return
      }
      if (xhr.status < 200 || xhr.status >= 300 || !body?.success) {
        reject(new Error(body?.message || 'HTTP ' + xhr.status))
        return
      }
      resolve(body.data)
    }
    xhr.onerror = () => {
      console.info('HTTP响应', { url, status: 0, elapsedMs: Date.now() - startedAt, success: false })
      reject(new Error('ZIP 上传网络异常'))
    }
    xhr.send(form)
  })
}

export function saveDatasource(projectId, payload) {
  return request(`/projects/${projectId}/datasources`, { method: 'POST', body: JSON.stringify(payload) })
}

export function listDatasources(projectId) {
  return request(`/projects/${projectId}/datasources`)
}

export async function saveProjectDatasourcePolicy(projectId, payload) {
  const path = `/projects/${projectId}/datasource-policy`
  console.info('HTTP请求', { url: API_PREFIX + path, method: 'PUT', payload })
  const result = await request(path, { method: 'PUT', body: JSON.stringify(payload) })
  console.info('HTTP响应', { url: API_PREFIX + path, success: true })
  return result
}

export function listDbhubDatasources() {
  return request('/dbhub/datasources')
}

export function saveDbhubDatasource(payload) {
  return request('/dbhub/datasources', { method: 'POST', body: JSON.stringify(payload) })
}

export function testDbhubDatasource(payload) {
  return request('/dbhub/datasources/test', { method: 'POST', body: JSON.stringify(payload) })
}

export function deleteDbhubDatasource(key) {
  return request(`/dbhub/datasources/${encodeURIComponent(key)}`, { method: 'DELETE' })
}

export function listAiConfigs() {
  return request('/ai-config')
}

export function createAiConfig(payload) {
  return request('/ai-config', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateAiConfig(id, payload) {
  return request(`/ai-config/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function activateAiConfig(id) {
  return request(`/ai-config/${encodeURIComponent(id)}/activate`, { method: 'POST' })
}

export function deleteAiConfig(id) {
  return request(`/ai-config/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function testAiConfig() {
  return request('/ai-config/test', { method: 'POST' })
}

export function testEmbeddingConfig() {
  return request('/ai-config/test-embedding', { method: 'POST' })
}

export function submitAgentAnalysisTaskScreenshots(payload, screenshots = []) {
  const form = new FormData()
  form.append('request', JSON.stringify(payload))
  screenshots.forEach((file) => form.append('screenshots', file))
  return request('/analysis/agent/tasks/screenshots', { method: 'POST', body: form })
}

export function pollAgentAnalysisTask(taskId) {
  return request(`/analysis/agent/tasks/${encodeURIComponent(taskId)}/poll`, { method: 'POST' })
}

export function stopAgentAnalysisTask(taskId) {
  return request(`/analysis/agent/tasks/${encodeURIComponent(taskId)}/stop`, { method: 'POST' })
}

export function resumeAgentAnalysisTask(taskId) {
  return request(`/analysis/agent/tasks/${encodeURIComponent(taskId)}/resume`, { method: 'POST' })
}

export function submitApiExplainTask(payload) {
  return request('/analysis/explain/tasks', { method: 'POST', body: JSON.stringify(payload) })
}

export function submitFollowUpTask(recordId, question) {
  return request(`/analysis/records/${encodeURIComponent(recordId)}/followup/tasks`, {
    method: 'POST',
    body: JSON.stringify({ question })
  })
}

export function submitAnalysisFeedback(recordId, payload) {
  return request(`/analysis/records/${encodeURIComponent(recordId)}/feedback`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function uploadLog(file, onProgress) {
  const form = new FormData()
  form.append('file', file)
  const url = API_PREFIX + '/analysis/logs/upload'
  const startedAt = Date.now()
  console.info('HTTP请求', { url, method: 'POST', fileName: file.name, fileSize: file.size })
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    const token = getToken()
    if (token) {
      xhr.setRequestHeader('Authorization', 'Bearer ' + token)
    }
    xhr.upload.onprogress = event => {
      if (event.lengthComputable && onProgress) {
        onProgress(event.loaded, event.total, Date.now() - startedAt)
      }
    }
    xhr.onload = () => {
      const elapsedMs = Date.now() - startedAt
      let body = null
      try {
        body = JSON.parse(xhr.responseText)
      } catch (error) {
        body = null
      }
      console.info('HTTP响应', { url, status: xhr.status, elapsedMs, success: body?.success })
      if (xhr.status === 401) {
        notifyUnauthorized()
        const error = new Error('登录已过期，请重新登录')
        error.code = 'UNAUTHORIZED'
        reject(error)
        return
      }
      if (xhr.status < 200 || xhr.status >= 300 || !body?.success) {
        reject(new Error(body?.message || 'HTTP ' + xhr.status))
        return
      }
      resolve(body.data)
    }
    xhr.onerror = () => {
      console.info('HTTP响应', { url, status: 0, elapsedMs: Date.now() - startedAt, success: false })
      reject(new Error('日志上传网络异常'))
    }
    xhr.send(form)
  })
}

export function listAnalysisRecords(params = {}) {
  const query = new URLSearchParams()
  if (params.projectId) query.set('projectId', params.projectId)
  if (params.apiPath) query.set('apiPath', params.apiPath)
  if (params.recordType) query.set('recordType', params.recordType)
  if (params.page != null) query.set('page', params.page)
  if (params.size != null) query.set('size', params.size)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return request(`/analysis/records${suffix}`)
}

export function getAnalysisRecord(recordId) {
  return request(`/analysis/records/${encodeURIComponent(recordId)}`)
}

export function batchDeleteAnalysisRecords(ids) {
  return request('/analysis/records/batch-delete', { method: 'POST', body: JSON.stringify(ids) })
}


// ===== 鉴权 =====
export function login(username, password) {
  return request('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
}

export function logoutApi() {
  return request('/auth/logout', { method: 'POST' })
}

export function getMe() {
  return request('/auth/me')
}

export function changePassword(oldPassword, newPassword) {
  return request('/auth/change-password', { method: 'POST', body: JSON.stringify({ oldPassword, newPassword }) })
}

// ===== 用户管理（管理员） =====
export function listUsers() {
  return request('/users')
}

export function createUser(payload) {
  return request('/users', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateUser(userId, payload) {
  return request(`/users/${encodeURIComponent(userId)}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function resetUserPassword(userId, password) {
  return request(`/users/${encodeURIComponent(userId)}/reset-password`, { method: 'POST', body: JSON.stringify({ password }) })
}

// ===== 脱敏元信息（所有登录用户可读） =====
export function getActiveModel() {
  return request('/meta/active-model')
}

export function listDatasourceKeys() {
  return request('/meta/datasource-keys')
}
