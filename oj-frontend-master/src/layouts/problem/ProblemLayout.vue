<template>
  <nav class="flex h-[50px] w-full min-w-[820px] justify-center bg-gray-100">
    <div class="mx-auto flex h-full w-full items-center px-6">
      <router-link to="/">
        <div class="flex items-center">
          <span class="h-[22px]">
            <img class="h-[22px] object-cover" src="/favicon.svg" alt="logo" />
          </span>

          <a-divider direction="vertical" />
        </div>
      </router-link>

      <ProblemRouter />

      <div v-if="!loading" class="flex-grow flex justify-center pr-[161px] space-x-[1px]">
        <a-button @click="handleRun" class="common-button" :style="{ color: 'black' }" type="text">
          <template #icon>
            <icon-font type="icon-yunhang" class="text-lg" />
          </template>
          运行
        </a-button>
        <a-button @click="handleSubmit" class="common-button" :style="{ color: '#01b328' }" type="text">
          <template #icon>
            <icon-font type="icon-cloudupload" class="text-lg" />
          </template>
          提交
        </a-button>
      </div>
      <div v-if="loading" class="flex-grow flex justify-center pr-[161px] text-gr">
        <a-button class="common-button" :style="{ background: '#E7E7E7' }" type="text" :disabled="true">
          <template #icon>
            <icon-loading class="text-lg" />
          </template>
          执行判题中...
        </a-button>
      </div>

      <a-space direction="horizontal" class="ml-auto">
        <user-avatar />
      </a-space>
    </div>
  </nav>
  <slot />
</template>

