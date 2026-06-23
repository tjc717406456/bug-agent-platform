<template>
  <a-modal v-model:open="aiDialogVisible" :title="editingAiId ? '编辑 AI 配置' : '新增 AI 配置'" width="560px" centered destroy-on-close @ok="saveAiAction">
    <a-form layout="vertical">
      <a-form-item label="Provider"><a-input v-model:value="aiForm.provider" /></a-form-item>
      <a-form-item label="Base URL"><a-input v-model:value="aiForm.baseUrl" placeholder="http://host:port/v1" /></a-form-item>
      <a-form-item label="Model"><a-input v-model:value="aiForm.modelName" /></a-form-item>
      <a-form-item label="API Key"><a-input-password v-model:value="aiForm.apiKey" :placeholder="editingAiId ? '留空则不修改' : ''" /></a-form-item>
      <a-form-item label="超时秒"><a-input-number v-model:value="aiForm.timeoutSeconds" :min="10" :max="300" style="width: 100%" /></a-form-item>
      <a-form-item>
        <a-checkbox v-model:checked="aiForm.supportsVision">该模型支持视觉(多模态)，分析时把报错截图喂图识读</a-checkbox>
      </a-form-item>
      <a-form-item label="角色">
        <a-radio-group v-model:value="aiForm.role">
          <a-radio value="PRIMARY">主分析(bug 定位/自检)</a-radio>
          <a-radio value="UTILITY">辅助(接口讲解/侦察，便宜模型)</a-radio>
        </a-radio-group>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { useAppStore } from '../../store/useAppStore'

const { aiDialogVisible, aiForm, saveAiAction, editingAiId } = useAppStore()
</script>
