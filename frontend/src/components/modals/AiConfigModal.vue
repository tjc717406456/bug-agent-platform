<template>
  <a-modal v-model:open="aiDialogVisible" title="新增 AI 配置" width="560px" centered destroy-on-close @ok="saveAiAction">
    <a-form layout="vertical">
      <a-form-item label="Provider"><a-input v-model:value="aiForm.provider" /></a-form-item>
      <a-form-item label="Base URL"><a-input v-model:value="aiForm.baseUrl" placeholder="http://host:port/v1" /></a-form-item>
      <a-form-item label="Model"><a-input v-model:value="aiForm.modelName" /></a-form-item>
      <a-form-item label="API Key"><a-input-password v-model:value="aiForm.apiKey" /></a-form-item>
      <a-form-item label="超时秒"><a-input-number v-model:value="aiForm.timeoutSeconds" :min="10" :max="300" style="width: 100%" /></a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { useAppStore } from '../../store/useAppStore'

const { aiDialogVisible, aiForm, saveAiAction } = useAppStore()
</script>
