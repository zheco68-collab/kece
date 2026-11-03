import { http } from './request'
import type { PageResult, Submission, SubmissionRecord } from '@/types'

export interface SubmitPayload {
  questionId: number
  practiceCode?: string
  practiceQuestionId?: number
  language: string
  codeContent: Record<string, string>
}

export interface SubmissionListParams {
  userId?: number
  status?: string
  page?: number
  size?: number
}

export const submissionApi = {
  submit: (payload: SubmitPayload) => http.post<Submission>('/submissions', payload),
  get: (id: number | string) => http.get<Submission>(`/submissions/${id}`),
  list: (params?: SubmissionListParams) =>
    http.get<PageResult<SubmissionRecord>>('/submissions', { params }),
  /** 当前用户已通过的题目 id 列表。 */
  solved: () => http.get<number[]>('/submissions/solved'),
}
