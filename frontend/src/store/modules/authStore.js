import { ref, computed } from 'vue'
import { message } from 'ant-design-vue'
import { login as loginApi, logoutApi, getMe, changePassword as changePasswordApi } from '../../api/client'
import { getToken, setToken, clearToken, setUnauthorizedHandler } from '../../api/authToken'

export const currentUser = ref(null)
// 会话恢复完成前不渲染任何界面，避免先闪登录页再跳主界面
export const authReady = ref(false)
export const loginPending = ref(false)
// 修改密码弹窗，由用户主动打开；不强制改密
export const changePwdVisible = ref(false)

export const isAuthenticated = computed(() => !!currentUser.value)
export const isAdmin = computed(() => currentUser.value?.role === 'ADMIN')

// 登出时要连带清空各业务 store，由 useAppStore 聚合层注入，避免 authStore 反向依赖所有模块
let logoutCleanup = null
export function registerLogoutCleanup(fn) {
  logoutCleanup = fn
}

function applyLoggedOut() {
  currentUser.value = null
  clearToken()
  if (logoutCleanup) {
    logoutCleanup()
  }
}

// token 过期由 client.js 集中捕获 401 后回调到这里
setUnauthorizedHandler(() => {
  if (currentUser.value) {
    message.warning('登录已过期，请重新登录')
  }
  applyLoggedOut()
})

export async function loginAction(username, password) {
  if (loginPending.value) {
    return false
  }
  loginPending.value = true
  try {
    const result = await loginApi(username, password)
    setToken(result.token)
    currentUser.value = { username: result.username, role: result.role, displayName: result.displayName }
    return true
  } catch (error) {
    message.error(error.message || '登录失败')
    return false
  } finally {
    loginPending.value = false
  }
}

/** 页面加载时用已存的 token 换回身份；token 失效就干净地停在登录页。 */
export async function restoreSession() {
  try {
    if (getToken()) {
      currentUser.value = await getMe()
    }
  } catch (error) {
    clearToken()
    currentUser.value = null
  } finally {
    authReady.value = true
  }
}

export async function logoutAction() {
  try {
    await logoutApi()
  } catch (error) {
    // 后端已失效或网络问题都不该挡住本地登出
  }
  applyLoggedOut()
  message.success('已退出登录')
}

export async function changePasswordAction(oldPassword, newPassword) {
  await changePasswordApi(oldPassword, newPassword)
  changePwdVisible.value = false
  // 后端改密后会踢掉该用户全部 token（含本次会话），这里直接回到登录页
  applyLoggedOut()
  message.success('密码已修改，请用新密码重新登录')
}