<script lang="ts" setup>
import type { Page } from '@/api/common/types'
import { doProblemRun, doProblemSubmit, getSubmitStatus } from '@/api/problem'
import { SubmitPollConfig } from '@/api/problem/constants'
import type {
  ProblemSubmitVo,
  SubmitStatusVo,
  TestResults,
} from '@/api/problem/types'
import UserAvatar from '@/components/header/UserAvatar.vue'
import { useWindowSize } from '@/composables/useWindowSize'
import { CODE_TEMPLATE } from '@/config/config'
import IconFont from '@/utils/iconfont'
import { flushOnHide, track } from '@/utils/track'
import { Message } from '@arco-design/web-vue'
import { computed, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import ProblemRouter from './components/ProblemRouter.vue'

// 与题目相关的状态
const language = ref('Java')
const code = ref(CODE_TEMPLATE)
const inputList = ref<{ id: string; input: string }[]>([])
const testResults = ref<TestResults>({} as TestResults)
const submitResult = ref<ProblemSubmitVo>({} as ProblemSubmitVo)
const submitLogs = ref<Page<ProblemSubmitVo>>({
  records: [],
  total: 0,
  size: 10,
  current: 1,
})

provide('language', language)
provide('code', code)
provide('inputList', inputList)
provide('testResults', testResults)
provide('submitResult', submitResult)
provide('submitLogs', submitLogs)

// 与标签页相关的状态
const infoActive = ref('1')
const codeActive = ref('1')
const debugActive = ref('1')
provide('infoActive', infoActive)
provide('codeActive', codeActive)
provide('debugActive', debugActive)

// 与折叠面板相关
const { pageHeight } = useWindowSize()
const height = ref()
provide('height', height)

// 布局页面自己的状态
const route = useRoute()
const problemId = computed(() => Number(route.params.problemId))
// 子组件（代码面板的复制埋点）需要知道当前题目
provide('problemId', problemId)
const loading = ref(false)

// ---------------- 埋点：停留时长（P4 契约 §2.1） ----------------
// 进入页面开始计时；切题/离开页面时结算一次。不足 1s 的停留不记（多半是误点）。
let enteredAt = Date.now()
let dwellSent = false

const sendDwell = (dwellProblemId: number) => {
  if (dwellSent) {
    return
  }
  const durationMs = Date.now() - enteredAt
  if (durationMs < 1000) {
    return
  }
  dwellSent = true
  track('dwell', { problemId: dwellProblemId, payload: { durationMs } })
}

const onPageHide = () => {
  sendDwell(problemId.value)
  flushOnHide()
}

// 切题：先用**切换前**的 id 结算上一题的停留，再重新计时。
// 直接用 problemId.value 会把 A 题的时长记到 B 题名下（复审 P13a）。
watch(problemId, (_current, previous) => {
  sendDwell(previous ?? problemId.value)
  enteredAt = Date.now()
  dwellSent = false
})

onMounted(() => window.addEventListener('pagehide', onPageHide))
onUnmounted(() => {
  window.removeEventListener('pagehide', onPageHide)
  sendDwell(problemId.value)
  flushOnHide()
})

const handleRun = () => {
  // 未输入测试用例
  if (inputList.value.length === 0) {
    Message.error({
      content: '请输入测试用例',
      duration: 5 * 1000,
    })
    return
  }

  loading.value = true

  doProblemRun({
    code: code.value,
    language: language.value,
    inputList: inputList.value.map(item => item.input),
    // 服务端据此把这次运行归到题目上（P4）
    problemId: problemId.value,
  }).then(res => {
    testResults.value = {
      inputList: inputList.value.map(item => item.input),
      ...res.data.data,
    }

    // 改变debug标签页与页高
    debugActive.value = '2'
    height.value = `${(pageHeight.value - 54) * 0.5}px`
  })

  loading.value = false
}

const handleSubmit = () => {
  loading.value = true

  doProblemSubmit({
    code: code.value,
    language: language.value,
    problemId: problemId.value,
  })
    .then((res) => {
      const submit = res.data.data
      submitResult.value = submit

      codeActive.value = '2'
      submitLogs.value.records.unshift(submit)
      // 提交只代表「已受理」，结果靠轮询拿，不要阻塞按钮
      startPolling(submit.id)
    })
    .finally(() => {
      loading.value = false
    })
}

// ---------------- 判题结果轮询（契约：0001-async-judge.md §10.1） ----------------
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollSeq = 0

const stopPolling = () => {
  pollSeq += 1
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

const applySubmitStatus = (status: SubmitStatusVo) => {
  submitResult.value = {
    ...submitResult.value,
    status: status.status,
    judgeInfo: status.judgeInfo,
    updateTime: status.updateTime,
  }
  const record = submitLogs.value.records.find(item => item.id === status.id)
  if (record) {
    record.status = status.status
    record.judgeInfo = status.judgeInfo
  }
}

const startPolling = (submitId: string) => {
  stopPolling()
  const seq = ++pollSeq
  const startedAt = Date.now()

  const schedule = () => {
    const elapsed = Date.now() - startedAt
    const delay
      = elapsed > SubmitPollConfig.slowAfterMs
        ? SubmitPollConfig.slowIntervalMs
        : SubmitPollConfig.intervalMs
    pollTimer = setTimeout(tick, delay)
  }

  const tick = () => {
    if (seq !== pollSeq) {
      return
    }
    if (Date.now() - startedAt > SubmitPollConfig.timeoutMs) {
      Message.warning({
        content: '判题超时，请稍后刷新页面查看结果',
        duration: 5000,
      })
      return
    }
    getSubmitStatus(submitId)
      .then((res) => {
        if (seq !== pollSeq) {
          return
        }
        const status = res.data.data
        applySubmitStatus(status)
        // 2/3/4 是终态，停止轮询
        if (status.status >= 2) {
          return
        }
        schedule()
      })
      .catch(() => {
        // 404/403/网络异常：停止轮询，避免持续打后端
        stopPolling()
      })
  }

  schedule()
}

onUnmounted(stopPolling)
</script>

<style scoped>
.common-button {
  background-color: #e7e7e7;
  padding: 0 12px;
  line-height: 1;
}

.common-button:hover {
  background-color: #e2e2e2;
}
</style>
