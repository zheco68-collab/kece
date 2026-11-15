import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import type { DbQuestion } from '@/types'

const { pushMock, databaseApiMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  databaseApiMock: {
    list: vi.fn(),
    solved: vi.fn(),
  },
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRouter: () => ({ push: pushMock }),
  }
})

vi.mock('@/api/database', () => ({ databaseApi: databaseApiMock }))

import { useUserStore } from '@/stores/user'
import DatabaseQuestionsView from './DatabaseQuestionsView.vue'

function makeDbQuestion(id: number): DbQuestion {
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
  }
}

describe('DatabaseQuestionsView', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
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
})
