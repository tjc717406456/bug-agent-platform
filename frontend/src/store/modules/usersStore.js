import { ref, reactive } from 'vue'
import { message } from 'ant-design-vue'
import { listUsers, createUser, updateUser, resetUserPassword } from '../../api/client'
import { confirm } from '../core'

export const users = ref([])
export const userDialogVisible = ref(false)
export const userEditing = ref(false)
export const userForm = reactive({ id: null, username: '', password: '', role: 'USER', displayName: '', status: 'ACTIVE' })
export const resetPwdVisible = ref(false)
export const resetPwdForm = reactive({ id: null, username: '', password: '' })

export async function loadUsers() {
  users.value = await listUsers().catch(() => [])
}

export function openUserDialog(row) {
  userEditing.value = !!row
  Object.assign(userForm, row
    ? { id: row.id, username: row.username, password: '', role: row.role, displayName: row.displayName || '', status: row.status }
    : { id: null, username: '', password: '', role: 'USER', displayName: '', status: 'ACTIVE' })
  userDialogVisible.value = true
}

export async function saveUserAction() {
  try {
    if (userEditing.value) {
      await updateUser(userForm.id, { role: userForm.role, status: userForm.status, displayName: userForm.displayName })
    } else {
      if (!userForm.username.trim() || userForm.password.length < 8) {
        message.warning('用户名必填，密码至少 8 位')
        return
      }
      await createUser({
        username: userForm.username.trim(),
        password: userForm.password,
        role: userForm.role,
        displayName: userForm.displayName
      })
    }
    userDialogVisible.value = false
    message.success('已保存')
    await loadUsers()
  } catch (error) {
    message.error(error.message || '保存失败')
  }
}

/** 停用/启用：后端会顺带踢掉该用户已签发的全部 token */
export async function toggleUserEnabled(row) {
  const disabling = row.status === 'ACTIVE'
  await confirm(`确认${disabling ? '停用' : '启用'}用户「${row.username}」吗？${disabling ? '该用户会立即被踢下线。' : ''}`, '变更用户状态')
  try {
    await updateUser(row.id, { role: row.role, status: disabling ? 'DISABLED' : 'ACTIVE', displayName: row.displayName })
    message.success('已更新')
    await loadUsers()
  } catch (error) {
    message.error(error.message || '操作失败')
  }
}

export function openResetPwd(row) {
  Object.assign(resetPwdForm, { id: row.id, username: row.username, password: '' })
  resetPwdVisible.value = true
}

export async function resetPasswordAction() {
  if (resetPwdForm.password.length < 8) {
    message.warning('密码至少 8 位')
    return
  }
  try {
    await resetUserPassword(resetPwdForm.id, resetPwdForm.password)
    resetPwdVisible.value = false
    message.success('密码已重置，该用户需重新登录')
  } catch (error) {
    message.error(error.message || '重置失败')
  }
}

export function resetUsersState() {
  users.value = []
  userDialogVisible.value = false
  resetPwdVisible.value = false
}
