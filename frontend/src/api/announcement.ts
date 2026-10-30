import { http } from './request'
import type { PageResult } from '@/types'

export interface AnnouncementItem {
  id: number
  title: string
  content?: string
  summary?: string
  isPinned?: number | boolean
  isTop?: number | boolean
  status?: string | number
  viewCount?: number
  authorId?: number
  authorName?: string
  createdBy?: number
  createdAt?: string
  updatedAt?: string
  priority?: number
}

export type AnnouncementDetail = AnnouncementItem

export interface AnnouncementSavePayload {
  title: string
  content: string
  summary?: string
  isPinned?: number | boolean
  isTop?: number | boolean
  status?: string | number
  authorName?: string
}

export interface AnnouncementListParams {
  page?: number
  size?: number
}

export interface AdminAnnouncementListParams {
  page?: number
  size?: number
  keyword?: string
  status?: string | number
}

function normalizeSavePayload(payload: AnnouncementSavePayload) {
  const isPinnedVal =
    payload.isPinned === 1 ||
    payload.isPinned === true ||
    payload.isTop === 1 ||
    payload.isTop === true
      ? 1
      : 0

  let statusStr = 'PUBLISHED'
  if (typeof payload.status === 'number') {
    statusStr = payload.status === 1 ? 'PUBLISHED' : 'DRAFT'
  } else if (payload.status) {
    statusStr = payload.status
  }

  return {
    title: payload.title,
    content: payload.content,
    summary: payload.summary,
    isPinned: isPinnedVal,
    isTop: isPinnedVal,
    status: statusStr,
    authorName: payload.authorName,
  }
}

export const announcementApi = {
  /** 前台：分页获取公开公告看板列表 */
  list(params?: AnnouncementListParams) {
    return http.get<PageResult<AnnouncementItem>>('/announcements', { params })
  },

  /** 前台：获取公告详情（阅读量自增） */
  detail(id: number) {
    return http.get<AnnouncementDetail>(`/announcements/${id}`)
  },

  /** 管理后台：分页查询公告列表 */
  listAdmin(params?: AdminAnnouncementListParams) {
    return http.get<PageResult<AnnouncementItem>>('/admin/announcements', { params })
  },

  /** 管理后台：查询单条公告完整详情 */
  detailAdmin(id: number) {
    return http.get<AnnouncementDetail>(`/admin/announcements/${id}`)
  },

  /** 管理后台：创建新公告 */
  create(payload: AnnouncementSavePayload) {
    const body = normalizeSavePayload(payload)
    return http.post<AnnouncementDetail>('/admin/announcements', body)
  },

  /** 管理后台：修改已有公告 */
  update(id: number, payload: AnnouncementSavePayload) {
    const body = normalizeSavePayload(payload)
    return http.put<AnnouncementDetail>(`/admin/announcements/${id}`, body)
  },

  /** 管理后台：删除指定公告 */
  delete(id: number) {
    return http.delete<void>(`/admin/announcements/${id}`)
  },

  /** 管理后台：快速切换置顶状态 */
  toggleTop(id: number, isPinned: boolean) {
    const val = isPinned ? 1 : 0
    return http.put<AnnouncementDetail>(`/admin/announcements/${id}/pinned`, { isPinned: val })
  },

  /** 管理后台：更新发布状态 (PUBLISHED / DRAFT) */
  updateStatus(id: number, status: string | number) {
    const statusStr =
      typeof status === 'number' ? (status === 1 ? 'PUBLISHED' : 'DRAFT') : status
    return http.put<AnnouncementDetail>(`/admin/announcements/${id}/status`, { status: statusStr })
  },
}

export const getAnnouncements = (params?: AnnouncementListParams) => announcementApi.list(params)
export const getAnnouncementDetail = (id: number) => announcementApi.detail(id)
export const listAdminAnnouncements = (params?: AdminAnnouncementListParams) => announcementApi.listAdmin(params)
export const getAdminAnnouncementDetail = (id: number) => announcementApi.detailAdmin(id)
export const createAdminAnnouncement = (payload: AnnouncementSavePayload) => announcementApi.create(payload)
export const updateAdminAnnouncement = (id: number, payload: AnnouncementSavePayload) =>
  announcementApi.update(id, payload)
export const deleteAdminAnnouncement = (id: number) => announcementApi.delete(id)
export const toggleAdminAnnouncementTop = (id: number, isPinned: boolean) => announcementApi.toggleTop(id, isPinned)
export const updateAdminAnnouncementStatus = (id: number, status: string | number) =>
  announcementApi.updateStatus(id, status)
