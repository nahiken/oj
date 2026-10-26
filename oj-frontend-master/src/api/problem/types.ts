import type { PageReq } from '../common/types'

export interface JudgeCase {
  input: string
  output: string
}

export interface JudgeConfig {
  timeLimit: number
  memoryLimit: number
}

export interface ProblemVo {
  id: number
  title: string
  content: string
  difficulty: string
  tags: string[]
  answer: string
  submitNum: number
  acceptedNum: number
  judgeCase: JudgeCase[]
  judgeConfig: JudgeConfig
  thumbNum: number
  favourNum: number
  userId: number
  createTime: string
  updateTime: string
}

export interface SafeProblemVo {
  id: number
  title: string
  content: string
  status: string
  difficulty: string
  tags: string[]
  answer: string
  submitNum: number
  acceptedNum: number
  judgeCase: string[]
  judgeConfig: JudgeConfig
  thumbNum: number
  favourNum: number
  createTime: string
  updateTime: string
}

export interface ProblemAddReq {
  title: string
  content: string
  difficulty: string
  tags: string[]
  answer: string
  judgeCase: JudgeCase[]
  judgeConfig: JudgeConfig
}

export interface ProblemUpdateReq {
  id: number
  title: string
  content: string
  difficulty: string
  tags: string[]
  answer: string
  judgeCase: JudgeCase[]
  judgeConfig: JudgeConfig
}

export interface ProblemQueryReq extends PageReq {
  tags?: string[]
  status?: string
  difficulty?: string
  keyword?: string
}

export interface ExecuteResult {
  exitCode: number
  stdout: string
  stderr: string
  time: number
  memory: number
}

export interface ExecuteCodeRes {
  code: number
  msg: string
  results: ExecuteResult[]
}

export interface TestResults extends ExecuteCodeRes {
  inputList: string[]
}

export interface ExecuteCodeReq {
  inputList: string[]
  code: string
  language: string
  /** 题目 id（P4）：服务端用它归属学习事件 */
  problemId?: number
}

export interface ProblemSubmitAddReq {
  language: string
  code: string
  problemId: number
}

export interface JudgeInfo {
  status: number
  msg: string
  pass: number
  total: number
  memory: number
  time: number
  input: string
  output: string
  expectedOutput: string
}

export interface ProblemSubmitVo {
  /** 雪花 ID 按字符串返回，避免 JS 精度丢失 */
  id: string
  language: string
  code: string
  judgeInfo: JudgeInfo
  status: number
  problemId: number
  userId: number
  createTime: string
  updateTime: string
}

export interface ProblemSubmitQueryReq extends PageReq {
  problemId?: number
}

/** 轮询专用状态，契约见 docs/design-docs/0001-async-judge.md §9.2 */
export interface SubmitStatusVo {
  /** 雪花 ID 按字符串返回，避免 JS 精度丢失 */
  id: string
  status: number
  judgeInfo: JudgeInfo
  version: number
  updateTime: string
}

export interface SubmitSummaryVo {
  easyPass: number
  easyTotal: number
  mediumPass: number
  mediumTotal: number
  hardPass: number
  hardTotal: number
  submitCount: number
  passCount: number
}
