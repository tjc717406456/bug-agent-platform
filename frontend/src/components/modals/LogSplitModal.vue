<template>
  <a-modal v-model:open="logSplitVisible" title="日志按时间切割（本地处理，不上传后端）" width="600px" centered destroy-on-close>
    <a-alert
      type="info"
      show-icon
      class="bottom-gap"
      message="大文件在浏览器本地流式处理，原始日志不会上传。按行首时间戳 HH:mm:ss(.SSS) 过滤，无时间戳的续行（堆栈等）跟随上一条。"
    />

    <a-upload-dragger
      :max-count="1"
      accept=".log,.txt"
      :show-upload-list="false"
      :before-upload="pickFile"
    >
      <p class="ant-upload-drag-icon"><InboxOutlined /></p>
      <p class="ant-upload-text">拖入 .log 文件或点击选择（建议 ≤1GB）</p>
    </a-upload-dragger>

    <div v-if="file" class="file-info">
      已选：<b>{{ file.name }}</b> · {{ humanSize(file.size) }}
    </div>

    <a-form layout="vertical" class="top-gap">
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="开始时间">
            <a-time-picker v-model:value="startTime" value-format="HH:mm:ss" format="HH:mm:ss" placeholder="选开始时间" style="width:100%" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="结束时间">
            <a-time-picker v-model:value="endTime" value-format="HH:mm:ss" format="HH:mm:ss" placeholder="选结束时间" style="width:100%" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-form>

    <a-progress v-if="processing || percent === 100" :percent="percent" :status="processing ? 'active' : 'success'" />
    <div v-if="resultMsg" class="result-msg">{{ resultMsg }}</div>

    <template #footer>
      <a-button @click="logSplitVisible = false">关闭</a-button>
      <a-button type="primary" :loading="processing" @click="run">
        <template #icon><ScissorOutlined /></template>切割并下载
      </a-button>
    </template>
  </a-modal>
</template>

<script setup>
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { InboxOutlined, ScissorOutlined } from '@ant-design/icons-vue'
import { useAppStore } from '../../store/useAppStore'

const { logSplitVisible } = useAppStore()

const file = ref(null)
const startTime = ref(null)
const endTime = ref(null)
const processing = ref(false)
const percent = ref(0)
const resultMsg = ref('')

// before-upload 返回 false 阻止 antd 自动上传，只留住 File 对象本地用
function pickFile(f) {
  file.value = f
  resultMsg.value = ''
  percent.value = 0
  return false
}

function humanSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

// 把 HH:mm[:ss[.SSS]] 解析成"当天秒数"(含毫秒小数)，非法返回 null
function parseTime(text) {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[.,](\d{1,3}))?$/.exec((text || '').trim())
  if (!m) return null
  const ms = m[4] ? Number(m[4].padEnd(3, '0')) / 1000 : 0
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3] || 0) + ms
}

async function run() {
  if (!file.value) {
    message.warning('先选日志文件')
    return
  }
  if (!startTime.value || !endTime.value) {
    message.warning('先选好开始和结束时间')
    return
  }
  const startSec = parseTime(startTime.value)
  const endSec = parseTime(endTime.value)
  if (endSec < startSec) {
    message.warning('结束时间早于开始时间（跨午夜暂不支持，按当天时分秒比较）')
    return
  }

  processing.value = true
  percent.value = 0
  resultMsg.value = ''
  // 行首时间戳：HH:mm:ss(.SSS)
  const tsRe = /^(\d{2}):(\d{2}):(\d{2})(?:[.,](\d{1,3}))?/
  const parts = []
  let buffer = ''
  let keep = false
  let matched = 0
  let outBytes = 0

  function handleLine(line) {
    const m = tsRe.exec(line)
    if (m) {
      const ms = m[4] ? Number(m[4].padEnd(3, '0')) / 1000 : 0
      const t = Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) + ms
      keep = t >= startSec && t <= endSec
    }
    // 无时间戳的续行(堆栈/SQL 换行)跟随上一条命中状态
    if (keep) {
      parts.push(line)
      matched++
      outBytes += line.length
    }
  }

  try {
    const reader = file.value.stream().getReader()
    const decoder = new TextDecoder('utf-8')
    let readBytes = 0
    // 分块流式读，1G 也不会一次性进内存；await 让出事件循环，进度条能刷
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        buffer += decoder.decode()
        break
      }
      readBytes += value.byteLength
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n')) >= 0) {
        handleLine(buffer.slice(0, idx + 1))
        buffer = buffer.slice(idx + 1)
      }
      percent.value = Math.min(99, Math.floor((readBytes / file.value.size) * 100))
    }
    if (buffer) handleLine(buffer)

    if (matched === 0) {
      message.warning('该时间段没匹配到任何日志，确认时间范围和文件')
      processing.value = false
      return
    }
    const blob = new Blob(parts, { type: 'text/plain;charset=utf-8' })
    download(blob, buildName())
    percent.value = 100
    resultMsg.value = `命中 ${matched} 行 · 约 ${humanSize(outBytes)} · 已开始下载`
  } catch (error) {
    message.error('处理失败：' + (error && error.message ? error.message : error))
  } finally {
    processing.value = false
  }
}

function buildName() {
  const base = file.value.name.replace(/\.[^.]+$/, '')
  const tag = (startTime.value + '_' + endTime.value).replace(/[:.]/g, '-')
  return `${base}_${tag}.log`
}

function download(blob, name) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1500)
}
</script>

<style scoped>
.bottom-gap { margin-bottom: 12px; }
.top-gap { margin-top: 12px; }
.file-info { margin-top: 8px; color: #555; }
.result-msg { margin-top: 10px; color: #389e0d; }
</style>
