import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import type { ObjectiveQuestion } from '@/types'

const { pushMock, replaceMock, routerState, objectiveApiMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  routerState: {
    route: { query: {} as Record<string, unknown> },
  },
  objectiveApiMock: {
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

vi.mock('@/api/objective', () => ({ objectiveApi: objectiveApiMock }))

import { useUserStore } from '@/stores/user'
import ObjectiveQuestionsView from './ObjectiveQuestionsView.vue'

function makeObjectiveQuestion(
  id: number,
  overrides: Partial<ObjectiveQuestion> = {},
): ObjectiveQuestion {
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
    ...overrides,
  }
}

describe('ObjectiveQuestionsView', () => {
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

  it('使用 stripMarkdown 渲染题干摘要、移除多余标准客观题徽章并应用 HUD 胶囊', async () => {
    const longMarkdown =
      '### 数据结构\n关于 **快速排序** `quick_sort` 与时间复杂度 $O(n \\log n)$ 的说法正确的是：' +
      '重复描述信息'.repeat(20)
    objectiveApiMock.list.mockResolvedValue({
      list: [
        makeObjectiveQuestion(1, {
          type: 'SINGLE',
          content: longMarkdown,
          score: 4,
        }),
        makeObjectiveQuestion(2, {
          type: 'FILL',
          content: '```cpp\nint x = 1;\n```\n计算变量 `x` 的最终结果是 **42**',
          score: 6,
        }),
      ],
      total: 2,
      page: 1,
      size: 10,
    })

    const wrapper = mount(ObjectiveQuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(wrapper.find('label.sr-only[for="objective-search"]').exists()).toBe(true)
    expect(wrapper.find('nav.pagination').exists()).toBe(false)

    const cards = wrapper.findAll('article.question-item')
    expect(cards).toHaveLength(2)

    const firstLink = cards[0].get('h2.q-title').getComponent(RouterLinkStub)
    expect(firstLink.props('to')).toBe('/objective/1')
    const firstTitleText = firstLink.text()
    expect(firstTitleText).toContain('数据结构 关于 快速排序 quick_sort')
    expect(firstTitleText).not.toContain('###')
    expect(firstTitleText).not.toContain('**')
    expect(firstTitleText).not.toContain('$')
    expect(firstTitleText.length).toBeLessThanOrEqual(120)
    expect(firstTitleText.endsWith('…')).toBe(true)

    const secondTitleText = cards[1].get('h2.q-title').text()
    expect(secondTitleText).toBe('计算变量 x 的最终结果是 42')
    expect(cards[0].text()).not.toContain('标准客观题')

    expect(cards[0].get('.hud-pill').classes()).toContain('tone-teal')
    expect(cards[0].get('.hud-pill').text()).toBe('单选题')
    expect(cards[1].get('.hud-pill').classes()).toContain('tone-danger')
    expect(cards[1].get('.hud-pill').text()).toBe('填空题')

    wrapper.unmount()
  })

  it('statusFilter !== ALL 时命中 filteredPool 缓存，翻页做 O(1) 切片不重扫全库', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    // 1..5 已答对，6..30 共 25 道未解答
    objectiveApiMock.solved.mockResolvedValue([1, 2, 3, 4, 5])
    routerState.route = reactive({
      query: { status: 'UNSOLVED' },
    })

    objectiveApiMock.list.mockImplementation(async (params) => {
      const page = params?.page ?? 1
      const size = Math.min(params?.size ?? 10, 100)
      const total = 30
      const start = (page - 1) * size
      const end = Math.min(total, start + size)
      const list = Array.from({ length: Math.max(0, end - start) }, (_, i) =>
        makeObjectiveQuestion(1 + start + i),
      )
      return { list, total, page, size }
    })

    const wrapper = mount(ObjectiveQuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(objectiveApiMock.list).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('article.question-item').map((el) => el.get('.q-number').text())).toEqual(
      Array.from({ length: 10 }, (_, i) => `#${6 + i}`),
    )

    // 翻至第 2 页和第 3 页：命中 filteredPool 缓存，不重新调用 objectiveApi.list
    await wrapper.get('nav.pagination button[aria-label="第 2 页"]').trigger('click')
    await flushPromises()
    expect(objectiveApiMock.list).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('article.question-item').map((el) => el.get('.q-number').text())).toEqual(
      Array.from({ length: 10 }, (_, i) => `#${16 + i}`),
    )
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: { status: 'UNSOLVED', page: '2' },
    })

    await wrapper.get('nav.pagination button[aria-label="第 3 页"]').trigger('click')
    await flushPromises()
    expect(objectiveApiMock.list).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('article.question-item').map((el) => el.get('.q-number').text())).toEqual(
      Array.from({ length: 5 }, (_, i) => `#${26 + i}`),
    )

    // 切换状态为「已通过」：缓存签名变化，重置 page=1 并触发新的全库拉取
    const solvedTab = wrapper
      .findAll('.status-tabs button.status-tab')
      .find((b) => b.text() === '已通过')!
    await solvedTab.trigger('click')
    await flushPromises()

    expect(objectiveApiMock.list).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('article.question-item').map((el) => el.get('.q-number').text())).toEqual(
      ['#1', '#2', '#3', '#4', '#5'],
    )
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: { status: 'SOLVED' },
    })

    wrapper.unmount()
  })

  it('从 URL query 初始化并探测 globalTotal，加载异常时支持重试恢复', async () => {
    const userStore = useUserStore()
    userStore.setToken('user-token')
    objectiveApiMock.solved.mockResolvedValue(Array.from({ length: 12 }, (_, i) => i + 1))
    routerState.route = reactive({
      query: { keyword: '二叉树', type: 'MULTIPLE', page: '2' },
    })

    let shouldFail = true
    objectiveApiMock.list.mockImplementation(async (params) => {
      if (params?.size === 1) {
        return { list: [makeObjectiveQuestion(1)], total: 60, page: 1, size: 1 }
      }
      if (shouldFail) {
        throw new Error('题库加载失败')
      }
      return {
        list: [makeObjectiveQuestion(21, { type: 'MULTIPLE', content: '二叉树遍历性质' })],
        total: 15,
        page: params?.page ?? 1,
        size: params?.size ?? 10,
      }
    })

    const wrapper = mount(ObjectiveQuestionsView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(objectiveApiMock.list).toHaveBeenCalledWith({
      keyword: '二叉树',
      type: 'MULTIPLE',
      page: 2,
      size: 10,
    })
    expect((wrapper.get('#objective-search').element as HTMLInputElement).value).toBe('二叉树')
    expect(wrapper.get('.submit-error').text()).toContain('题库加载失败')

    shouldFail = false
    await wrapper.get('.submit-error button').trigger('click')
    await flushPromises()

    expect(objectiveApiMock.list).toHaveBeenCalledWith({ page: 1, size: 1 })
    expect(wrapper.get('.progress-fraction').text()).toContain('12 / 60')
    expect(wrapper.get('.progress-percent').text()).toContain('20% 完成')
    expect(wrapper.get('h2.q-title').text()).toBe('二叉树遍历性质')

    wrapper.unmount()
  })
})
