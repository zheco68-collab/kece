import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const {
  routeState,
  profileApiMock,
  submissionApiMock,
  databaseApiMock,
  objectiveApiMock,
  solutionApiMock,
} = vi.hoisted(() => ({
  routeState: {
    params: {} as Record<string, string>,
    query: {} as Record<string, string>,
  },
  profileApiMock: {
    getProfile: vi.fn(),
    getHeatmap: vi.fn(),
    getExams: vi.fn(),
  },
  submissionApiMock: { list: vi.fn() },
  databaseApiMock: { listSubmissions: vi.fn() },
  objectiveApiMock: { listSubmissions: vi.fn() },
  solutionApiMock: {
    listMine: vi.fn(),
    listByAuthor: vi.fn(),
  },
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routeState,
  }
})

vi.mock('@/api/profile', () => ({ profileApi: profileApiMock }))
vi.mock('@/api/submission', () => ({ submissionApi: submissionApiMock }))
vi.mock('@/api/database', () => ({ databaseApi: databaseApiMock }))
vi.mock('@/api/objective', () => ({ objectiveApi: objectiveApiMock }))
vi.mock('@/api/solution', () => ({ solutionApi: solutionApiMock }))

import ProfileView from './ProfileView.vue'
import { useUserStore } from '@/stores/user'

