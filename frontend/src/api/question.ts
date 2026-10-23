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

export interface TestCaseSummaryVO {
  totalCount: number
  sampleCount: number
  totalInputBytes: number
  totalOutputBytes: number
  totalBytes: number
}

export const questionApi = {
  list: (params: QuestionListParams) => http.get<PageResult<Question>>('/questions', { params }),
  detail: (id: number | string) => http.get<Question>(`/questions/${id}`),
  create: (body: QuestionWriteRequest) => http.post<Question>('/questions', body),
  update: (id: number | string, body: QuestionWriteRequest) =>
    http.put<Question>(`/questions/${id}`, body),
  remove: (id: number | string) => http.delete<void>(`/questions/${id}`),
  getTestCaseSummary: (questionId: number | string) =>
    http.get<TestCaseSummaryVO>(`/questions/${questionId}/test-cases/summary`),
  listTestCases: (questionId: number | string) =>
    http.get<TestCase[]>(`/questions/${questionId}/test-cases`),
  addTestCase: (questionId: number | string, body: TestCaseWriteRequest) =>
    http.post<TestCase>(`/questions/${questionId}/test-cases`, body),
  updateTestCase: (testCaseId: number | string, body: TestCaseWriteRequest) =>
    http.put<TestCase>(`/questions/test-cases/${testCaseId}`, body),
  toggleTestCaseSample: (questionId: number | string, testCaseId: number | string, isSample: boolean) =>
    http.put<void>(`/questions/${questionId}/test-cases/${testCaseId}/sample?isSample=${isSample}`),
  setSampleCaseIds: (questionId: number | string, sampleCaseIds: number[]) =>
    http.put<void>(`/questions/${questionId}/test-cases/sample-ids`, sampleCaseIds),
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
  exportTestCasesZipUrl: (questionId: number | string) => {
    let url = `/api/v1/questions/${questionId}/test-cases/export-zip`
    const token = localStorage.getItem('token')
    if (token) {
      url += `?token=${encodeURIComponent(token)}`
    }
    return url
  },
  exportTestCasesZip: async (questionId: number | string) => {
    const token = localStorage.getItem('token')
    const url = `/api/v1/questions/${questionId}/test-cases/export-zip${token ? `?token=${encodeURIComponent(token)}` : ''}`
    const response = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        throw new Error('未登录或登录已过期，请重新登录')
      }
      throw new Error('测试用例 Zip 导出失败')
    }
    const blob = await response.blob()
    const blobUrl = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = blobUrl
    a.download = `testcases_q${questionId}.zip`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.URL.revokeObjectURL(blobUrl)
  },
  downloadInputUrl: (testCaseId: number | string) => {
    let url = `/api/v1/questions/test-cases/${testCaseId}/input`
    const token = localStorage.getItem('token')
    if (token) url += `?token=${encodeURIComponent(token)}`
    return url
  },
  downloadOutputUrl: (testCaseId: number | string) => {
    let url = `/api/v1/questions/test-cases/${testCaseId}/output`
    const token = localStorage.getItem('token')
    if (token) url += `?token=${encodeURIComponent(token)}`
    return url
  },
}
