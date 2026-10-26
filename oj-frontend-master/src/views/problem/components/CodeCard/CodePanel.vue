<template>
  <div class="p-1">
    <LangSelect />

    <div class="float-right">
      <a-tooltip content="还原到默认代码模板" position="tr" mini>
        <a-button @click="resetCode" class="!w-6 !h-6 mr-1" type="text">
          <template #icon>
            <icon-refresh />
          </template>
        </a-button>
      </a-tooltip>

      <a-tooltip content="保存" position="tr" mini>
        <a-button @click="saveCode" class="!w-6 !h-6" type="text">
          <template #icon>
            <icon-save />
          </template>
        </a-button>
      </a-tooltip>
    </div>
  </div>

  <DividerLine />

  <div class="pb-1 px-1" :style="{ height: 'calc(100% - 32px)' }">
    <MonacoEditor />
  </div>
</template>

<script setup lang="ts">
import DividerLine from '@/components/common/DividerLine.vue'
import { track } from '@/utils/track'
import { Message } from '@arco-design/web-vue'
import { inject, onMounted, onUnmounted, type Ref } from 'vue'
import LangSelect from './CodePanel/LangSelect.vue'
import MonacoEditor from './CodePanel/MonacoEditor.vue'

const problemId = inject<Ref<number> | undefined>('problemId', undefined)

// 埋点：复制代码。监听文档级 copy，但只认「选中内容落在代码编辑器里」的复制：
// 复制题面/输入用例不该算「抄走了代码」（复审 P10）。
const onCopy = () => {
  const selection = document.getSelection()
  const text = selection?.toString() ?? ''
  if (text.length === 0) {
    return
  }
  const anchor = selection?.anchorNode
  const element = anchor?.nodeType === Node.TEXT_NODE ? anchor.parentElement : (anchor as Element | null)
  if (!element?.closest('.monaco-editor')) {
    return
  }
  track('copy_code', { problemId: problemId?.value, payload: { chars: text.length } })
}

onMounted(() => document.addEventListener('copy', onCopy))
onUnmounted(() => document.removeEventListener('copy', onCopy))

const resetCode = () => {
  Message.info('暂未实现')
}

const saveCode = () => {
  Message.info('暂未实现')
}
</script>
