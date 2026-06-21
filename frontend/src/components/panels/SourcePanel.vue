<template>
  <section class="panel">
    <div class="panel-title">
      <h2>源码导入</h2>
      <a-tag v-if="currentProject" color="blue">当前：{{ currentProject.name }}</a-tag>
    </div>
    <a-alert v-if="!currentProject" type="warning" show-icon message="先在项目管理里选择一个项目" />
    <div v-else class="split">
      <a-card title="Git 拉取" :bordered="false">
        <a-form layout="vertical">
          <a-form-item label="仓库地址"><a-input v-model:value="gitForm.repoUrl" /></a-form-item>
          <a-form-item label="分支"><a-input v-model:value="gitForm.branchName" placeholder="可空" /></a-form-item>
          <a-form-item label="Token"><a-input-password v-model:value="gitForm.accessToken" placeholder="首版预留" /></a-form-item>
          <a-button type="primary" @click="importGitAction"><template #icon><DownloadOutlined /></template>导入 Git</a-button>
        </a-form>
      </a-card>
      <a-card title="ZIP 上传" :bordered="false">
        <a-upload-dragger :max-count="1" accept=".zip" :file-list="zipFileList" :before-upload="beforeZipUpload" @remove="removeZip">
          <p class="ant-upload-drag-icon"><InboxOutlined /></p>
          <p class="ant-upload-text">拖入 ZIP 或点击选择</p>
        </a-upload-dragger>
        <a-button type="primary" class="top-gap" @click="importZipAction"><template #icon><UploadOutlined /></template>上传并索引</a-button>
      </a-card>
    </div>
    <a-table class="top-gap" :data-source="versions" :pagination="false" row-key="id" size="middle">
      <a-table-column title="来源" data-index="sourceType" :width="100" />
      <a-table-column title="索引状态" data-index="indexStatus" :width="130" />
      <a-table-column title="更新时间" data-index="updatedAt" :width="200" />
      <a-table-column title="索引消息" data-index="indexMessage" :ellipsis="true" />
      <a-table-column title="操作" :width="90">
        <template #default="{ record }">
          <a-button type="link" danger size="small" @click="deleteVersionAction(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>
  </section>
</template>

<script setup>
import { DownloadOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { currentProject, gitForm, importGitAction, zipFileList, beforeZipUpload, removeZip, importZipAction, versions, deleteVersionAction } = useAppStore()
</script>
