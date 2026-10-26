import axios from 'axios'
import type { R } from '../common/types'

/**
 * 前端埋点（P4，契约 docs/design-docs/0004-learning-memory.md §2.1）。
 *
 * 只有浏览器知道的行为才走这里：查看题解、复制代码、页面停留。
 * run/submit/ac/wa 一律由服务端发射，客户端不能伪造。
 */
export type LearningEventType = 'view_solution' | 'copy_code' | 'dwell'

export interface LearningEventItem {
  eventId: string
  eventType: LearningEventType
  problemId?: number
  payload?: Record<string, number>
  clientTime?: number
}

export interface LearningContextVo {
  problem: {
    id: number
    title: string
    difficulty: string
    tags: string[]
  } | null
  episodes: {
    startedAt: string
    outcome: string
    durationMin: number
    attempts: number
    summary: string
  }[]
  patterns: {
    type: string
    description: string
    confidence: number
  }[]
  skills: unknown[]
  tokenEstimate: number
  truncated: boolean
}

/** 埋点端点：SDK 的 beacon 与 axios 上报共用这一个常量（复审 S12） */
export const LEARNING_EVENT_PATH = '/judge/learning/event'

export function reportLearningEvents(events: LearningEventItem[]) {
  return axios.post<R<number>>(LEARNING_EVENT_PATH, { events })
}

export function getLearningContext(problemId: number) {
  return axios.get<R<LearningContextVo>>('/judge/learning/context', {
    params: { problemId },
  })
}
