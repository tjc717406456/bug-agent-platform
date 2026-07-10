<template>
  <div class="login-page">
    <a-card class="login-card" :bordered="false">
      <div class="login-title">Bug Agent 平台</div>
      <div class="login-subtitle">后端 Bug 定位 · 接口讲解</div>
      <a-form layout="vertical" class="top-gap" @submit.prevent="submit">
        <a-form-item label="用户名">
          <a-input v-model:value="username" size="large" placeholder="请输入用户名" autocomplete="username" autofocus @press-enter="submit" />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password v-model:value="password" size="large" placeholder="请输入密码" autocomplete="current-password" @press-enter="submit" />
        </a-form-item>
        <a-button type="primary" size="large" block :loading="loginPending" @click="submit">登 录</a-button>
      </a-form>
      <p class="login-hint">账号由管理员创建。首次使用请查看后端启动日志中的初始管理员密码。</p>
    </a-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { useAppStore } from '../store/useAppStore'

const { loginAction, loginPending } = useAppStore()

const username = ref('')
const password = ref('')

// 登录成功后的数据初始化由 App.vue 监听登录态触发，这里不 emit：
// 本组件在 loginAction 返回时早已被 v-else-if 卸载，emit 发不出去
async function submit() {
  if (loginPending.value) return
  if (!username.value.trim() || !password.value) {
    message.warning('请输入用户名和密码')
    return
  }
  await loginAction(username.value.trim(), password.value)
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f1419 0%, #1c2530 100%);
}
.login-card {
  width: 380px;
  padding: 12px 8px;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.35);
}
.login-title {
  font-size: 24px;
  font-weight: 700;
  text-align: center;
  color: #1677ff;
}
.login-subtitle {
  text-align: center;
  color: #999;
  margin-top: 6px;
  font-size: 13px;
}
.login-hint {
  margin-top: 16px;
  margin-bottom: 0;
  color: #aaa;
  font-size: 12px;
  text-align: center;
  line-height: 1.6;
}
</style>
