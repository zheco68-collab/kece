import { http } from './request'
import type { Difficulty, PageResult, Question, TestCase } from '@/types'

export interface QuestionListParams {
  keyword?: string
  difficulty?: string
  userStatus?: string
  page?: number
  size?: number
}

/** 算法题创建/更新请求。 */
export interface QuestionWriteRequest {
  title: string
  description: string
  difficulty: Difficulty
  timeLimit?: number
  memoryLimit?: number
  /** 支持语言，逗号分隔（如 "cpp,java,python"）。 */
  languages?: string
  /** 状态：1 公开 0 私有。 */
  status?: number
}

/** 测试用例创建/更新请求。 */
export interface TestCaseWriteRequest {
  input?: string | null
  expectedOutput: string
  caseNum?: number
  scoreWeight?: number
  /** 是否为样例用例：1 是 0 否。 */
  isSample?: number
}

export const questionApi = {
  list: (params: QuestionListParams) => http.get<PageResult<Question>>('/questions', { params }),
  detail: (id: number | string) => http.get<Question>(`/questions/${id}`),
  create: (body: QuestionWriteRequest) => http.post<Question>('/questions', body),
  update: (id: number | string, body: QuestionWriteRequest) =>
    http.put<Question>(`/questions/${id}`, body),
  remove: (id: number | string) => http.delete<void>(`/questions/${id}`),
  listTestCases: (questionId: number | string) =>
    http.get<TestCase[]>(`/questions/${questionId}/test-cases`),
  addTestCase: (questionId: number | string, body: TestCaseWriteRequest) =>
    http.post<TestCase>(`/questions/${questionId}/test-cases`, body),
  updateTestCase: (testCaseId: number | string, body: TestCaseWriteRequest) =>
    http.put<TestCase>(`/questions/test-cases/${testCaseId}`, body),
  removeTestCase: (testCaseId: number | string) =>
    http.delete<void>(`/questions/test-cases/${testCaseId}`),
  importTestCasesZip: (
    questionId: number | string,
    file: File,
    replace = false,
    firstAsSample = true
  ) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post<TestCase[]>(
      `/questions/${questionId}/test-cases/import-zip?replace=${replace}&firstAsSample=${firstAsSample}`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    )
  },
  exportTestCasesZipUrl: (questionId: number | string) =>
    `/api/v1/questions/${questionId}/test-cases/export-zip`,
  downloadInputUrl: (testCaseId: number | string) =>
    `/api/v1/questions/test-cases/${testCaseId}/input`,
  downloadOutputUrl: (testCaseId: number | string) =>
    `/api/v1/questions/test-cases/${testCaseId}/output`,
}
