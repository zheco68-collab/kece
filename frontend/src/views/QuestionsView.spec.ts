import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import type { Question } from '@/types'

const { pushMock, questionApiMock, submissionApiMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  questionApiMock: {
    list: vi.fn(),
    listTags: vi.fn(),
  },
  submissionApiMock: {
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

vi.mock('@/api/question', () => ({ questionApi: questionApiMock }))
vi.mock('@/api/submission', () => ({ submissionApi: submissionApiMock }))

import { useUserStore } from '@/stores/user'
import QuestionsView from './QuestionsView.vue'

function makeQuestion(id: number): Question {
  return {
    id,
    title: `题目-${id}`,
    description: '',
    difficulty: 'EASY',
    timeLimit: 1000,
    memoryLimit: 256,
    languages: ['CPP', 'JAVA', 'PYTHON'],
    status: 1,
    createdBy: 1,
    sampleTestCases: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  }
}

describe('QuestionsView', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    questionApiMock.listTags.mockResolvedValue([])
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('已登录用户在第一页全部已通过时，随机刷题应跨全库未解答分页抽取而不是局限于第一页', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    submissionApiMock.solved.mockResolvedValue(Array.from({ length: 30 }, (_, i) => i + 1))

    questionApiMock.list.mockImplementation(async (params) => {
      const page = params?.page ?? 1
      const size = params?.size ?? 10
      if (params?.userStatus === 'UNSOLVED') {
        const unsolvedTotal = 798
        const start = (page - 1) * size
        const end = Math.min(unsolvedTotal, start + size)
        const list = Array.from({ length: Math.max(0, end - start) }, (_, i) =>
          makeQuestion(31 + start + i),
        )
        return { list, total: unsolvedTotal, page, size }
      }
      const total = 828
      const start = (page - 1) * size
      const end = Math.min(total, start + size)
      const list = Array.from({ length: Math.max(0, end - start) }, (_, i) =>
        makeQuestion(1 + start + i),
      )
      return { list, total, page, size }
    })

    // Math.random 指向第 50 页未解题（0-based index 495 -> 第 50 页第 6 条 -> #526）
    vi.spyOn(Math, 'random').mockReturnValue(495 / 798)

    const wrapper = mount(QuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    await wrapper.get('.random-btn').trigger('click')
    await flushPromises()

    expect(questionApiMock.list).toHaveBeenCalledWith(
      expect.objectContaining({ userStatus: 'UNSOLVED', page: 50, size: 10 }),
    )
    expect(pushMock).toHaveBeenCalledWith('/questions/526')

    wrapper.unmount()
  })

  it('未登录或全库均已通过时可按筛选条件跨全部分页抽取题目', async () => {
    submissionApiMock.solved.mockResolvedValue([])
    questionApiMock.list.mockImplementation(async (params) => {
      const page = params?.page ?? 1
      const size = params?.size ?? 10
      const total = 828
      const start = (page - 1) * size
      const end = Math.min(total, start + size)
      const list = Array.from({ length: Math.max(0, end - start) }, (_, i) =>
        makeQuestion(1 + start + i),
      )
      return { list, total, page, size }
    })

    // 指向最后一个分页第 828 题（index 827 -> 第 83 页下标 7）
    vi.spyOn(Math, 'random').mockReturnValue(827.5 / 828)

    const wrapper = mount(QuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    await wrapper.get('.random-btn').trigger('click')
    await flushPromises()

    expect(questionApiMock.list).toHaveBeenCalledWith(
      expect.objectContaining({ page: 83, size: 10 }),
    )
    expect(pushMock).toHaveBeenCalledWith('/questions/828')

    wrapper.unmount()
  })
})
