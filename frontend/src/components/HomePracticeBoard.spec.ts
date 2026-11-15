import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { questionApi } from '@/api/question'
import { useUserStore } from '@/stores/user'
import type { Question } from '@/types'
import HomePracticeBoard from './HomePracticeBoard.vue'

function makeQuestion(id: number, title: string, difficulty: Question['difficulty']): Question {
  return {
    id,
    title,
    description: '',
    difficulty,
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

async function withReducedMotion(run: () => Promise<void> | void) {
  const originalMatchMedia = window.matchMedia
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  })

  try {
    await run()
  } finally {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: originalMatchMedia,
    })
  }
}

function mountBoard(pinia: ReturnType<typeof createPinia>) {
  return mount(HomePracticeBoard, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: RouterLinkStub,
      },
    },
  })
}

describe('HomePracticeBoard', () => {
  let pinia: ReturnType<typeof createPinia>
  const pageOneQuestions = [
    makeQuestion(1, 'A+B 问题', 'EASY'),
    makeQuestion(2, '反转字符串', 'EASY'),
    makeQuestion(3, '两数之和', 'MEDIUM'),
    makeQuestion(4, '最长回文子串', 'HARD'),
  ]

  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('渲染题目总量、难度标签并触发逐行错峰掉落及末行收尾', async () => {
    vi.spyOn(questionApi, 'list').mockResolvedValueOnce({
      list: pageOneQuestions,
      total: 828,
      page: 1,
      size: 4,
    })

    const wrapper = mountBoard(pinia)
    await flushPromises()

    expect(wrapper.get('.practice-total strong').text()).toBe('828')
    const rows = wrapper.findAll('.question-row')
    expect(rows).toHaveLength(4)

    expect(rows[0].get('.question-number').text()).toBe('#1')
    expect(rows[0].get('.question-title').text()).toBe('A+B 问题')
    expect(rows[0].get('.difficulty').text()).toBe('简单')
    expect(rows[0].get('.difficulty').classes()).toContain('is-easy')
    expect(rows[2].get('.difficulty').classes()).toContain('is-medium')
    expect(rows[3].get('.difficulty').classes()).toContain('is-hard')

    expect(rows.every((row) => row.classes().includes('is-dropping'))).toBe(true)
    expect((rows[0].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('0ms')
    expect((rows[1].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('60ms')
    expect((rows[2].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('120ms')
    expect((rows[3].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('180ms')

    await rows[0].trigger('animationend')
    expect(rows.every((row) => row.classes().includes('is-dropping'))).toBe(true)

    await rows[3].get('.difficulty').trigger('animationend')
    expect(rows.every((row) => row.classes().includes('is-dropping'))).toBe(true)

    await rows[3].trigger('animationend')
    expect(rows.every((row) => !row.classes().includes('is-dropping'))).toBe(true)

    wrapper.unmount()
  })

  it('点击下一页时重新加载并重播逐行掉落动效', async () => {
    const pageTwoQuestions = [
      makeQuestion(5, '二分查找', 'EASY'),
      makeQuestion(6, '合并区间', 'MEDIUM'),
    ]
    const listSpy = vi
      .spyOn(questionApi, 'list')
      .mockResolvedValueOnce({
        list: pageOneQuestions,
        total: 828,
        page: 1,
        size: 4,
      })
      .mockResolvedValueOnce({
        list: pageTwoQuestions,
        total: 828,
        page: 2,
        size: 4,
      })

    const wrapper = mountBoard(pinia)
    await flushPromises()

    const firstRows = wrapper.findAll('.question-row')
    await firstRows[3].trigger('animationend')
    expect(firstRows.every((row) => !row.classes().includes('is-dropping'))).toBe(true)

    const nextButton = wrapper.findAll('.page-actions button').find((btn) => btn.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    expect(listSpy).toHaveBeenLastCalledWith({
      keyword: undefined,
      page: 2,
      size: 4,
    })

    const secondRows = wrapper.findAll('.question-row')
    expect(secondRows).toHaveLength(2)
    expect(secondRows[0].get('.question-title').text()).toBe('二分查找')
    expect(secondRows.every((row) => row.classes().includes('is-dropping'))).toBe(true)
    expect((secondRows[0].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('0ms')
    expect((secondRows[1].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('60ms')

    wrapper.unmount()
  })

  it('在减少动态效果偏好下禁用题目行掉落动画', async () => {
    vi.spyOn(questionApi, 'list').mockResolvedValueOnce({
      list: pageOneQuestions,
      total: 828,
      page: 1,
      size: 4,
    })

    await withReducedMotion(async () => {
      const wrapper = mountBoard(pinia)
      await flushPromises()

      const rows = wrapper.findAll('.question-row')
      expect(rows).toHaveLength(4)
      expect(rows.every((row) => !row.classes().includes('is-dropping'))).toBe(true)
      expect((rows[0].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('')

      wrapper.unmount()
    })
  })

  it('未登录用户点击随机刷 5 题可在面板内抽题、换一批并退出返回列表', async () => {
    const difficulties: Question['difficulty'][] = ['EASY', 'MEDIUM', 'HARD']
    const listSpy = vi.spyOn(questionApi, 'list').mockImplementation(async (params) => {
      if (params?.size === 4) {
        return {
          list: pageOneQuestions,
          total: 828,
          page: params.page ?? 1,
          size: 4,
        }
      }

      const page = params?.page ?? 1
      const pageSize = params?.size ?? 5
      const list = Array.from({ length: pageSize }, (_, index) => {
        const id = 100 + (page - 1) * 5 + index + 1
        return makeQuestion(id, `随机题目-${id}`, difficulties[index % difficulties.length])
      })
      return {
        list,
        total: 828,
        page,
        size: pageSize,
      }
    })

    const wrapper = mountBoard(pinia)
    await flushPromises()

    const randomButton = wrapper.get('.btn-random-5')
    expect(randomButton.text()).toBe('随机刷 5 题')
    expect(wrapper.get('h3').text()).toBe('选择下一道练习')
    expect(wrapper.get('.live-label').text()).toBe('公开题库')
    expect(wrapper.get('.hud-telemetry').text()).toBe('BANK.READY')

    await randomButton.trigger('click')
    await flushPromises()

    expect(wrapper.get('h3').text()).toBe('随机 5 题速刷')
    expect(wrapper.get('.live-label').text()).toBe('全库随机')
    expect(wrapper.get('.hud-telemetry').text()).toBe('RANDOM.X5')
    expect(wrapper.get('.practice-total strong').text()).toBe('5')
    expect(wrapper.get('.practice-total').text()).toContain('828')
    expect(wrapper.get('.btn-random-5').text()).toBe('换一批 5 题')
    expect(wrapper.find('.btn-back-browse').exists()).toBe(true)
    expect(wrapper.find('.page-actions').exists()).toBe(false)
    expect(wrapper.findAll('button').filter((btn) => btn.text() === '换一批 5 题')).toHaveLength(1)
    expect(wrapper.findAll('button').filter((btn) => btn.text() === '返回列表')).toHaveLength(1)

    const randomRows = wrapper.findAll('.question-row')
    expect(randomRows).toHaveLength(5)
    const pickedNumbers = randomRows.map((row) => row.get('.question-number').text())
    expect(new Set(pickedNumbers).size).toBe(5)
    expect(randomRows.every((row) => row.classes().includes('is-dropping'))).toBe(true)
    expect((randomRows[0].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('0ms')
    expect((randomRows[4].element as HTMLElement).style.getPropertyValue('--row-delay')).toBe('240ms')

    await randomRows[4].trigger('animationend')
    expect(randomRows.every((row) => !row.classes().includes('is-dropping'))).toBe(true)

    const callsBeforeRefresh = listSpy.mock.calls.length
    await wrapper.get('.btn-random-5').trigger('click')
    await flushPromises()

    expect(listSpy.mock.calls.length).toBeGreaterThan(callsBeforeRefresh)
    const refreshedRows = wrapper.findAll('.question-row')
    expect(refreshedRows).toHaveLength(5)
    expect(refreshedRows.every((row) => row.classes().includes('is-dropping'))).toBe(true)

    await wrapper.get('.btn-back-browse').trigger('click')
    await flushPromises()

    expect(wrapper.get('h3').text()).toBe('选择下一道练习')
    expect(wrapper.get('.live-label').text()).toBe('公开题库')
    expect(wrapper.get('.hud-telemetry').text()).toBe('BANK.READY')
    expect(wrapper.get('.btn-random-5').text()).toBe('随机刷 5 题')
    expect(wrapper.find('.btn-back-browse').exists()).toBe(false)
    expect(wrapper.findAll('.question-row')).toHaveLength(4)

    wrapper.unmount()
  })

  it('已登录用户优先抽取未解答题目并在不足 5 题时从公开题库去重补足', async () => {
    const userStore = useUserStore()
    userStore.setToken('test-token')

    const unsolvedPool = [
      makeQuestion(501, '未解答一', 'MEDIUM'),
      makeQuestion(502, '未解答二', 'HARD'),
    ]
    const fullBank = [
      unsolvedPool[0],
      unsolvedPool[1],
      makeQuestion(601, '补足题一', 'EASY'),
      makeQuestion(602, '补足题二', 'MEDIUM'),
      makeQuestion(603, '补足题三', 'HARD'),
      makeQuestion(604, '补足题四', 'EASY'),
    ]

    const listSpy = vi.spyOn(questionApi, 'list').mockImplementation(async (params) => {
      if (params?.size === 4) {
        return {
          list: pageOneQuestions,
          total: 828,
          page: 1,
          size: 4,
        }
      }
      if (params?.userStatus === 'UNSOLVED') {
        return {
          list: unsolvedPool,
          total: unsolvedPool.length,
          page: 1,
          size: params.size ?? 5,
        }
      }
      const size = params?.size ?? 5
      return {
        list: fullBank.slice(0, size),
        total: fullBank.length,
        page: 1,
        size,
      }
    })

    const wrapper = mountBoard(pinia)
    await flushPromises()

    await wrapper.get('.btn-random-5').trigger('click')
    await flushPromises()

    expect(listSpy.mock.calls.some(([p]) => p?.userStatus === 'UNSOLVED')).toBe(true)
    expect(listSpy.mock.calls.some(([p]) => p?.userStatus === undefined && p?.size === 6)).toBe(true)

    expect(wrapper.get('.live-label').text()).toBe('优先未解答')
    expect(wrapper.get('.hud-telemetry').text()).toBe('UNSOLVED.X5')

    const rows = wrapper.findAll('.question-row')
    expect(rows).toHaveLength(5)
    const numbers = rows.map((row) => row.get('.question-number').text())
    expect(new Set(numbers).size).toBe(5)
    expect(numbers).toContain('#501')
    expect(numbers).toContain('#502')

    wrapper.unmount()
  })

  it('随机抽题失败时展示错误提示且支持重新加载重试', async () => {
    let shouldFailRandom = true
    const fallbackBatch = [
      makeQuestion(701, '恢复题一', 'EASY'),
      makeQuestion(702, '恢复题二', 'EASY'),
      makeQuestion(703, '恢复题三', 'MEDIUM'),
      makeQuestion(704, '恢复题四', 'MEDIUM'),
      makeQuestion(705, '恢复题五', 'HARD'),
    ]

    vi.spyOn(questionApi, 'list').mockImplementation(async (params) => {
      if (params?.size === 4) {
        return {
          list: pageOneQuestions,
          total: 828,
          page: 1,
          size: 4,
        }
      }
      if (shouldFailRandom) {
        throw new Error('network error')
      }
      return {
        list: fallbackBatch,
        total: fallbackBatch.length,
        page: 1,
        size: params?.size ?? 5,
      }
    })

    const wrapper = mountBoard(pinia)
    await flushPromises()

    await wrapper.get('.btn-random-5').trigger('click')
    await flushPromises()

    expect(wrapper.get('.hud-telemetry').text()).toBe('SYNC.ERR')
    const messageState = wrapper.get('.list-state.is-message')
    expect(messageState.text()).toContain('随机抽题失败，请稍后重试。')

    shouldFailRandom = false
    const retryButton = messageState.findAll('button').find((btn) => btn.text() === '重新加载')
    expect(retryButton).toBeDefined()
    await retryButton!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.list-state.is-message').exists()).toBe(false)
    expect(wrapper.get('.hud-telemetry').text()).toBe('RANDOM.X5')
    expect(wrapper.findAll('.question-row')).toHaveLength(5)

    wrapper.unmount()
  })
})
