import { http } from './request'

export interface DemoSnippet {
  filename: string
  langBadge: string
  code: string
  sourcePath?: string
  updatedAt?: number
}

export interface PoolSummary {
  pool: string
  name: string
  state: 'READY' | 'BUSY' | 'OFFLINE' | 'DRAINING' | 'PENDING_SWITCH' | string
  readyNodes: number
  totalNodes: number
  pendingJobs: number
  activeJobs: number
}

export interface JudgeClusterSummary {
  asOf: number
  freshness: 'FRESH' | 'STALE' | 'DEGRADED' | string
  readyPools: number
  totalPools: number
  pools: PoolSummary[]
}

export const systemApi = {
  getClusterSummary: () => http.get<JudgeClusterSummary>('/system/judgers/summary'),
  getDemoSnippet: () => http.get<DemoSnippet>('/system/demo-snippet'),
}

