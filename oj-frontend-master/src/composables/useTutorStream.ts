/**
 * SSE consumer for `/api/ai/chat`.
 *
 * Kept out of the drawer so the component only renders state: the transport
 * details (frame buffering, abort, Retry-After) are the parts that break, and
 * they are easier to reason about in one place.
 *
 * The response is a stream of `event:` / `data:` frames. A frame can be split
 * across network chunks, so parsing waits for the blank-line terminator.
 */

import { ref, type Ref } from 'vue'

export type TutorLevel = 1 | 2 | 3

export interface TutorHistoryItem {
  role: 'user' | 'assistant'
  content: string
}

export interface TutorChatBody {
  problemId: number
  level: TutorLevel
  message: string
  history: TutorHistoryItem[]
  sessionId: string
  mode?: string
}

export interface TutorFailure {
  status: number
  code: string
  message: string
  retryAfter: number
}

export interface TutorMeta {
  provider?: string
  skillsUsed?: string[]
  searchUsed?: boolean
  summarySource?: string
  promptTokens?: number
  contextTokens?: number
  historyTrimmed?: boolean
  budget?: Record<string, number>
  [key: string]: unknown
}

export function describeFailure(failure: TutorFailure): string {
  if (failure.status === 401) {
    return '登录已过期，请重新登录'
  }
  if (failure.status === 429) {
    return '提问太频繁了，请稍后再试'
  }
  if (failure.code === 'DISABLED') {
    return 'AI 辅导当前已关闭'
  }
  if (failure.code === 'CONTEXT_UNAVAILABLE') {
    return '学习上下文暂时不可用，本次辅导无法开始'
  }
  if (failure.code === 'PROVIDER_UNAVAILABLE' || failure.code === 'AI_UNAVAILABLE') {
    return '辅导服务暂不可用，请稍后再试'
  }
  if (failure.code === 'NETWORK') {
    return '网络异常，请检查连接后重试'
  }
  return failure.message || '辅导请求失败'
}

async function readFailure(response: Response): Promise<TutorFailure> {
  let code = ''
  let message = ''
  try {
    const body = await response.json()
    code = String(body?.code ?? '')
    // Gateway replies use `msg`, the AI service uses `message`.
    message = String(body?.message ?? body?.msg ?? '')
  } catch {
    code = ''
  }
  const retryAfter = Number(response.headers.get('Retry-After') ?? 0)
  return {
    status: response.status,
    code,
    message,
    retryAfter: Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : 0,
  }
}

interface Frame {
  event?: string
  data?: string
}

function parseFrame(block: string): Frame {
  let event: string | undefined
  let data: string | undefined
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      data = line.slice('data:'.length).trim()
    }
  }
  return { event, data }
}

export function useTutorStream() {
  const answer = ref('')
  const meta: Ref<TutorMeta | null> = ref(null)
  const finishReason = ref('')
  const streaming = ref(false)
  const failure: Ref<TutorFailure | null> = ref(null)
  let controller: AbortController | null = null

  const reset = () => {
    answer.value = ''
    meta.value = null
    finishReason.value = ''
    failure.value = null
  }

  const stop = () => {
    controller?.abort()
    controller = null
    streaming.value = false
  }

  const start = async (body: TutorChatBody) => {
    reset()
    streaming.value = true
    controller = new AbortController()

    try {
      const response = await fetch('/api/ai/chat', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      })

      if (!response.ok) {
        failure.value = await readFailure(response)
        return
      }
      if (!response.body) {
        failure.value = { status: 0, code: 'NO_STREAM', message: '当前浏览器不支持流式响应', retryAfter: 0 }
        return
      }
      // The gateway can reject before the AI service is reached, and its refusal
      // is a JSON envelope with HTTP 200. Treating that as a stream would end in
      // silence, which is exactly the failure mode the drawer must not have.
      const contentType = response.headers.get('Content-Type') ?? ''
      if (!contentType.includes('text/event-stream')) {
        const envelope = await response.json().catch(() => null)
        const code = String(envelope?.code ?? 'UNEXPECTED_RESPONSE')
        failure.value = {
          status: Number(code) || response.status,
          code,
          message: String(envelope?.msg ?? envelope?.message ?? ''),
          retryAfter: 0,
        }
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          const block = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          handleFrame(parseFrame(block))
          boundary = buffer.indexOf('\n\n')
        }
      }
    } catch (error) {
      if ((error as Error).name !== 'AbortError') {
        failure.value = { status: 0, code: 'NETWORK', message: (error as Error).message, retryAfter: 0 }
      }
    } finally {
      streaming.value = false
      controller = null
    }
  }

  const handleFrame = (frame: Frame) => {
    if (!frame.event || !frame.data) {
      return
    }
    let payload: Record<string, unknown>
    try {
      payload = JSON.parse(frame.data)
    } catch {
      return
    }
    if (frame.event === 'meta') {
      meta.value = payload as TutorMeta
      // D3: the meta fields must be inspectable without guessing. Console only —
      // the drawer shows the two fields a student cares about.
      console.debug('[tutor] meta', payload)
    } else if (frame.event === 'delta') {
      answer.value += String(payload.text ?? '')
    } else if (frame.event === 'done') {
      finishReason.value = String(payload.finishReason ?? '')
    } else if (frame.event === 'error') {
      failure.value = {
        status: 0,
        code: String(payload.code ?? 'STREAM_ERROR'),
        message: String(payload.message ?? ''),
        retryAfter: 0,
      }
    }
  }

  return { answer, meta, finishReason, streaming, failure, start, stop, reset }
}
