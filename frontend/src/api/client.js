const API_PREFIX = '/api'

async function request(path, options = {}) {
  const response = await fetch(`${API_PREFIX}${path}`, {
    headers: options.body instanceof FormData ? undefined : { 'Content-Type': 'application/json' },
    ...options
  })
  const body = await response.json().catch(() => null)
  if (!response.ok || !body?.success) {
    throw new Error(body?.message || `HTTP ${response.status}`)
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

export function importZip(projectId, file) {
  const form = new FormData()
  form.append('file', file)
  return request(`/projects/${projectId}/sources/zip`, { method: 'POST', body: form })
}

export function saveDatasource(projectId, payload) {
  return request(`/projects/${projectId}/datasources`, { method: 'POST', body: JSON.stringify(payload) })
}

export function listDatasources(projectId) {
  return request(`/projects/${projectId}/datasources`)
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

export function analyzeBug(payload) {
  return request('/analysis', { method: 'POST', body: JSON.stringify(payload) })
}

export function analyzeBugWithAgent(payload) {
  return request('/analysis/agent', { method: 'POST', body: JSON.stringify(payload) })
}

export function analyzeBugWithAgentScreenshots(payload, screenshots = []) {
  const form = new FormData()
  form.append('request', JSON.stringify(payload))
  screenshots.forEach((file) => form.append('screenshots', file))
  return request('/analysis/agent/screenshots', { method: 'POST', body: form })
}
export function submitAgentAnalysisTask(payload) {
  return request('/analysis/agent/tasks', { method: 'POST', body: JSON.stringify(payload) })
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

export function submitApiExplainTask(payload) {
  return request('/analysis/explain/tasks', { method: 'POST', body: JSON.stringify(payload) })
}

export function submitAnalysisFeedback(recordId, payload) {
  return request(`/analysis/records/${encodeURIComponent(recordId)}/feedback`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function uploadLog(file) {
  const form = new FormData()
  form.append('file', file)
  return request('/analysis/logs/upload', { method: 'POST', body: form })
}

export function listAnalysisRecords(params = {}) {
  const query = new URLSearchParams()
  if (params.projectId) query.set('projectId', params.projectId)
  if (params.apiPath) query.set('apiPath', params.apiPath)
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

