import { http } from './request'

export interface ProblemSetSummary {
  id: number
  title: string
  subtitle?: string | null
  description?: string | null
  category: 'ALGO' | 'DB' | 'OBJECTIVE' | 'MIXED' | 'COURSE' | string
  difficulty: 'EASY' | 'MEDIUM' | 'HARD' | string
  tags: string[]
  itemCount: number
  algoCount: number
  dbCount: number
  objectiveCount: number
  participantCount: number
  isOfficial: number
  status: number
  version: number
  createdBy: number
  authorName: string
  createdAt: string
  updatedAt: string
  favorited: boolean
  solvedCount: number
  lastItemId?: number | null
}

export interface ProblemSetItemVO {
  id: number
  sectionId: number
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE' | string
  questionId: number
  title: string
  difficulty: string
  sourceAlive: boolean
  sortOrder: number
  solved: boolean
}

export type ProblemSetItem = ProblemSetItemVO

export interface ProblemSetSectionVO {
  id: number
  title: string
  description?: string | null
  sortOrder: number
  totalCount: number
  solvedCount: number
  items: ProblemSetItemVO[]
}

export interface ProblemSetDetail extends ProblemSetSummary {
  resumeItem?: ProblemSetItemVO | null
  sections: ProblemSetSectionVO[]
  flatItems: ProblemSetItemVO[]
}

export interface SaveProblemSetPayload {
  title: string
  subtitle?: string
  description?: string
  category: string
  difficulty: string
  tags?: string
  isOfficial?: number
  status?: number
  version?: number
  sections?: {
    title: string
    description?: string
    sortOrder?: number
    items: {
      questionType: string
      questionId: number
      title?: string
      difficulty?: string
      sortOrder?: number
    }[]
  }[]
  items?: {
    questionType: string
    questionId: number
    title?: string
    difficulty?: string
    sortOrder?: number
  }[]
}

export type SaveProblemSetRequest = SaveProblemSetPayload

export interface PageResult<T> {
  list: T[]
  items?: T[]
  total: number
  page: number
  size: number
}

export const problemSetApi = {
  listPublic(params: {
    category?: string
    difficulty?: string
    keyword?: string
    favoritedOnly?: boolean
    page?: number
    size?: number
  }) {
    return http.get<PageResult<ProblemSetSummary>>('/problem-sets', { params })
  },

  detail(id: number) {
    return http.get<ProblemSetDetail>(`/problem-sets/${id}`)
  },

  toggleFavorite(id: number) {
    return http.post<{ favorited: boolean; participantCount: number }>(
      `/problem-sets/${id}/favorite`,
    )
  },

  touch(id: number, itemId: number) {
    return http.post<void>(`/problem-sets/${id}/touch`, null, {
      params: { itemId },
    })
  },

  listAdmin(params: { category?: string; keyword?: string; page?: number; size?: number }) {
    return http.get<PageResult<ProblemSetSummary>>('/admin/problem-sets', { params })
  },

  create(payload: SaveProblemSetPayload) {
    return http.post<ProblemSetDetail>('/admin/problem-sets', payload)
  },

  update(id: number, payload: SaveProblemSetPayload) {
    return http.put<ProblemSetDetail>(`/admin/problem-sets/${id}`, payload)
  },

  remove(id: number) {
    return http.delete<void>(`/admin/problem-sets/${id}`)
  },
}

export const getProblemSetDetail = (id: number) => problemSetApi.detail(id)
export const getAdminProblemSetDetail = (id: number) => problemSetApi.detail(id)
export const listAdminProblemSets = (params: {
  category?: string
  keyword?: string
  page?: number
  size?: number
}) => problemSetApi.listAdmin(params)
export const createAdminProblemSet = (payload: SaveProblemSetPayload) =>
  problemSetApi.create(payload)
export const updateAdminProblemSet = (id: number, payload: SaveProblemSetPayload) =>
  problemSetApi.update(id, payload)
export const deleteAdminProblemSet = (id: number) => problemSetApi.remove(id)
