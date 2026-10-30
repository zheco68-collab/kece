import { describe, expect, it, vi, beforeEach } from 'vitest'
import {
  getAnnouncements,
  getAnnouncementDetail,
  listAdminAnnouncements,
  getAdminAnnouncementDetail,
  createAdminAnnouncement,
  updateAdminAnnouncement,
  deleteAdminAnnouncement,
  toggleAdminAnnouncementTop,
  updateAdminAnnouncementStatus,
  type AnnouncementDetail,
} from './announcement'
import { http } from './request'

vi.mock('./request', () => ({
  http: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('announcementApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('list: 调用 /announcements 并传递分页参数', async () => {
    const mockData = {
      list: [{ id: 1, title: '测试公告', isTop: 1, status: 1, viewCount: 10, createdAt: '' }],
      total: 1,
      page: 1,
      size: 10,
    }
    vi.mocked(http.get).mockResolvedValueOnce(mockData)

    const res = await getAnnouncements({ page: 1, size: 10 })
    expect(http.get).toHaveBeenCalledWith('/announcements', { params: { page: 1, size: 10 } })
    expect(res).toEqual(mockData)
  })

  it('detail: 调用 /announcements/:id', async () => {
    const mockDetail: AnnouncementDetail = {
      id: 1,
      title: '公告详情',
      isTop: 0,
      status: 1,
      viewCount: 5,
      content: '# 内容',
      createdAt: '',
    }
    vi.mocked(http.get).mockResolvedValueOnce(mockDetail)

    const res = await getAnnouncementDetail(1)
    expect(http.get).toHaveBeenCalledWith('/announcements/1')
    expect(res).toEqual(mockDetail)
  })

  it('listAdmin: 调用 /admin/announcements', async () => {
    const mockData = { list: [], total: 0, page: 1, size: 15 }
    vi.mocked(http.get).mockResolvedValueOnce(mockData)

    const res = await listAdminAnnouncements({ keyword: '更新', page: 1, size: 15 })
    expect(http.get).toHaveBeenCalledWith('/admin/announcements', {
      params: { keyword: '更新', page: 1, size: 15 },
    })
    expect(res).toEqual(mockData)
  })

  it('detailAdmin: 调用 /admin/announcements/:id', async () => {
    const mockDetail: AnnouncementDetail = {
      id: 2,
      title: '后台公告',
      isTop: 1,
      status: 1,
      viewCount: 0,
      content: '正文',
      createdAt: '',
    }
    vi.mocked(http.get).mockResolvedValueOnce(mockDetail)

    const res = await getAdminAnnouncementDetail(2)
    expect(http.get).toHaveBeenCalledWith('/admin/announcements/2')
    expect(res).toEqual(mockDetail)
  })

  it('create: POST /admin/announcements 规范化参数', async () => {
    const payload = { title: '新公告', content: '内容', isTop: true, status: 1 }
    const mockCreated: AnnouncementDetail = { id: 3, ...payload, viewCount: 0, createdAt: '' }
    vi.mocked(http.post).mockResolvedValueOnce(mockCreated)

    const res = await createAdminAnnouncement(payload)
    expect(http.post).toHaveBeenCalledWith('/admin/announcements', {
      title: '新公告',
      content: '内容',
      summary: undefined,
      isPinned: 1,
      isTop: 1,
      status: 'PUBLISHED',
      authorName: undefined,
    })
    expect(res.id).toBe(3)
  })

  it('update: PUT /admin/announcements/:id 规范化参数', async () => {
    const payload = { title: '已编辑', content: '新内容', isTop: false, status: 1 }
    const mockUpdated: AnnouncementDetail = { id: 3, ...payload, viewCount: 0, createdAt: '' }
    vi.mocked(http.put).mockResolvedValueOnce(mockUpdated)

    await updateAdminAnnouncement(3, payload)
    expect(http.put).toHaveBeenCalledWith('/admin/announcements/3', {
      title: '已编辑',
      content: '新内容',
      summary: undefined,
      isPinned: 0,
      isTop: 0,
      status: 'PUBLISHED',
      authorName: undefined,
    })
  })

  it('delete: DELETE /admin/announcements/:id', async () => {
    vi.mocked(http.delete).mockResolvedValueOnce(undefined)

    await deleteAdminAnnouncement(3)
    expect(http.delete).toHaveBeenCalledWith('/admin/announcements/3')
  })

  it('toggleTop: PUT /admin/announcements/:id/pinned', async () => {
    const mockResult: AnnouncementDetail = {
      id: 3,
      title: '公告',
      content: '',
      isPinned: 1,
      status: 'PUBLISHED',
      viewCount: 0,
      createdAt: '',
    }
    vi.mocked(http.put).mockResolvedValueOnce(mockResult)

    await toggleAdminAnnouncementTop(3, true)
    expect(http.put).toHaveBeenCalledWith('/admin/announcements/3/pinned', { isPinned: 1 })
  })

  it('updateStatus: PUT /admin/announcements/:id/status 转为大写状态字符串', async () => {
    const mockResult: AnnouncementDetail = {
      id: 3,
      title: '公告',
      content: '',
      isPinned: 0,
      status: 'DRAFT',
      viewCount: 0,
      createdAt: '',
    }
    vi.mocked(http.put).mockResolvedValueOnce(mockResult)

    await updateAdminAnnouncementStatus(3, 0)
    expect(http.put).toHaveBeenCalledWith('/admin/announcements/3/status', { status: 'DRAFT' })
  })
})
