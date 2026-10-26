<template>
  <div>
    <a-table
      class="cursor-pointer"
      :pagination="{
        total: submitLogs.total,
        current: submitLogs.current,
        pageSize: submitLogs.size,
      }"
      @page-change="
        page => {
          fetchProblemSubmitPage(page)
        }
      "
      @row-click="
        row => {
          submitResult = row as ProblemSubmitVo
          codeActive = '2'
        }
      "
      :bordered="false"
      :columns="columns"
      :data="submitLogs.records"
      :hoverable="false"
      :stripe="true"
    >
      <template #cursor="{ record }">
        <div v-if="record.id === submitResult.id" class="text-gray-600 text-lg">
          <icon-font type="icon-chakan" />
        </div>
      </template>

      <template #status="{ record }">
        <div class="flex flex-col items-start">
          <div :class="statusClass(record)">
            {{ statusText(record) }}
          </div>

          <div class="text-gray-500 text-xs">
            {{ dayjs(record.createTime).format('YYYY.MM.DD') }}
          </div>
        </div>
      </template>

      <template #language="{ record }">
        <div class="flex flex-shrink-0">
          <div
            class="bg-gray-200 text-xs text-gray-500 rounded-xl px-2 py-[2px]"
          >
            {{ record.language }}
          </div>
        </div>
      </template>

      <template #time="{ record }">
        <div class="flex flex-shrink-0 text-gray-500 items-center gap-1">
          <icon-clock-circle class="text-base" />
          <span>
            {{ record.judgeInfo.time ? `${record.judgeInfo.time} ms` : 'N/A' }}
          </span>
        </div>
      </template>

      <template #memory="{ record }">
        <div class="flex flex-shrink-0 text-gray-500 items-center gap-1">
          <icon-font type="icon-neicun" class="text-base" />
          <span>
            {{
              record.judgeInfo.memory
                ? `${(record.judgeInfo.memory / 1024 / 1024).toFixed(1)} MB`
                : 'N/A'
            }}
          </span>
        </div>
      </template>
    </a-table>
  </div>
</template>

<script lang="ts" setup>
import type { Page } from '@/api/common/types'
import { listProblemSubmitVoByPage } from '@/api/problem'
import { JudgeInfoEnum, JudgeInfoEnumMap } from '@/api/problem/constants'
import type { ProblemSubmitVo } from '@/api/problem/types'
import IconFont from '@/utils/iconfont'
import type { TableColumnData } from '@arco-design/web-vue'
import dayjs from 'dayjs'
import { computed, inject, ref, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'

/** 等待期后端返回 -1；历史数据可能没有 judgeInfo，统一按「判题中」兜底 */
const statusText = (record: ProblemSubmitVo) => {
  const status = record.judgeInfo?.status
  if (status === undefined || status === null) {
    return '判题中'
  }
  return JudgeInfoEnumMap.get(status) ?? '未知'
}

const statusClass = (record: ProblemSubmitVo) => {
  const status = record.judgeInfo?.status
  if (status === JudgeInfoEnum.SUCCESS.value) {
    return 'text-green-500'
  }
  if (
    status === undefined
    || status === null
    || status === JudgeInfoEnum.PENDING.value
  ) {
    return 'text-gray-500'
  }
  return 'text-red-500'
}

const route = useRoute()
const problemId = computed(() => Number(route.params.problemId))

const columns: TableColumnData[] = [
  {
    title: '',
    slotName: 'cursor',
    align: 'center',
    width: 44,
  },
  {
    title: '状态',
    slotName: 'status',
  },
  {
    title: '编程语言',
    slotName: 'language',
  },
  {
    title: '执行用时',
    slotName: 'time',
  },
  {
    title: '消耗内存',
    slotName: 'memory',
  },
]

const submitResult = inject<Ref<ProblemSubmitVo>>(
  'submitResult',
  ref({} as ProblemSubmitVo),
)

const codeActive = inject<Ref<string>>('codeActive', ref('1'))
const submitLogs = inject<Ref<Page<ProblemSubmitVo>>>(
  'submitLogs',
  ref({} as Page<ProblemSubmitVo>),
)

const fetchProblemSubmitPage = (current: number) => {
  listProblemSubmitVoByPage({
    current,
    problemId: problemId.value,
  }).then(res => {
    submitLogs.value = res.data.data
  })
}

watch(
  () => problemId.value,
  () => {
    console.log('拉取提交历史')

    fetchProblemSubmitPage(1)
  },
  { immediate: true },
)
</script>

<style scoped>
:deep(.arco-table .arco-table-cell) {
  padding: 4px 0px;
}

:deep(.arco-table-th) {
  color: #6b7280;
}
</style>
