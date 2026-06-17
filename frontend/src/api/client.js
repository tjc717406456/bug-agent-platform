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

export function listProjects() {
  return request('/projects')
}

export function createProject(payload) {
  return request('/projects', { method: 'POST', body: JSON.stringify(payload) })
}

export function listVersions(projectId) {
  return request(`/projects/${projectId}/versions`)
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

export function getAiConfig() {
  return request('/ai-config')
}

export function saveAiConfig(payload) {
  return request('/ai-config', { method: 'POST', body: JSON.stringify(payload) })
}

export function testAiConfig() {
  return request('/ai-config/test', { method: 'POST' })
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

