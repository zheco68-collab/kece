import { http } from './request'
import type {
  CreatePracticeRequest,
  PracticeDetail,
  PracticeObjectiveResult,
  PracticeOverview,
  PracticeScoreboard,
  PracticeSummary,
  StudentAuditDetail,
  UpdatePracticeRequest,
} from '@/types/practice'

export interface PracticeListPageResult {
  list: PracticeSummary[]
  total: number
  page: number
  size: number
}

export const practiceApi = {
  list: (params?: {
    keyword?: string
    status?: string
    joinedOnly?: boolean
    page?: number
    size?: number
  }) => http.get<PracticeListPageResult>('/practice', { params }),

  listAdmin: (params?: { keyword?: string; status?: string; page?: number; size?: number }) =>
    http.get<PracticeListPageResult>('/practice/admin', { params }),

  create: (data: CreatePracticeRequest) =>
    http.post<PracticeSummary>('/practice', data),

  update: (id: number, data: UpdatePracticeRequest) =>
    http.put<PracticeSummary>(`/practice/admin/${id}`, data),

  join: (accessCode: string, practiceId?: number) =>
    http.post<PracticeDetail>('/practice/join', {
      accessCode,
      ...(practiceId ? { practiceId } : {}),
    }),

  detail: (code: string) =>
    http.get<PracticeDetail>(`/practice/${code}`),

  scoreboard: (code: string) =>
    http.get<PracticeScoreboard>(`/practice/${code}/scoreboard`),

  overview: (id: number) =>
    http.get<PracticeOverview>(`/practice/admin/${id}/overview`),

  auditStudent: (id: number, targetUserId: number) =>
    http.get<StudentAuditDetail>(`/practice/admin/${id}/audit/${targetUserId}`),

  submitObjective: (code: string, answers: Record<string | number, string>) =>
    http.post<PracticeObjectiveResult>(`/practice/${code}/objective-submit`, { answers }),

  recordPractical: (
    code: string,
    data: {
      practiceQuestionId: number
      submissionId?: number | null
      questionType: 'ALGO' | 'DB'
      status: string
      score: number
    },
  ) => http.post<void>(`/practice/${code}/practical-record`, data),

  submit: (code: string) =>
    http.post<void>(`/practice/${code}/submit`),

  end: (code: string) =>
    http.post<void>(`/practice/${code}/end`),

  extend: (code: string, extraMinutes: number = 5) =>
    http.post<void>(`/practice/${code}/extend`, { extraMinutes }),

  remove: (id: number) =>
    http.delete<void>(`/practice/${id}`),

  exportCsv: async (id: number, accessCode?: string) => {
    const blob = await http.get<Blob>(`/practice/admin/${id}/export-csv`, {
      responseType: 'blob',
    })
    if (blob.type === 'application/json') {
      const text = await blob.text()
      let msg = '导出成绩单失败'
      try {
        const parsed = JSON.parse(text)
        msg = parsed?.message || msg
      } catch {
        // ignore
      }
      throw new Error(msg)
    }
    const blobUrl = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = blobUrl
    a.download = `practice_scores_${accessCode || id}_${Date.now()}.csv`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.URL.revokeObjectURL(blobUrl)
  },
}
