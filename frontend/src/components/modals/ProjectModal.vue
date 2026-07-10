<template>
  <a-modal v-model:open="projectDialogVisible" :title="projectForm.id ? '编辑项目' : '新增项目'" width="560px" centered destroy-on-close @ok="saveProjectAction">
    <a-form layout="vertical">
      <a-form-item label="项目名称"><a-input v-model:value="projectForm.name" /></a-form-item>
      <a-form-item label="项目编码"><a-input v-model:value="projectForm.code" /></a-form-item>
      <a-form-item label="说明"><a-textarea v-model:value="projectForm.description" :rows="3" /></a-form-item>
      <a-form-item label="可见范围">
        <a-select
          v-model:value="projectMemberIds"
          mode="multiple"
          :options="memberOptions"
          show-search
          option-filter-prop="label"
          placeholder="勾选可见该项目的用户，勾中即可直接分析"
          allow-clear
        />
        <div class="member-tip">管理员天然可见全部项目，无需勾选；未勾选任何人则仅管理员可见。</div>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed } from 'vue'
import { useAppStore } from '../../store/useAppStore'

const { projectDialogVisible, projectForm, saveProjectAction, projectMemberIds, users } = useAppStore()

// 管理员不进候选：他们看全部，授权没意义
const memberOptions = computed(() => users.value
  .filter((user) => user.role !== 'ADMIN')
  .map((user) => ({ value: user.id, label: user.displayName || user.username })))
</script>

<style scoped>
.member-tip {
  color: #999;
  font-size: 12px;
  margin-top: 4px;
}
</style>
