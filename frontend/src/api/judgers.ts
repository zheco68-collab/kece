import { http } from './request'

export interface HostGroupView {
  id: string
  name: string
  maxSlots: number
  revision: number
}

export interface JudgeNodeView {
  nodeId: string
  hostGroupId: string
  name: string
  pool: string
  runtimeSpec: string
  baseUrl: string | null
  state: 'PENDING_VERIFICATION' | 'ENABLED' | 'DRAINING' | 'DISABLED' | string
  health: 'HEALTHY' | 'DEGRADED' | 'OFFLINE' | 'UNVERIFIED' | 'UNHEALTHY' | string
  maxSlots: number
  localSlots: number
  running: number
  revision: number
  runtimeVersion: string | null
  heartbeatAt: string | null
}

export interface JudgeCheckView {
  checkId: string
  state: string
  runtimeVersion: string
  detail: string | null
}

export interface JudgeAuditView {
  actorId: string
  action: string
  revision: number
  at: string
}

export interface JudgeAttemptView {
  jobId: number
  leaseEpoch: number
  outcome: string | null
  startedAt: string
  endedAt: string | null
}

export interface JudgeNodeDetail {
  node: JudgeNodeView
  checks: JudgeCheckView[]
  audit: JudgeAuditView[]
  attempts: JudgeAttemptView[]
}

export interface JudgeConnectionView {
  connectionId: string
  apiUrl: string
  nodeId: string | null
  status: string
  errorCode: string | null
  errorDetail: string | null
  retryCount: number
  createdAt: string
  updatedAt: string
}

export interface UpdateHostGroupRequest {
  revision: number
  maxSlots: number
}

export interface UpdateNodeRequest {
  revision: number
  state?: string
  maxSlots?: number
}

export interface RevisionRequest {
  revision: number
}

export const judgersApi = {
  // 宿主机分组
  getHostGroups: () => http.get<HostGroupView[]>('/admin/judge-host-groups'),
  updateHostGroup: (id: string | number, data: UpdateHostGroupRequest) =>
    http.patch<void>(`/admin/judge-host-groups/${id}`, data),

  // 评测节点管理
  getNodes: (params?: { afterId?: string | number; limit?: number }) =>
    http.get<JudgeNodeView[]>('/admin/judgers', { params }),
  getNodeDetail: (id: string | number) => http.get<JudgeNodeDetail>(`/admin/judgers/${id}`),
  connectNode: (apiUrl: string, idempotencyKey: string) =>
    http.post<JudgeConnectionView>('/admin/judgers/connections', { apiUrl }, {
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  getConnections: () => http.get<JudgeConnectionView[]>('/admin/judgers/connections'),
  getConnection: (id: string) => http.get<JudgeConnectionView>(`/admin/judgers/connections/${id}`),
  cancelConnection: (id: string) => http.post<JudgeConnectionView>(`/admin/judgers/connections/${id}/cancel`),
  updateNode: (id: string | number, data: UpdateNodeRequest) =>
    http.patch<JudgeNodeView>(`/admin/judgers/${id}`, data),
  requestCheck: (id: string | number) =>
    http.post<JudgeCheckView>(`/admin/judgers/${id}/checks`),
  revokeNode: (id: string | number, revision: number) =>
    http.post<void>(`/admin/judgers/${id}/revoke`, { revision }),
}
