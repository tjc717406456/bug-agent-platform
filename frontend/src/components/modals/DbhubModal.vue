<template>
  <a-modal v-model:open="dbhubDialogVisible" title="dbhub 数据源" width="640px" centered destroy-on-close>
    <a-form layout="vertical">
      <a-form-item label="数据源Key" :extra="dbhubEditing ? 'Key 是唯一标识，不可修改；要换名请删除后重建' : ''">
        <a-input v-model:value="dbhubForm.key" placeholder="user_bug_demo" :disabled="dbhubEditing" />
      </a-form-item>
      <a-form-item label="主机"><a-input v-model:value="dbhubForm.host" /></a-form-item>
      <a-form-item label="端口"><a-input-number v-model:value="dbhubForm.port" :min="1" :max="65535" style="width: 100%" /></a-form-item>
      <a-form-item label="库名"><a-input v-model:value="dbhubForm.database" /></a-form-item>
      <a-form-item label="用户名"><a-input v-model:value="dbhubForm.user" /></a-form-item>
      <a-form-item label="密码"><a-input-password v-model:value="dbhubForm.password" autocomplete="new-password" /></a-form-item>
    </a-form>
    <template #footer>
      <a-button @click="dbhubDialogVisible = false">取消</a-button>
      <a-button @click="testDbhubDatasourceAction"><template #icon><ApiOutlined /></template>测试连接</a-button>
      <a-button type="primary" @click="saveDbhubDatasourceAction"><template #icon><CheckOutlined /></template>保存</a-button>
    </template>
  </a-modal>
</template>

<script setup>
import { ApiOutlined, CheckOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { dbhubDialogVisible, dbhubEditing, dbhubForm, testDbhubDatasourceAction, saveDbhubDatasourceAction } = useAppStore()
</script>
