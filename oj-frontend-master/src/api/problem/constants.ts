export const ExecuteCodeStatusEnum = {
  TIMEOUT: { msg: '超出时间限制', value: 3 },
  RUN_FAILED: { msg: '执行出错', value: 2 },
  COMPILE_FAILED: { msg: '编译失败', value: 1 },
  SUCCESS: { msg: '成功', value: 0 },
}

export const JudgeInfoEnum = {
  PENDING: { msg: '判题中', value: -1 },
  SUCCESS: { msg: '通过', value: 0 },
  COMPILE_FAILED: { msg: '编译失败', value: 1 },
  RUN_FAILED: { msg: '执行出错', value: 2 },
  TIMEOUT: { msg: '超出时间限制', value: 3 },
  WRONG_ANSWER: { msg: '解答错误', value: 4 },
}

export const JudgeInfoEnumMap: Map<number, string> = new Map([
  [-1, '判题中'],
  [0, '通过'],
  [1, '编译失败'],
  [2, '执行出错'],
  [3, '超出时间限制'],
  [4, '解答错误'],
])

/** 提交状态，与后端 ProblemSubmitStatusEnum 一致 */
export const SubmitStatusEnum = {
  WAITING: { msg: '排队中', value: 0 },
  RUNNING: { msg: '判题中', value: 1 },
  FAILED: { msg: '未通过', value: 2 },
  SUCCEED: { msg: '通过', value: 3 },
  CANCELED: { msg: '已取消', value: 4 },
}

/** 轮询相关参数，契约见 docs/design-docs/0001-async-judge.md §10.1 */
export const SubmitPollConfig = {
  intervalMs: 800,
  slowIntervalMs: 2000,
  slowAfterMs: 30000,
  timeoutMs: 120000,
}

export const ProblemStatusEnum = {
  ALL: '全部',
  SOLVED: '已通过',
  TRIED: '尝试过',
  NOLOG: '未开始',
}
