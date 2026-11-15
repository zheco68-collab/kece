import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import type { ObjectiveQuestion } from '@/types'

const { pushMock, objectiveApiMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  objectiveApiMock: {
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

vi.mock('@/api/objective', () => ({ objectiveApi: objectiveApiMock }))

import { useUserStore } from '@/stores/user'
import ObjectiveQuestionsView from './ObjectiveQuestionsView.vue'

function makeObjectiveQuestion(id: number): ObjectiveQuestion {
  return {
    id,
    type: 'SINGLE',
    content: `客观题-${id}`,
    options: ['A', 'B', 'C', 'D'],
    answer: null,
    matchType: null,
    score: 5,
    status: 1,
    createdBy: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

describe('ObjectiveQuestionsView', () => {
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

  it('已登录用户在第一页全部已作答时，随机刷题应跨越分页从全库未答题中抽取', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    // 第一页 1..10 全部已答对
    objectiveApiMock.solved.mockResolvedValue([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])

    objectiveApiMock.list.mockImplementation(async (params) => {
      const page = params?.page ?? 1
      const size = Math.min(params?.size ?? 10, 100)
      const total = 150
      const start = (page - 1) * size
      const end = Math.min(total, start + size)
      const list = Array.from({ length: Math.max(0, end - start) }, (_, i) =>
        makeObjectiveQuestion(1 + start + i),
      )
      return { list, total, page, size }
    })

    // 140 道未答题（#11..#150），0.99 指向末尾题 #149/#150，绝不会停在 #1..#10
    vi.spyOn(Math, 'random').mockReturnValue(0.99)

    const wrapper = mount(ObjectiveQuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    await wrapper.get('.random-btn').trigger('click')
    await flushPromises()

    expect(objectiveApiMock.list).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, size: 100 }),
    )
    expect(objectiveApiMock.list).toHaveBeenCalledWith(
      expect.objectContaining({ page: 2, size: 100 }),
    )
    expect(pushMock).toHaveBeenCalledWith('/objective/149')

    wrapper.unmount()
  })
})
