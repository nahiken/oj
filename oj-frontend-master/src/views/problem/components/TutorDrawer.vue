<template>
  <a-button
    class="tutor-launcher"
    type="primary"
    shape="round"
    size="large"
    @click="open = true"
  >
    AI 辅导
  </a-button>

  <a-drawer
    v-model:visible="open"
    :width="440"
    class="p7-tutor-drawer"
    title="AI 辅导"
    :footer="false"
    unmount-on-close
    @close="onClose"
  >
    <div class="flex h-full min-h-0 flex-col">
      <div class="mb-2 flex items-center justify-between">
        <a-radio-group v-model="level" type="button" size="small" :disabled="streaming">
          <a-radio :value="1">思路</a-radio>
          <a-radio :value="2">伪代码</a-radio>
          <a-radio :value="3">局部代码</a-radio>
        </a-radio-group>
        <span class="text-xs text-gray-400">层级只由你切换</span>
      </div>

      <div ref="answerBox" class="tutor-answer min-h-0 flex-1 overflow-y-auto px-1">
        <div v-if="!answer && !streaming && !failure" class="tutor-empty">
          先说说你卡在哪一步，我会按你选的层级给提示。
        </div>

        <div v-if="answer" class="markdown" v-html="rendered" @click="onAnswerClick" />

        <div v-if="streaming" class="mt-2 flex items-center text-gray-400">
          <a-spin :size="14" />
          <span class="ml-2 text-xs">正在回答…</span>
        </div>

        <div v-if="notice" class="tutor-notice">{{ notice }}</div>

        <div v-if="failure" class="tutor-error">
          <div>{{ describeFailure(failure) }}</div>
          <div v-if="failure.status === 429 && secondsLeft > 0" class="mt-1 text-xs">
            还需等待 {{ secondsLeft }} 秒
          </div>
          <a-link v-if="failure.status === 401" class="mt-1 text-xs" @click="goLogin">
            去登录
          </a-link>
        </div>
      </div>

      <div class="tutor-meta mt-2 text-xs text-gray-400">
        <span v-if="meta?.skillsUsed?.length">经验 {{ meta.skillsUsed.length }} 条</span>
        <span v-if="meta?.searchUsed"> · 已参考相关题</span>
        <span v-if="meta && meta.searchUsed === false"> · 本次无相关题</span>
      </div>

      <div class="mt-2">
        <a-textarea
          v-model="draft"
          :auto-size="{ minRows: 2, maxRows: 4 }"
          :disabled="streaming"
          placeholder="例如：我卡在边界条件，不知道该怎么判断"
          @keydown.enter.exact="onEnter"
        />
      </div>

      <div class="mt-2 flex items-center justify-end space-x-2">
        <a-button v-if="streaming" @click="stopStream">停止</a-button>
        <a-button type="primary" :disabled="sendDisabled" @click="send">发送</a-button>
      </div>
    </div>
  </a-drawer>
</template>

<script lang="ts" setup>
import { describeFailure, type TutorHistoryItem, type TutorLevel } from '@/composables/useTutorStream'
import { useTutorStream } from '@/composables/useTutorStream'
import { Message } from '@arco-design/web-vue'
import hljs from 'highlight.js/lib/core'
import cpp from 'highlight.js/lib/languages/cpp'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import python from 'highlight.js/lib/languages/python'
import 'highlight.js/styles/github.css'
import { marked } from 'marked'
import { v4 as uuidv4 } from 'uuid'
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

hljs.registerLanguage('java', java)
hljs.registerLanguage('python', python)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('javascript', javascript)

const escapeHtml = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

// Raw HTML from the model is dropped: the tutor answer is untrusted text, and a
// `v-html` sink must not become an XSS hole. Markdown itself still renders.
marked.use({
  gfm: true,
  breaks: true,
  renderer: {
    html() {
      return ''
    },
    code(token) {
      const language = (token.lang || '').split(/\s+/)[0]
      const known = language && hljs.getLanguage(language)
      const body = known
        ? hljs.highlight(token.text, { language }).value
        : escapeHtml(token.text)
      const cls = known ? `hljs language-${language}` : 'hljs'
      return `<div class="tutor-code-wrap"><button class="tutor-copy" type="button">复制</button><pre class="tutor-code"><code class="${cls}">${body}</code></pre></div>`
    },
  },
})

const props = defineProps<{ problemId: number }>()

const router = useRouter()
const {
  answer,
  meta,
  finishReason,
  streaming,
  failure,
  start,
  stop: stopStream,
  reset,
} = useTutorStream()

const open = ref(false)
const level = ref<TutorLevel>(1)
const draft = ref('')
const history = ref<TutorHistoryItem[]>([])
const sessionId = ref(uuidv4())
const answerBox = ref<HTMLElement | null>(null)
const secondsLeft = ref(0)
let countdown: ReturnType<typeof setInterval> | null = null

// Incremental rendering: during a stream only the *new* complete blocks and the
// unfinished tail are parsed. Re-parsing the whole answer on every delta is what
// spec D2 calls "整段重渲染" and it also makes highlight.js re-run needlessly.
const rendered = ref('')
let stableHtml = ''
let stableCount = 0

