import { ref, reactive, computed } from 'vue'
import { message } from 'ant-design-vue'
import { listDbhubDatasources, saveDbhubDatasource, testDbhubDatasource, deleteDbhubDatasource } from '../../api/client'
import { confirm } from '../core'

export const dbhubDatasources = ref([])
export const selectedDbhubDatasources = ref([])
export const selectedDbhubKeys = ref([])
export const dbhubDialogVisible = ref(false)
// 编辑态锁 key：key 是业务主键，编辑时改 key 会被后端当新记录 insert
export const dbhubEditing = ref(false)
export const dbhubForm = reactive({ key: '', host: 'localhost', port: 3306, user: 'root', password: '1234', database: '' })
export const dbhubQuery = reactive({ user: '', database: '' })

export const dbhubKeyOptions = computed(() => dbhubDatasources.value.map((item) => ({ value: item.key, label: item.key })))

export const filteredDbhubDatasources = computed(() => {
  const user = dbhubQuery.user.trim().toLowerCase()
  const database = dbhubQuery.database.trim().toLowerCase()
  return dbhubDatasources.value.filter((item) => {
    const matchedUser = !user || String(item.user || '').toLowerCase().includes(user)
    const matchedDatabase = !database || String(item.database || '').toLowerCase().includes(database)
    return matchedUser && matchedDatabase
  })
})

export const dbhubRowSelection = computed(() => ({
  selectedRowKeys: selectedDbhubKeys.value,
  onChange: (keys, rows) => {
    selectedDbhubKeys.value = keys
    selectedDbhubDatasources.value = rows
  }
}))

// 供 project.loadAll 复用，重新拉全局 dbhub 数据源池
export async function reloadDbhubDatasources(ignoreError = false) {
  dbhubDatasources.value = ignoreError
    ? await listDbhubDatasources().catch(() => [])
    : await listDbhubDatasources()
}

export function dbhubRowEvents(record) {
  return { onDblclick: () => openDbhubDialog(record) }
}

export async function saveDbhubDatasourceAction() {
  if (!dbhubForm.key || !dbhubForm.database) {
    message.warning('数据源 Key 和库名不能为空')
    return
  }
  await saveDbhubDatasource(dbhubForm)
  message.success('dbhub 数据源已保存')
  await reloadDbhubDatasources()
  dbhubDialogVisible.value = false
}

export async function testDbhubDatasourceAction() {
  if (!dbhubForm.key || !dbhubForm.database) {
    message.warning('先填写完整数据源信息')
    return
  }
  const result = await testDbhubDatasource(dbhubForm)
  message.success(result)
}

export function openDbhubDialog(row) {
  dbhubEditing.value = !!row
  if (row) {
    fillDbhubForm(row)
  } else {
    resetDbhubForm()
  }
  dbhubDialogVisible.value = true
}

export function fillDbhubForm(row) {
  Object.assign(dbhubForm, { key: row.key, host: row.host, port: row.port, user: row.user, password: '', database: row.database })
}

export function resetDbhubForm() {
  Object.assign(dbhubForm, { key: '', host: 'localhost', port: 3306, user: 'root', password: '1234', database: '' })
}

export function resetDbhubQuery() {
  Object.assign(dbhubQuery, { user: '', database: '' })
}

export async function deleteSelectedDbhubDatasources() {
  if (!selectedDbhubDatasources.value.length) {
    message.warning('请先勾选要删除的数据源')
    return
  }
  const keys = selectedDbhubDatasources.value.map((item) => item.key)
  await confirm(`确认删除 ${keys.join('、')} 吗？`, '删除数据源')
  await Promise.all(keys.map((key) => deleteDbhubDatasource(key)))
  message.success('数据源已删除')
  selectedDbhubDatasources.value = []
  selectedDbhubKeys.value = []
  await reloadDbhubDatasources()
}
