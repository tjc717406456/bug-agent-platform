<template>
  <a-modal v-model:open="userDialogVisible" :title="userEditing ? '编辑用户' : '新增用户'" @ok="saveUserAction">
    <a-form layout="vertical">
      <a-form-item label="用户名">
        <a-input v-model:value="userForm.username" :disabled="userEditing" placeholder="登录名，创建后不可修改" autocomplete="off" />
      </a-form-item>
      <a-form-item v-if="!userEditing" label="初始密码">
        <a-input-password v-model:value="userForm.password" placeholder="至少 8 位" autocomplete="new-password" />
      </a-form-item>
      <a-form-item label="显示名">
        <a-input v-model:value="userForm.displayName" placeholder="可选，默认与用户名相同" />
      </a-form-item>
      <a-form-item label="角色">
        <a-select v-model:value="userForm.role">
          <a-select-option value="USER">普通用户（只能管理自己的项目）</a-select-option>
          <a-select-option value="ADMIN">管理员（可管 AI 配置、数据源、用户）</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item v-if="userEditing" label="状态">
        <a-select v-model:value="userForm.status">
          <a-select-option value="ACTIVE">启用</a-select-option>
          <a-select-option value="DISABLED">停用（立即踢下线）</a-select-option>
        </a-select>
      </a-form-item>
    </a-form>
  </a-modal>

  <a-modal v-model:open="resetPwdVisible" title="重置密码" @ok="resetPasswordAction">
    <p>为用户「{{ resetPwdForm.username }}」设置新密码，重置后该用户会被踢下线并需用新密码登录。</p>
    <a-input-password v-model:value="resetPwdForm.password" placeholder="新密码，至少 8 位" autocomplete="new-password" />
  </a-modal>
</template>

<script setup>
import { useAppStore } from '../../store/useAppStore'

const {
  userDialogVisible, userEditing, userForm, saveUserAction,
  resetPwdVisible, resetPwdForm, resetPasswordAction
} = useAppStore()
</script>
