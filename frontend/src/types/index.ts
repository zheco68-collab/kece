export type Role = 'USER' | 'TEACHER' | 'ADMIN'
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type ObjectiveType = 'SINGLE' | 'MULTIPLE' | 'FILL' | 'JUDGE'
export type MatchType = 'EXACT' | 'IGNORE_CASE' | 'REGEX'
export type DbQuestionType = 'SELECT' | 'DML'

export interface UserInfo {
  id: number
  username: string
  email: string | null
  avatarUrl?: string | null
  bio?: string | null
  githubUrl?: string | null
  roles: string[]
}

export interface AuthResult {
  token: string
  refreshToken: string
  user: UserInfo
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface TestCase {
  id: number
  questionId?: number
  caseNum?: number
  inputPath?: string | null
  outputPath?: string | null
  inputSize?: number
  outputSize?: number
  scoreWeight?: number
  input: string | null
  expectedOutput: string
  isSample: number
  isLarge?: boolean
}

export interface Question {
  id: number
  title: string
  description: string
  difficulty: Difficulty
  timeLimit: number | null
  memoryLimit: number | null
  languages: string[]
  status: number
  createdBy: number
  authorName?: string | null
  authorAvatarUrl?: string | null
  sampleTestCases: TestCase[]
  createdAt: string
  updatedAt: string
}

export interface ObjectiveQuestion {
  id: number
  type: ObjectiveType
  content: string
  options: string[]
  answer: string | null
  matchType: MatchType | null
  score: number
  status: number
  createdBy: number
  authorName?: string | null
  authorAvatarUrl?: string | null
  createdAt: string
  updatedAt: string
}

export interface AnswerResult {
  correct: boolean
  score: number
  fullScore: number
  correctAnswer: string
}

/** 数据库测试点：一题多套数据，表结构与标准答案在题目上。 */
export interface DbTestCase {
  id?: number
  caseOrder: number
  seedSql: string
  /** 期望输出结果集（JSON/文本），仅公开样例对普通用户可见 */
  expectedOutput?: string | null
  /** 0 非样例 / 1 样例 */
  isSample: number
}

export interface DbQuestion {
  id: number
  title: string
  description: string
  dialect: string
  type: DbQuestionType
  /** 建表语句，所有测试点共用，对所有人可见。 */
  schemaSql: string
  /** 标准答案，仅出题人与管理员可见，普通用户为 null。 */
  referenceSql: string | null
  /** DML 判分用的校验查询，仅出题人与管理员可见。 */
  verifySql: string | null
  orderSensitive: boolean
  status: number
  createdBy: number
  authorName?: string | null
  authorAvatarUrl?: string | null
  testCases: DbTestCase[]
  createdAt: string
  updatedAt: string
}

export interface DbSubmissionRecord {
  submissionId: number
  questionId: number
  questionTitle: string
  status: SubmissionStatus
  score: number | null
  timeUsedMs: number | null
  sqlContent: string
  createdAt: string
}

export type SubmissionStatus =
  | 'PENDING'
  | 'COMPILING'
  | 'JUDGING'
  | 'AI_REVIEWING'
  | 'ACCEPTED'
  | 'WRONG_ANSWER'
  | 'TIME_LIMIT_EXCEEDED'
  | 'MEMORY_LIMIT_EXCEEDED'
  | 'RUNTIME_ERROR'
  | 'COMPILE_ERROR'
  | 'SYSTEM_ERROR'

export interface SubmissionProgress {
  totalCases: number
  finishedCases: number
}

export interface TestCaseReport {
  caseId: number
  status: string
  timeMs: number | null
  memoryKb: number | null
  expectedOutput: string | null
  actualOutput: string | null
}

export interface SubmissionResult {
  score: number | null
  timeUsedMs: number | null
  memoryUsedKb: number | null
  compileErrorLog: string | null
  testCasesReport: TestCaseReport[]
}

export interface Submission {
  submissionId: number
  status: SubmissionStatus
  progress: SubmissionProgress | null
  result: SubmissionResult | null
}

export interface SubmissionRecord {
  submissionId: number
  questionId: number
  questionTitle: string
  language: string
  codeContent: string
  status: SubmissionStatus
  score: number | null
  timeUsedMs: number | null
  memoryUsedKb: number | null
  createdAt: string
}

/** 客观题作答记录。客观题只有一个「测试点」，对错即 AC/WA。 */
export interface ObjectiveSubmissionRecord {
  submissionId: number
  questionId: number
  questionContent: string
  type: ObjectiveType
  answer: string
  /** 1 答对 / 0 答错 */
  correct: number
  correctAnswer: string
  score: number | null
  fullScore: number | null
  createdAt: string
}
