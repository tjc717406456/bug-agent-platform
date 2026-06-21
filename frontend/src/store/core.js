import { reactive, ref } from 'vue'
import { Modal } from 'ant-design-vue'

// 跨域共享的根状态：当前选中项目、分析表单、结果弹窗被多个面板同时读写，
// 集中放这里让各域 store 单向依赖，避免模块间互相 import 形成环
export const currentProject = ref(null)
export const activePanel = ref('projects')

// 分析输入表单：analysis 域主导，project 域联动版本/路由选择，history 回看时复用
export const analysisForm = reactive({ versionId: '', apiPath: '', userDescription: '', requestBody: '', responseBody: '', stackTrace: '', traceId: '', requestTime: '', logText: '', logId: '' })
export const analysisResult = ref(null)
export const analysisDialogVisible = ref(false)
export const activeReportTab = ref('report')
// 反馈标注只在历史页查看时可填；刚分析完不显示，让开发去历史确认
export const feedbackEditable = ref(false)

// Modal.confirm 的 Promise 封装：取消时 reject，保持调用方 await 流程不变
export function confirm(content, title = '确认') {
  return new Promise((resolve, reject) => {
    Modal.confirm({ title, content, okText: '确定', cancelText: '取消', onOk: () => resolve(true), onCancel: () => reject(new Error('cancelled')) })
  })
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
