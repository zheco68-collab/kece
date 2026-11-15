import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import type { DbQuestion } from '@/types'

const { pushMock, replaceMock, routerState, databaseApiMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  routerState: {
    route: { query: {} as Record<string, unknown> },
  },
  databaseApiMock: {
    list: vi.fn(),
    solved: vi.fn(),
  },
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routerState.route,
    useRouter: () => ({ push: pushMock, replace: replaceMock }),
  }
})

vi.mock('@/api/database', () => ({ databaseApi: databaseApiMock }))

import { useUserStore } from '@/stores/user'
import DatabaseQuestionsView from './DatabaseQuestionsView.vue'

function makeDbQuestion(id: number, overrides: Partial<DbQuestion> = {}): DbQuestion {
  return {
    id,
    title: `SQL-${id}`,
    description: '',
    dialect: 'MYSQL',
    type: 'SELECT',
    schemaSql: 'CREATE TABLE t (id INT);',
    referenceSql: null,
    verifySql: null,
    orderSensitive: false,
    status: 1,
    createdBy: 1,
    testCases: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('DatabaseQuestionsView', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    replaceMock.mockResolvedValue(undefined)
    routerState.route = reactive({ query: {} })
    pinia = createPinia()
    setActivePinia(pinia)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('随机刷题跨全库分页抽取未解答 SQL 题目而不局限在第一页', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    databaseApiMock.solved.mockResolvedValue([1, 2, 3])

    databaseApiMock.list.mockImplementation(async (params) => {
      const page = params?.page ?? 1
      const size = params?.size ?? 10
      const base = params?.userStatus === 'UNSOLVED' ? 4 : 1
      const total = params?.userStatus === 'UNSOLVED' ? 47 : 50
      const start = (page - 1) * size
      const end = Math.min(total, start + size)
      const list = Array.from({ length: Math.max(0, end - start) }, (_, i) =>
        makeDbQuestion(base + start + i),
      )
      return { list, total, page, size }
    })

    // index 42 / 47 -> 第 5 页第 3 条 -> id 4 + 42 = 46
    vi.spyOn(Math, 'random').mockReturnValue(42 / 47)

    const wrapper = mount(DatabaseQuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    await wrapper.get('.random-btn').trigger('click')
    await flushPromises()

    expect(databaseApiMock.list).toHaveBeenCalledWith(
      expect.objectContaining({ userStatus: 'UNSOLVED', page: 5, size: 10 }),
    )
    expect(pushMock).toHaveBeenCalledWith('/database/46')

    wrapper.unmount()
  })

  it('带筛选参数直入时探测 globalTotal，保证攻克进度分母不失真', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    databaseApiMock.solved.mockResolvedValue(Array.from({ length: 15 }, (_, i) => i + 1))
    routerState.route = reactive({
      query: { type: 'DML' },
    })

    databaseApiMock.list.mockImplementation(async (params) => {
      if (params?.type === 'DML') {
        return {
          list: Array.from({ length: 5 }, (_, i) => makeDbQuestion(50 + i, { type: 'DML' })),
          total: 5,
          page: 1,
          size: 10,
        }
      }
      return {
        list: [makeDbQuestion(1)],
        total: 50,
        page: 1,
        size: 1,
      }
    })

    const wrapper = mount(DatabaseQuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(databaseApiMock.list).toHaveBeenCalledWith({ page: 1, size: 1 })
    expect(wrapper.get('.progress-fraction').text()).toContain('15 / 50')
    expect(wrapper.get('.progress-percent').text()).toContain('30% 完成')
    expect(wrapper.find('nav.pagination').exists()).toBe(false)

    wrapper.unmount()
  })

  it('标准化展示方言与 HUD 胶囊，保持 article/h2/RouterLink 语义并支持分页及错误重试', async () => {
    routerState.route = reactive({
      query: { keyword: 'Join', type: 'SELECT', page: '2' },
    })

    let shouldFail = true
    databaseApiMock.list.mockImplementation(async (params) => {
      if (params?.size === 1) {
        return { list: [makeDbQuestion(1)], total: 35, page: 1, size: 1 }
      }
      if (shouldFail) {
        throw new Error('数据库服务暂不可用')
      }
      const page = params?.page ?? 1
      return {
        list: [
          makeDbQuestion(10 + page, {
            title: '多表连接查询',
            dialect: 'MYSQL',
            type: 'SELECT',
            orderSensitive: true,
          }),
          makeDbQuestion(20 + page, {
            title: '批量更新库存',
            dialect: '',
            type: 'DML',
          }),
        ],
        total: 24,
        page,
        size: 10,
      }
    })

    const wrapper = mount(DatabaseQuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(databaseApiMock.list).toHaveBeenCalledWith({
      keyword: 'Join',
      type: 'SELECT',
      userStatus: undefined,
      page: 2,
      size: 10,
    })
    expect(wrapper.find('label.sr-only[for="database-search"]').exists()).toBe(true)
    expect((wrapper.get('#database-search').element as HTMLInputElement).value).toBe('Join')
    expect(wrapper.get('.submit-error').text()).toContain('数据库服务暂不可用')

    shouldFail = false
    await wrapper.get('.submit-error button').trigger('click')
    await flushPromises()

    const cards = wrapper.findAll('article.question-item')
    expect(cards).toHaveLength(2)
    expect(cards[0].get('.meta-tag.dialect').text()).toBe('MySQL 8.0')
    expect(cards[1].get('.meta-tag.dialect').text()).toBe('MySQL 8.0')

    const titleLink = cards[0].get('h2.q-title').getComponent(RouterLinkStub)
    expect(titleLink.props('to')).toBe('/database/12')
    expect(titleLink.classes()).toContain('q-title-link')

    expect(cards[0].get('.hud-pill').classes()).toContain('tone-teal')
    expect(cards[1].get('.hud-pill').classes()).toContain('tone-warning')

    expect(wrapper.find('nav.pagination').exists()).toBe(true)
    await wrapper.get('nav.pagination button[aria-label="第 3 页"]').trigger('click')
    await flushPromises()

    expect(replaceMock).toHaveBeenLastCalledWith({
      query: { keyword: 'Join', type: 'SELECT', page: '3' },
    })

    const dmlTab = wrapper.findAll('.type-tabs button.tab-btn').find((b) => b.text() === 'DML 操作')!
    await dmlTab.trigger('click')
    await flushPromises()

    expect(databaseApiMock.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: 'DML', page: 1 }),
    )
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: { keyword: 'Join', type: 'DML' },
    })

    wrapper.unmount()
  })
})
