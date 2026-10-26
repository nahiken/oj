import axios from 'axios'
import { LEARNING_EVENT_PATH, reportLearningEvents } from '@/api/learning'
import type { LearningEventItem, LearningEventType } from '@/api/learning'

/**
 * 埋点队列（P4）。设计目标：埋点永远不能影响做题体验。
 *
 * - 批量：最多 20 条/请求，2s 窗口合并
 * - 离开页面：`pagehide` 用 `sendBeacon`，不阻塞卸载
 * - 失败静默：埋点丢了只影响记忆体，不能弹错误、不能阻塞提交
 */
const MAX_BATCH = 20
const FLUSH_DELAY_MS = 2000

let queue: LearningEventItem[] = []
let timer: ReturnType<typeof setTimeout> | null = null

function newEventId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `evt-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function track(
  eventType: LearningEventType,
  options: { problemId?: number, payload?: Record<string, number> } = {},
): void {
  queue.push({
    eventId: newEventId(),
    eventType,
    problemId: options.problemId,
    payload: options.payload,
    clientTime: Date.now(),
  })
  if (queue.length >= MAX_BATCH) {
    void flush()
  } else if (!timer) {
    timer = setTimeout(() => void flush(), FLUSH_DELAY_MS)
  }
}

async function flush(): Promise<void> {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
  if (queue.length === 0) {
    return
  }
  const batch = queue.splice(0, MAX_BATCH)
  try {
    await reportLearningEvents(batch)
  } catch {
    // 埋点失败不提示用户：记忆体是旁路，不能反噬主流程
  }
  if (queue.length > 0) {
    void flush()
  }
}

/**
 * 页面隐藏/卸载时兜底发送：`pagehide` 里 await 不可靠，只能 `sendBeacon`。
 *
 * <p>地址复用 axios 的 baseURL（与 interceptor 同源），dev 模式下 baseURL 为空，
 * beacon 打到 vite，发不出去也无所谓（只在本地开发发生）。
 */
export function flushOnHide(): void {
  if (queue.length === 0) {
    return
  }
  const batch = queue.splice(0, MAX_BATCH)
  if (typeof navigator === 'undefined' || !navigator.sendBeacon) {
    return
  }
  const base = axios.defaults.baseURL ?? ''
  const blob = new Blob([JSON.stringify({ events: batch })], {
    type: 'application/json',
  })
  navigator.sendBeacon(`${base}${LEARNING_EVENT_PATH}`, blob)
}
