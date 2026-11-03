export interface PracticeQuestionItem {
  questionId: number
  questionType: 'OBJECTIVE' | 'ALGO' | 'DB'
  title?: string
  score?: number
  sortOrder?: number
}

export interface CreatePracticeRequest {
  title: string
  description?: string
  durationMinutes: number
  questions: PracticeQuestionItem[]
}

export interface UpdatePracticeRequest {
  title: string
  description?: string
  durationMinutes?: number
  questions?: PracticeQuestionItem[]
}

export interface PracticeSummary {
  id: number
  title: string
  description?: string
  accessCode?: string | null
  durationMinutes: number
  status: 'ACTIVE' | 'ENDED'
  teacherId: number
  teacherName: string
  memberCount: number
  questionCount: number
  objectiveCount: number
  practicalCount: number
  startTime: string
  endTime?: string
  myJoined: boolean
  myScore?: number
  mySubmitted?: boolean
  createdAt: string
}

export interface PracticeObjectiveQuestion {
  practiceQuestionId: number
  questionId: number
  type: 'SINGLE' | 'MULTIPLE' | 'FILL' | 'JUDGE'
  content: string
  options: string[]
  score: number
  sortOrder: number
  userAnswer?: string | null
  userScore?: number | null
  isCorrect?: boolean | null
  answered?: boolean
  correctAnswer?: string | null
}

export interface PracticePracticalQuestion {
  practiceQuestionId: number
  questionId: number
  questionType: 'ALGO' | 'DB'
  title: string
  score: number
  sortOrder: number
  difficulty?: string
  timeLimitMs?: number
  memoryLimitKb?: number
  languages?: string[]
  userStatus: 'ACCEPTED' | 'WRONG_ANSWER' | 'PENDING' | 'UNATTEMPTED' | 'PARTIAL' | string
  userScore: number
  submissionId?: number | null
}

export interface PracticeDetail {
  id: number
  title: string
  description?: string
  accessCode: string
  durationMinutes: number
  status: 'ACTIVE' | 'ENDED'
  teacherId: number
  teacherName: string
  memberCount: number
  startTime: string
  endTime?: string
  remainingSeconds?: number | null
  canManage?: boolean
  myJoined: boolean
  myScore: number
  mySolvedCount: number
  mySubmitted: boolean
  objectiveQuestions: PracticeObjectiveQuestion[]
  practicalQuestions: PracticePracticalQuestion[]
}

export interface PracticeObjectiveItemResult {
  practiceQuestionId: number
  questionId: number
  correct: boolean
  status?: 'ACCEPTED' | 'PARTIAL' | 'WRONG_ANSWER' | string
  score: number
  fullScore: number
  userAnswer: string
  correctAnswer?: string | null
}

export interface PracticeObjectiveResult {
  totalScore: number
  fullScore: number
  correctCount: number
  totalCount: number
  myTotalScore?: number
  mySolvedCount?: number
  items: PracticeObjectiveItemResult[]
}

// 实时成绩榜 Scoreboard 结构
export interface ScoreboardQuestionHeader {
  practiceQuestionId: number
  questionId: number
  questionType: 'OBJECTIVE' | 'ALGO' | 'DB'
  title: string
  score: number
  sortOrder: number
}

export interface ScoreboardBenchmark {
  practiceQuestionId: number
  submitUserCount: number
  passUserCount: number
  passRate: number
}

export interface ScoreboardCellState {
  practiceQuestionId: number
  status: 'ACCEPTED' | 'PARTIAL' | 'WRONG_ANSWER' | 'UNATTEMPTED' | string
  score: number
  attempts: number
  elapsedMinutes: number
}

export interface ScoreboardRankRow {
  rank: number
  userId: number
  username: string
  nickname: string
  totalScore: number
  solvedCount: number
  penaltyTime: number
  submitted: boolean
  lastSubmitTime?: string
  isCurrentUser: boolean
  cellStates: ScoreboardCellState[]
}

export interface PracticeScoreboard {
  practiceId: number
  title: string
  accessCode: string
  status: 'ACTIVE' | 'ENDED'
  remainingSeconds?: number | null
  questionHeaders: ScoreboardQuestionHeader[]
  benchmarks: ScoreboardBenchmark[]
  rows: ScoreboardRankRow[]
}

// 教师端考务总览大盘 Overview 结构
export interface OverviewMetricsCard {
  joinedCount: number
  submittedCount: number
  submitRate: number
  avgScore: number
  maxScore: number
  minScore: number
  totalSubmissions: number
  perfectScoreCount: number
  activeJudgingCount: number
  passCount: number
  passRate: number
}

export interface OverviewQuestionAccuracy {
  practiceQuestionId: number
  questionId: number
  questionType: string
  subType?: string
  title: string
  fullScore: number
  submitCount: number
  passCount: number
  passRate: number
}

export interface OverviewStudentRoster {
  userId: number
  username: string
  nickname: string
  totalScore: number
  solvedCount: number
  submitted: boolean
  joinedAt: string
  submittedAt?: string
  lastActiveAt?: string
}

export interface PracticeOverview {
  practiceId: number
  title: string
  description?: string
  accessCode: string
  durationMinutes: number
  status: 'ACTIVE' | 'ENDED'
  teacherId: number
  teacherName: string
  startTime: string
  endTime?: string
  remainingSeconds?: number | null
  metrics: OverviewMetricsCard
  questionAccuracies: OverviewQuestionAccuracy[]
  students: OverviewStudentRoster[]
}

// 调阅学生作答全息审计结构
export interface StudentAuditQuestionItem {
  practiceQuestionId: number
  questionId: number
  questionType: 'OBJECTIVE' | 'ALGO' | 'DB'
  title: string
  fullScore: number
  earnedScore: number
  status: string
  objectiveType?: string
  objectiveContent?: string
  objectiveOptions?: string[]
  userAnswer?: string
  correctAnswer?: string
  submissionId?: number
  code?: string
  language?: string
  timeMs?: number
  memoryKb?: number
  judgeResultJson?: string
  submittedAt?: string
}

export interface StudentAuditDetail {
  userId: number
  username: string
  nickname: string
  totalScore: number
  solvedCount: number
  submitted: boolean
  joinedAt: string
  submittedAt?: string
  items: StudentAuditQuestionItem[]
}
