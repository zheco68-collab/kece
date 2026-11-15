import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import type { Question } from '@/types'

const { pushMock, replaceMock, routerState, questionApiMock, submissionApiMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  routerState: {
    route: { query: {} as Record<string, unknown> },
  },
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
    useRoute: () => routerState.route,
    useRouter: () => ({ push: pushMock, replace: replaceMock }),
  }
})

vi.mock('@/api/question', () => ({ questionApi: questionApiMock }))
vi.mock('@/api/submission', () => ({ submissionApi: submissionApiMock }))

import { useUserStore } from '@/stores/user'
import QuestionsView from './QuestionsView.vue'

function makeQuestion(id: number, overrides: Partial<Question> = {}): Question {
  return {
    id,
    title: `题目-${id}`,
    description: '',
    difficulty: 'EASY',
    timeLimit: 1000,
    memoryLimit: 256,
    languages: ['CPP', 'JAVA', 'PYTHON'],
    tags: [],
    status: 1,
    createdBy: 1,
    sampleTestCases: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('QuestionsView', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    replaceMock.mockResolvedValue(undefined)
    routerState.route = reactive({ query: {} })
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

  it('带筛选条件直入时通过轻量探测锁定 globalTotal 分母，避免进度超过 100%', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    submissionApiMock.solved.mockResolvedValue(Array.from({ length: 25 }, (_, i) => i + 1))
    routerState.route = reactive({
      query: { difficulty: 'HARD', page: '1' },
    })

    questionApiMock.list.mockImplementation(async (params) => {
      if (params?.difficulty === 'HARD') {
        return {
          list: Array.from({ length: 8 }, (_, i) => makeQuestion(100 + i, { difficulty: 'HARD' })),
          total: 8,
          page: 1,
          size: 10,
        }
      }
      return {
        list: [makeQuestion(1)],
        total: 100,
        page: 1,
        size: 1,
      }
    })

    const wrapper = mount(QuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(questionApiMock.list).toHaveBeenCalledWith(
      expect.objectContaining({ difficulty: 'HARD', page: 1, size: 10 }),
    )
    expect(questionApiMock.list).toHaveBeenCalledWith({ page: 1, size: 1 })
    expect(wrapper.get('.progress-fraction').text()).toContain('25 / 100')
    expect(wrapper.get('.progress-percent').text()).toContain('25% 完成')
    expect(wrapper.find('nav.pagination').exists()).toBe(false)

    wrapper.unmount()
  })

  it('按标准顺序渲染 ArkTS 等语言标签与 HUD 难度胶囊，且保持非嵌套交互结构', async () => {
    questionApiMock.listTags.mockResolvedValue(['动态规划', '图论'])
    questionApiMock.list.mockResolvedValue({
      list: [
        makeQuestion(12, {
          title: '鸿蒙与算法综合题',
          difficulty: 'MEDIUM',
          languages: ['ARKTS', 'PYTHON', 'CPP', 'JAVA'],
          tags: ['动态规划', '图论'],
        }),
      ],
      total: 1,
      page: 1,
      size: 10,
    })

    const wrapper = mount(QuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    const searchLabel = wrapper.find('label.sr-only[for="questions-search"]')
    expect(searchLabel.exists()).toBe(true)

    const card = wrapper.get('article.question-item')
    const titleHeading = card.get('h2.q-title')
    const titleLink = titleHeading.getComponent(RouterLinkStub)
    expect(titleLink.props('to')).toBe('/questions/12')
    expect(titleLink.classes()).toContain('q-title-link')

    const langTexts = card.findAll('.meta-tag.lang').map((el) => el.text())
    expect(langTexts).toEqual(['C++', 'Java', 'Python', 'ArkTS'])

    const pill = card.get('.hud-pill')
    expect(pill.classes()).toContain('tone-warning')
    expect(pill.text()).toBe('中等')

    const tagButtons = card.findAll('button.question-tag')
    expect(tagButtons).toHaveLength(2)
    expect(tagButtons[0].attributes('aria-pressed')).toBe('false')
    expect(tagButtons[0].element.closest('a')).toBeNull()

    await tagButtons[0].trigger('click')
    await flushPromises()

    const refreshedTagButtons = wrapper.findAll('article.question-item button.question-tag')
    expect(refreshedTagButtons[0].attributes('aria-pressed')).toBe('true')
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: { tag: '动态规划' },
    })

    const toggleBtn = wrapper.get('.tag-toggle-btn')
    const clearBtn = wrapper.get('button.tag-clear-btn')
    expect(clearBtn.attributes('aria-label')).toBe('清除标签筛选')
    expect(clearBtn.element.closest('.tag-toggle-btn')).toBeNull()
    expect(toggleBtn.element.parentElement).toBe(clearBtn.element.parentElement)

    await clearBtn.trigger('click')
    await flushPromises()

    expect(wrapper.find('button.tag-clear-btn').exists()).toBe(false)
    expect(replaceMock).toHaveBeenLastCalledWith({ query: {} })

    wrapper.unmount()
  })

  it('从 URL query 初始化筛选状态、分页切换与错误重试均正常工作', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    submissionApiMock.solved.mockResolvedValue([1, 2])
    questionApiMock.listTags.mockResolvedValue(['BFS', 'DFS'])
    routerState.route = reactive({
      query: {
        keyword: '图论',
        difficulty: 'EASY',
        tag: 'BFS',
        status: 'UNSOLVED',
        page: '2',
      },
    })

    let shouldFail = true
    questionApiMock.list.mockImplementation(async (params) => {
      if (params?.size === 1) {
        return { list: [makeQuestion(1)], total: 40, page: 1, size: 1 }
      }
      if (shouldFail) {
        throw new Error('网络超时')
      }
      const page = params?.page ?? 1
      return {
        list: [makeQuestion(20 + page, { difficulty: 'EASY', tags: ['BFS'] })],
        total: 25,
        page,
        size: 10,
      }
    })

    const wrapper = mount(QuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(questionApiMock.list).toHaveBeenCalledWith({
      keyword: '图论',
      difficulty: 'EASY',
      tag: 'BFS',
      userStatus: 'UNSOLVED',
      page: 2,
      size: 10,
    })
    expect((wrapper.get('#questions-search').element as HTMLInputElement).value).toBe('图论')
    expect(wrapper.get('.submit-error').text()).toContain('网络超时')

    shouldFail = false
    await wrapper.get('.submit-error button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.submit-error').exists()).toBe(false)
    expect(wrapper.find('nav.pagination').exists()).toBe(true)

    const page3Btn = wrapper.get('nav.pagination button[aria-label="第 3 页"]')
    await page3Btn.trigger('click')
    await flushPromises()

    expect(questionApiMock.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 3, keyword: '图论', difficulty: 'EASY', tag: 'BFS' }),
    )
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: {
        keyword: '图论',
        difficulty: 'EASY',
        tag: 'BFS',
        status: 'UNSOLVED',
        page: '3',
      },
    })

    // 切换难度时自动重置 page = 1 并从 URL 省略默认 page=1
    const mediumBtn = wrapper
      .findAll('.difficulty-tabs button.tab-btn')
      .find((b) => b.text() === '中等')!
    await mediumBtn.trigger('click')
    await flushPromises()

    expect(questionApiMock.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, difficulty: 'MEDIUM' }),
    )
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: {
        keyword: '图论',
        difficulty: 'MEDIUM',
        tag: 'BFS',
        status: 'UNSOLVED',
      },
    })

    wrapper.unmount()
  })

  it('卡片入场 is-dropping 在 animationend 后清除且兼容 prefers-reduced-motion，历史回退保留页码', async () => {
    const originalMatchMedia = window.matchMedia
    const matchMediaSpy = vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      configurable: true,
      value: matchMediaSpy,
    })

    try {
      questionApiMock.list.mockResolvedValue({
        list: [makeQuestion(1)],
        total: 30,
        page: 1,
        size: 10,
      })

      const wrapper = mount(QuestionsView, {
        global: {
          plugins: [pinia],
          stubs: { RouterLink: RouterLinkStub },
        },
      })
      await flushPromises()

      const card = wrapper.get('article.question-item')
      expect(card.classes()).toContain('is-dropping')
      await card.trigger('animationend')
      expect(card.classes()).not.toContain('is-dropping')

      // 模拟浏览器前进/后退修改 route.query，保留 page=2 而不重置为 1
      const replaceCountBeforeNav = replaceMock.mock.calls.length
      routerState.route.query = { keyword: '动态规划', page: '2' }
      await flushPromises()

      expect(questionApiMock.list).toHaveBeenLastCalledWith(
        expect.objectContaining({ keyword: '动态规划', page: 2, size: 10 }),
      )
      expect(replaceMock).toHaveBeenCalledTimes(replaceCountBeforeNav)
      wrapper.unmount()

      // prefers-reduced-motion: reduce 开启时不挂载 is-dropping 类
      matchMediaSpy.mockImplementation((query: string) => ({
        matches: query === '(prefers-reduced-motion: reduce)',
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }))
      routerState.route = reactive({ query: {} })
      const reducedWrapper = mount(QuestionsView, {
        global: {
          plugins: [pinia],
          stubs: { RouterLink: RouterLinkStub },
        },
      })
      await flushPromises()
      expect(reducedWrapper.get('article.question-item').classes()).not.toContain('is-dropping')
      reducedWrapper.unmount()
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        writable: true,
        configurable: true,
        value: originalMatchMedia,
      })
    }
  })
})
