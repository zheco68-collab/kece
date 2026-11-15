import { defineComponent, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Difficulty } from '@/types'

const routerState = vi.hoisted(() => ({
  hasUseRoute: true,
  route: undefined as { query: Record<string, unknown> } | undefined,
  router: undefined as { push?: ReturnType<typeof vi.fn>; replace?: ReturnType<typeof vi.fn> } | undefined,
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    get useRoute() {
      return routerState.hasUseRoute ? () => routerState.route : undefined
    },
    useRouter: () => routerState.router,
  }
})

import {
  buildBankQuery,
  readBankQuery,
  useBankUrlSync,
  type UseBankUrlSyncOptions,
} from './useBankUrlSync'

function mountUrlSync<TDifficulty extends string = string, TType extends string = string>(
  options?: UseBankUrlSyncOptions<TDifficulty, TType>,
) {
  let state!: ReturnType<typeof useBankUrlSync<TDifficulty, TType>>
  const Host = defineComponent({
    setup() {
      state = useBankUrlSync<TDifficulty, TType>(options)
      return state
    },
    template: '<div />',
  })
  const wrapper = mount(Host)
  return { wrapper, state }
}

describe('useBankUrlSync', () => {
  beforeEach(() => {
    routerState.hasUseRoute = true
    routerState.route = undefined
    routerState.router = { push: vi.fn() }
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('readBankQuery 正确解析合法参数并对非法值提供安全默认值', () => {
    expect(readBankQuery(undefined)).toEqual({
      keyword: '',
      difficulty: '',
      type: '',
      tag: '',
      status: 'ALL',
      page: 1,
    })

    expect(
      readBankQuery(
        {
          keyword: '  二分查找  ',
          difficulty: 'medium',
          type: 'SELECT',
          tag: '图论',
          status: 'unsolved',
          page: '4',
        },
        { validDifficulties: ['EASY', 'MEDIUM', 'HARD'] },
      ),
    ).toEqual({
      keyword: '二分查找',
      difficulty: 'MEDIUM',
      type: 'SELECT',
      tag: '图论',
      status: 'UNSOLVED',
      page: 4,
    })

    expect(
      readBankQuery(
        {
          keyword: ['dp', 'greedy'],
          difficulty: 'IMPOSSIBLE',
          status: 'INVALID',
          page: '-2',
        },
        { validDifficulties: ['EASY', 'MEDIUM', 'HARD'] },
      ),
    ).toEqual({
      keyword: 'dp',
      difficulty: '',
      type: '',
      tag: '',
      status: 'ALL',
      page: 1,
    })
  })

  it('buildBankQuery 省略默认 page=1、status=ALL 与空字符串，并保留上下文 query', () => {
    expect(
      buildBankQuery({
        keyword: '  ',
        difficulty: '',
        type: '',
        tag: '',
        status: 'ALL',
        page: 1,
      }),
    ).toEqual({})

    expect(
      buildBankQuery(
        {
          keyword: '动态规划',
          difficulty: 'HARD',
          status: 'SOLVED',
          page: 3,
        },
        { setId: '9', keyword: 'old', page: '2' },
      ),
    ).toEqual({
      setId: '9',
      keyword: '动态规划',
      difficulty: 'HARD',
      status: 'SOLVED',
      page: '3',
    })
  })

  it('在仅 mock 了 useRouter: () => ({ push })（缺 useRoute 或 router.replace）的环境中不报错', async () => {
    const pushMock = vi.fn()
    routerState.hasUseRoute = false
    routerState.route = undefined
    routerState.router = { push: pushMock }

    const { wrapper, state } = mountUrlSync()
    expect(state.keyword.value).toBe('')
    expect(state.status.value).toBe('ALL')
    expect(state.page.value).toBe(1)

    state.keyword.value = 'SQL'
    state.page.value = 2
    await flushPromises()

    expect(pushMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('在 route 存在且 router.replace 可用时双向同步 URL query（含外部传入 refs 与 reset）', async () => {
    const replaceMock = vi.fn().mockResolvedValue(undefined)
    const reactiveRoute = reactive<{ query: Record<string, unknown> }>({
      query: { keyword: '图论', difficulty: 'EASY', page: '2', setId: '7' },
    })
    routerState.hasUseRoute = true
    routerState.route = reactiveRoute
    routerState.router = { push: vi.fn(), replace: replaceMock }

    const keyword = ref('')
    const difficulty = ref<Difficulty | ''>('')
    const tag = ref('')
    const page = ref(1)

    const { wrapper, state } = mountUrlSync<Difficulty>({
      fields: ['keyword', 'difficulty', 'tag', 'status', 'page'],
      refs: { keyword, difficulty, tag, page },
    })
    expect(keyword.value).toBe('图论')
    expect(difficulty.value).toBe('EASY')
    expect(page.value).toBe(2)
    expect(replaceMock).not.toHaveBeenCalled()

    state.status.value = 'UNSOLVED'
    page.value = 1
    await flushPromises()

    expect(replaceMock).toHaveBeenCalledTimes(1)
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: {
        setId: '7',
        keyword: '图论',
        difficulty: 'EASY',
        status: 'UNSOLVED',
      },
    })

    // 调用 reset 清理筛选并回到默认页（page=1, status=ALL 均不写入冗余 query）
    state.reset()
    await flushPromises()
    expect(replaceMock).toHaveBeenCalledTimes(2)
    expect(replaceMock).toHaveBeenLastCalledWith({
      query: {
        setId: '7',
      },
    })

    // 模拟浏览器前进/后退更新 route.query，不触发回声 replace
    reactiveRoute.query = { setId: '7', keyword: 'BFS', page: '3' }
    await flushPromises()
    expect(keyword.value).toBe('BFS')
    expect(difficulty.value).toBe('')
    expect(state.status.value).toBe('ALL')
    expect(page.value).toBe(3)
    expect(replaceMock).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })
})
