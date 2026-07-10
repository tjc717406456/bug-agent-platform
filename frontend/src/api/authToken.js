// token 的唯一落点。刻意做成不依赖任何 store 的叶子模块：
// client.js 要读 token，authStore 要调 client.js 登录——两边都只依赖这里，避免循环 import。
const TOKEN_KEY = 'bug-agent-token'

let unauthorizedHandler = null

export function getToken() {
  return typeof window === 'undefined' ? null : window.localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(TOKEN_KEY, token)
  }
}

export function clearToken() {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(TOKEN_KEY)
  }
}

/** authStore 启动时注册；收到 401 由 client.js 回调，统一走登出流程 */
export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler
}

// 并发请求同时 401 时只触发一次登出与一次提示：token 已被清掉就直接跳过
export function notifyUnauthorized() {
  if (!getToken()) {
    return
  }
  clearToken()
  if (unauthorizedHandler) {
    unauthorizedHandler()
  }
}