describe('ProfileView showcase-only surface & standalone solutions section', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeState.params = reactive({})
    routeState.query = reactive({})
    setActivePinia(createPinia())
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 27,
        username: 'zheco68',
        email: 'zheco68@kece.edu',
        roles: ['USER'],
      },
    })

    profileApiMock.getProfile.mockResolvedValue({
      id: 27,
      username: 'zheco68',
      email: 'zheco68@kece.edu',
      avatarUrl: null,
      bio: 'Hello KECE',
      githubUrl: 'https://github.com/zheco68',
      roles: ['USER'],
      createdAt: '2026-10-01T08:00:00Z',
    })
    profileApiMock.getHeatmap.mockResolvedValue({
      days: [],
      totalAc: 0,
      maxStreak: 0,
      currentStreak: 0,
    })
    profileApiMock.getExams.mockResolvedValue([])
    submissionApiMock.list.mockResolvedValue({ list: [], total: 0 })
    databaseApiMock.listSubmissions.mockResolvedValue({ list: [], total: 0 })
    objectiveApiMock.listSubmissions.mockResolvedValue({ list: [], total: 0 })
    solutionApiMock.listMine.mockResolvedValue({ list: [], total: 0 })
    solutionApiMock.listByAuthor.mockResolvedValue({ list: [], total: 0 })
  })

  it('strips all edit/password triggers and modals from ProfileView', async () => {
    const wrapper = mount(ProfileView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
          HeatmapCalendar: true,
          Pagination: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.avatar-hover-mask').exists()).toBe(false)
    expect(wrapper.find('button.hero-avatar-box').exists()).toBe(false)
    expect(wrapper.find('.hero-actions-group').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('编辑资料')
    expect(wrapper.text()).not.toContain('修改密码')
    expect(wrapper.text()).not.toContain('更换头像')
  })

  it('renders bounded square avatar-image when avatarUrl exists and fallback letter otherwise', async () => {
    profileApiMock.getProfile.mockResolvedValueOnce({
      id: 27,
      username: 'zheco68',
      email: 'zheco68@kece.edu',
      avatarUrl: '/api/v1/uploads/avatars/avatar_27_demo.png',
      bio: 'Hello KECE',
      githubUrl: 'https://github.com/zheco68',
      roles: ['USER'],
      createdAt: '2026-10-01T08:00:00Z',
    })

    const withAvatar = mount(ProfileView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
          HeatmapCalendar: true,
          Pagination: true,
        },
      },
    })
    await flushPromises()

    const img = withAvatar.find('.hero-avatar-box img.avatar-image')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/api/v1/uploads/avatars/avatar_27_demo.png')
    expect(img.attributes('alt')).toContain('zheco68')
    expect(withAvatar.find('.avatar-placeholder-large').exists()).toBe(false)
  })

  it('separates solutions out of submissions tabs into an independent section placed at the bottom for owner', async () => {
    solutionApiMock.listMine.mockResolvedValueOnce({
      list: [
        {
          id: 501,
          questionType: 'ALGO',
          questionId: 12,
          questionTitle: '前缀和',
          questionPublic: true,
          authorId: 27,
          authorName: 'zheco68',
          title: '一维差分思路',
          status: 'PUBLISHED',
          version: 1,
          createdAt: '2026-11-10T10:00:00Z',
          updatedAt: '2026-11-10T12:00:00Z',
        },
        {
          id: 502,
          questionType: 'DB',
          questionId: 8,
          questionTitle: '窗口函数排序',
          questionPublic: false,
          authorId: 27,
          authorName: 'zheco68',
          title: 'DENSE_RANK 解析',
          status: 'HIDDEN',
          version: 2,
          createdAt: '2026-11-11T10:00:00Z',
          updatedAt: '2026-11-11T11:00:00Z',
        },
      ],
      total: 2,
    })

    const wrapper = mount(ProfileView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
          HeatmapCalendar: true,
          Pagination: true,
        },
      },
    })
    await flushPromises()

    const submissionsSection = wrapper.find('#submissions-section')
    const solutionsSection = wrapper.find('#solutions-section')
    expect(submissionsSection.exists()).toBe(true)
    expect(solutionsSection.exists()).toBe(true)

    // Solutions tab removed from submissions card
    expect(submissionsSection.find('#profile-tab-solutions').exists()).toBe(false)
    expect(submissionsSection.findAll('[role="tab"]')).toHaveLength(3)

    // Solutions card rendered below submissions card
    const pos = submissionsSection.element.compareDocumentPosition(solutionsSection.element)
    expect(pos & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    expect(solutionsSection.text()).toContain('我的题解')
    expect(solutionsSection.text()).toContain('2 篇')
    expect(solutionsSection.text()).toContain('一维差分思路')
    expect(solutionsSection.text()).toContain('公开')
    expect(solutionsSection.text()).toContain('已隐藏')

    const editLinks = solutionsSection
      .findAllComponents(RouterLinkStub)
      .filter((link) => String(link.props('to')).endsWith('/edit'))
    expect(editLinks).toHaveLength(2)
    expect(solutionsSection.text()).not.toContain('删除')
  })

  it('loads public solutions via listByAuthor for visitor mode (even when viewer is admin) without status or edit columns', async () => {
    useUserStore().setAuth({
      token: 'admin-token',
      refreshToken: 'admin-refresh',
      user: {
        id: 1,
        username: 'admin',
        email: 'admin@kece.edu',
        roles: ['ADMIN'],
      },
    })
    routeState.params = reactive({ id: '28' })

    profileApiMock.getProfile.mockResolvedValueOnce({
      id: 28,
      username: 'bob',
      email: 'bob@kece.edu',
      avatarUrl: null,
      bio: 'SQL lover',
      githubUrl: null,
      roles: ['USER'],
      createdAt: '2026-10-05T08:00:00Z',
    })
    solutionApiMock.listByAuthor.mockResolvedValueOnce({
      list: [
        {
          id: 601,
          questionType: 'DB',
          questionId: 3,
          questionTitle: '连续登录天数',
          questionPublic: true,
          authorId: 28,
          authorName: 'bob',
          title: '日期差分组法',
          status: 'PUBLISHED',
          version: 1,
          createdAt: '2026-11-12T08:00:00Z',
          updatedAt: '2026-11-12T09:00:00Z',
        },
      ],
      total: 1,
    })

    const wrapper = mount(ProfileView, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
          HeatmapCalendar: true,
          Pagination: true,
        },
      },
    })
    await flushPromises()

    expect(solutionApiMock.listMine).not.toHaveBeenCalled()
    expect(solutionApiMock.listByAuthor).toHaveBeenCalledWith(28, { page: 1, size: 10 })

    const solutionsSection = wrapper.find('#solutions-section')
    expect(solutionsSection.exists()).toBe(true)
    expect(solutionsSection.text()).toContain('bob 的题解')
    expect(solutionsSection.text()).toContain('日期差分组法')
    expect(solutionsSection.text()).not.toContain('状态')
    expect(solutionsSection.text()).not.toContain('编辑')
  })
})
