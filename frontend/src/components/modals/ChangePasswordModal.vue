<template>
  <a-modal
    v-model:open="changePwdVisible"
    title="修改密码"
    :confirm-loading="submitting"
    ok-text="确认修改"
    @ok="submit"
  >
    <p class="tip">修改成功后当前登录会失效，需要用新密码重新登录。</p>
    <a-form layout="vertical">
      <a-form-item label="当前密码">
        <a-input-password v-model:value="form.oldPassword" placeholder="请输入当前密码" autocomplete="current-password" />
      </a-form-item>
      <a-form-item label="新密码">
        <a-input-password v-model:value="form.newPassword" placeholder="至少 8 位" autocomplete="new-password" @press-enter="submit" />
      </a-form-item>
      <a-form-item label="确认新密码">
        <a-input-password v-model:value="form.confirmPassword" placeholder="再输入一次" autocomplete="new-password" @press-enter="submit" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useAppStore } from '../../store/useAppStore'

const { changePwdVisible, changePasswordAction } = useAppStore()

const submitting = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

watch(changePwdVisible, (open) => {
  if (open) {
    Object.assign(form, { oldPassword: '', newPassword: '', confirmPassword: '' })
  }
})

async function submit() {
  if (submitting.value) return
  if (!form.oldPassword || !form.newPassword) {
    message.warning('请填写当前密码和新密码')
    return
  }
  if (form.newPassword.length < 8) {
    message.warning('新密码至少 8 位')
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    message.warning('两次输入的新密码不一致')
    return
  }
  if (form.newPassword === form.oldPassword) {
    message.warning('新密码不能与当前密码相同')
    return
  }
  submitting.value = true
  try {
    await changePasswordAction(form.oldPassword, form.newPassword)
  } catch (error) {
    message.error(error.message || '修改失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tip {
  color: #888;
  font-size: 12px;
  margin-bottom: 12px;
}
</style>
