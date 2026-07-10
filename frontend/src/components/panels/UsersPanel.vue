<template>
  <section class="panel">
    <div class="panel-title">
      <h2>用户管理</h2>
      <div>
        <a-button type="primary" @click="openUserDialog()"><template #icon><PlusOutlined /></template>新增用户</a-button>
        <a-button class="left-gap" @click="loadUsers"><template #icon><ReloadOutlined /></template>刷新</a-button>
      </div>
    </div>
    <p class="panel-tip">账号由管理员创建，无开放注册。停用或重置密码会立即让该用户的登录失效，需其用新密码重新登录。</p>
    <a-table class="top-gap" :data-source="users" row-key="id" size="middle" :pagination="false">
      <a-table-column title="用户名" data-index="username" :width="160" />
      <a-table-column title="显示名" data-index="displayName" :width="160" />
      <a-table-column title="角色" :width="110">
        <template #default="{ record }">
          <a-tag :color="record.role === 'ADMIN' ? 'red' : 'blue'">{{ record.role === 'ADMIN' ? '管理员' : '普通用户' }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="状态" :width="100">
        <template #default="{ record }">
          <a-tag :color="record.status === 'ACTIVE' ? 'green' : 'default'">{{ record.status === 'ACTIVE' ? '启用' : '停用' }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="最近登录" data-index="lastLoginAt" :width="180" />
      <a-table-column title="操作" :width="220">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openUserDialog(record)">编辑</a-button>
          <!-- 重置是给别人用的：重置自己会立刻把自己踢下线，改自己的密码走右上角「修改密码」 -->
          <template v-if="record.username !== currentUser?.username">
            <a-button type="link" size="small" @click="openResetPwd(record)">重置密码</a-button>
            <a-button type="link" size="small" :danger="record.status === 'ACTIVE'" @click="toggleUserEnabled(record)">
              {{ record.status === 'ACTIVE' ? '停用' : '启用' }}
            </a-button>
          </template>
          <a-button v-else type="link" size="small" @click="changePwdVisible = true">修改我的密码</a-button>
        </template>
      </a-table-column>
    </a-table>
  </section>
</template>

<script setup>
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { users, loadUsers, openUserDialog, openResetPwd, toggleUserEnabled, currentUser, changePwdVisible } = useAppStore()
</script>
