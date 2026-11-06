import { http } from './request'
import type { PageResult } from '@/types'

export type SolutionQuestionType = 'ALGO' | 'DB'
export type SolutionStatus = 'PUBLISHED' | 'HIDDEN'

export interface SolutionListItem {
  id: number
  questionType: SolutionQuestionType
  questionId: number
  questionTitle: string
  questionPublic: boolean
  authorId: number
  authorName: string
  title: string
  status: SolutionStatus
  version: number
  createdAt: string
  updatedAt: string
}

export interface SolutionDetail extends SolutionListItem {
  contentMd: string
  lastManagementReason: string | null
}

export interface CreateSolutionPayload {
  questionType: SolutionQuestionType
  questionId: number
  title: string
  contentMd: string
  clientRequestId: string
}

export interface UpdateSolutionPayload {
  title: string
  contentMd: string
  version: number
}

export interface UpdateSolutionStatusPayload {
  status: SolutionStatus
  version: number
  reason?: string
}

export interface SolutionListParams {
  questionType: SolutionQuestionType
  questionId: number
  page?: number
  size?: number
}

export interface AdminSolutionListParams {
  keyword?: string
  questionId?: number
  questionType?: SolutionQuestionType
  status?: SolutionStatus
  page?: number
  size?: number
}

export const solutionApi = {
  list(params: SolutionListParams) {
    return http.get<PageResult<SolutionListItem>>('/solutions', { params })
  },

  detail(id: number) {
    return http.get<SolutionDetail>(`/solutions/${id}`)
  },

  create(payload: CreateSolutionPayload) {
    return http.post<SolutionDetail>('/solutions', payload)
  },

  update(id: number, payload: UpdateSolutionPayload) {
    return http.put<SolutionDetail>(`/solutions/${id}`, payload)
  },

  remove(id: number, version: number) {
    return http.delete<void>(`/solutions/${id}`, { params: { version } })
  },

  listMine(params?: { page?: number; size?: number }) {
    return http.get<PageResult<SolutionListItem>>('/me/solutions', { params })
  },

  listAdmin(params?: AdminSolutionListParams) {
    return http.get<PageResult<SolutionListItem>>('/admin/solutions', { params })
  },

  detailAdmin(id: number) {
    return http.get<SolutionDetail>(`/admin/solutions/${id}`)
  },

  updateStatus(id: number, payload: UpdateSolutionStatusPayload) {
    return http.patch<SolutionDetail>(`/admin/solutions/${id}/status`, payload)
  },
}
