import { http } from './request'

export interface RankUser {
  rank: number
  userId: number
  username: string
  avatarUrl: string | null
  bio: string | null
  roles: string | null
  solvedCount: number
  totalSubmissions: number
  acSubmissions: number
  acceptRate: number | null
  totalScore: number | null
  lastAcceptedAt: string | null
}

export interface RankPageData {
  type: 'ALGO' | 'SQL' | 'OBJECTIVE' | string
  typeLabel: string
  total: number
  page: number
  size: number
  list: RankUser[]
  myRank: RankUser | null
}

export interface RankQueryParams {
  type?: 'ALGO' | 'SQL' | 'OBJECTIVE' | string
  page?: number
  size?: number
  keyword?: string
}

export const rankApi = {
  getRankings: (params?: RankQueryParams) =>
    http.get<RankPageData>('/rankings', { params }),
}
