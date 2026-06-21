<template>
  <a-modal v-model:open="projectDialogVisible" :title="projectForm.id ? '编辑项目' : '新增项目'" width="560px" centered destroy-on-close @ok="saveProjectAction">
    <a-form layout="vertical">
      <a-form-item label="项目名称"><a-input v-model:value="projectForm.name" /></a-form-item>
      <a-form-item label="项目编码"><a-input v-model:value="projectForm.code" /></a-form-item>
      <a-form-item label="说明"><a-textarea v-model:value="projectForm.description" :rows="3" /></a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { useAppStore } from '../../store/useAppStore'

const { projectDialogVisible, projectForm, saveProjectAction } = useAppStore()
</script>
