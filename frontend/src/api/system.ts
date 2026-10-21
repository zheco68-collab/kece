import { http } from './request'

export interface JudgerNode {
  id: string
  name: string
  type: 'ALGO' | 'DATABASE' | 'FRONTEND_AI' | string
  typeLabel: string
  status: 'IDLE' | 'JUDGING' | 'OFFLINE'
  statusLabel: string
  currentTasks: number
  maxConcurrency: number
  lastHeartbeatTime: number | null
  details: string
}

export interface ClusterStatus {
  totalNodes: number
  onlineNodes: number
  busyNodes: number
  idleNodes: number
  offlineNodes: number
  nodes: JudgerNode[]
  timestamp: number
}

export const systemApi = {
  getClusterStatus: () => http.get<ClusterStatus>('/system/judgers'),
}