function splitBlocks(text: string): { stable: string[], tail: string } {
  const blocks: string[] = []
  let current: string[] = []
  let inFence = false
  for (const line of text.split('\n')) {
    if (/^\s*```/.test(line)) {
      inFence = !inFence
    }
    if (!inFence && line.trim() === '') {
      if (current.length) {
        blocks.push(current.join('\n'))
        current = []
      }
      continue
    }
    current.push(line)
  }
  return { stable: blocks, tail: current.join('\n') }
}

function renderAnswer(text: string) {
  const { stable, tail } = splitBlocks(text)
  if (stable.length < stableCount) {
    // A new answer started: drop the cache instead of mixing two conversations.
    stableHtml = ''
    stableCount = 0
  }
  if (stable.length > stableCount) {
    stableHtml += stable.slice(stableCount).map(block => marked.parse(block) as string).join('')
    stableCount = stable.length
  }
  rendered.value = stableHtml + (tail ? (marked.parse(tail) as string) : '')
}

watch(answer, renderAnswer, { immediate: true })

const notice = computed(() => {
  if (finishReason.value === 'guarded') {
    return '回答停在了提示这一步：先说说你的思路，我不会直接给完整答案。'
  }
  if (finishReason.value === 'length') {
    return '回答达到长度上限，已被截断。可以把问题拆小一点再问。'
  }
  return ''
})

const sendDisabled = computed(
  () => streaming.value || secondsLeft.value > 0 || !draft.value.trim(),
)

// A new problem means a new conversation; the session id keeps multi-turn
// requests of one problem together in ai_tutoring_session.
watch(
  () => props.problemId,
  () => {
    stopStream()
    reset()
    history.value = []
    sessionId.value = uuidv4()
    draft.value = ''
  },
)

watch(answer, async () => {
  await nextTick()
  if (answerBox.value) {
    answerBox.value.scrollTop = answerBox.value.scrollHeight
  }
})

const clearCountdown = () => {
  if (countdown) {
    clearInterval(countdown)
    countdown = null
  }
  secondsLeft.value = 0
}

const startCountdown = (seconds: number) => {
  clearCountdown()
  if (seconds <= 0) {
    return
  }
  secondsLeft.value = seconds
  countdown = setInterval(() => {
    secondsLeft.value -= 1
    if (secondsLeft.value <= 0) {
      clearCountdown()
    }
  }, 1000)
}

const send = async () => {
  const message = draft.value.trim()
  if (sendDisabled.value) {
    return
  }
  clearCountdown()
  draft.value = ''

  // History is committed only after a successful turn. Pushing before the
  // request means a 429/401 retry sends the same user turn twice.
  await start({
    problemId: props.problemId,
    level: level.value,
    message,
    history: history.value,
    sessionId: sessionId.value,
  })

  if (failure.value) {
    if (failure.value.status === 429 && failure.value.retryAfter > 0) {
      startCountdown(failure.value.retryAfter)
    }
    // Keep the question in the box so a retry does not need retyping.
    draft.value = message
    if (failure.value.status === 401) {
      Message.warning({ content: '登录已过期，请重新登录', duration: 5000 })
    }
    return
  }

  history.value.push({ role: 'user', content: message })
  if (answer.value) {
    history.value.push({ role: 'assistant', content: answer.value })
  }
}

const onEnter = (event: KeyboardEvent) => {
  // Chinese IME confirms with Enter; sending then would cut the composition.
  if (event.isComposing) {
    return
  }
  event.preventDefault()
  send()
}

const onAnswerClick = (event: MouseEvent) => {
  const target = event.target as HTMLElement | null
  if (!target || !target.classList.contains('tutor-copy')) {
    return
  }
  const code = target.parentElement?.querySelector('code')?.textContent ?? ''
  navigator.clipboard?.writeText(code)
  Message.success({ content: '已复制', duration: 1500 })
}

const goLogin = () => {
  router.push('/login')
}

const onClose = () => {
  stopStream()
}

onUnmounted(() => {
  stopStream()
  clearCountdown()
})
</script>

<style scoped>
/* The drawer body must be a fixed-height column, otherwise the growing answer
   pushes the input row down and the 停止 button moves under the cursor. */
:global(.p7-tutor-drawer .arco-drawer-body) {
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
}

.tutor-launcher {
  position: fixed;
  right: 18px;
  bottom: 96px;
  z-index: 100;
}

.tutor-answer {
  min-height: 240px;
}

.tutor-empty {
  color: #86909c;
  font-size: 13px;
  padding: 24px 8px;
}

.tutor-meta {
  min-height: 16px;
}

.tutor-notice {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 4px;
  background: #fff7e8;
  color: #ad6800;
  font-size: 12px;
}

.tutor-error {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 4px;
  background: #ffece8;
  color: #cb2634;
  font-size: 13px;
}

.markdown :deep(pre.tutor-code) {
  position: relative;
  margin: 8px 0;
  padding: 10px 12px;
  overflow-x: auto;
  border-radius: 6px;
  background: #f6f8fa;
  font-size: 12px;
  line-height: 1.5;
}

.markdown :deep(.tutor-copy) {
  position: absolute;
  top: 4px;
  right: 6px;
  border: 0;
  background: transparent;
  color: #86909c;
  font-size: 11px;
  cursor: pointer;
}

.markdown :deep(p) {
  margin: 6px 0;
}

.markdown :deep(ul) {
  margin: 6px 0;
  padding-left: 18px;
  list-style: disc;
}
</style>
