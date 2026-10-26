<template>
  <a-card :style="{ borderRadius: '6px' }" :body-style="{ padding: '0px' }" class="w-full h-full">
    <a-tabs size="small" :active-key="infoActive" @change="newTab => infoActive = newTab">
      <a-tab-pane key="1">
        <template #title>
          <icon-file class="text-sm" />
          题目描述
        </template>
        <ProblemDescription :problem="problem" />
      </a-tab-pane>
      <a-tab-pane key="2">
        <template #title>
          <icon-experiment class="text-sm" />
          题解
        </template>
        <ProblemAnswer :answer="problem.answer" />
      </a-tab-pane>
      <a-tab-pane key="3">
        <template #title>
          <icon-history class="text-sm" />
          提交记录
        </template>
        <SubmitLog />
      </a-tab-pane>
    </a-tabs>
  </a-card>
</template>

<script setup lang="ts">
import type { SafeProblemVo } from '@/api/problem/types';
import { track } from '@/utils/track';
import { inject, ref, watch, type Ref } from 'vue';
import ProblemAnswer from './InfoCard/AnswerPanel.vue';
import ProblemDescription from './InfoCard/DescriptionPanel.vue';
import SubmitLog from './InfoCard/SubmitLog.vue';

const { problem } = defineProps<{
  problem: SafeProblemVo
}>()

const infoActive = inject<Ref<string | number>>('infoActive', ref('1'))

// 埋点：切到「题解」页签才算看题解（打开页面不等于看答案）
watch(infoActive, tab => {
  if (tab === '2') {
    track('view_solution', { problemId: problem?.id })
  }
})

</script>

<style scoped>
:deep(.arco-tabs-nav-tab) {
  padding: 0 4px;
  border-radius: 6px 6px 0 0;
  background: #fafafa;
  user-select: none;
}
</style>
