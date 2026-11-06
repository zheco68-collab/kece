import { describe, expect, it, vi, beforeEach } from 'vitest'
import { useRoute, type LocationQuery, type RouteLocationNormalizedLoaded } from 'vue-router'
import { useQuestionNavContext } from './useQuestionNavContext'

vi.mock('vue-router', () => ({
  useRoute: vi.fn(),
}))

describe('useQuestionNavContext composable', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function mockRoute(query: LocationQuery): RouteLocationNormalizedLoaded {
    return { query } as unknown as RouteLocationNormalizedLoaded
  }

  it('课练上下文优先解析并生成返回路径与 Query', () => {
    vi.mocked(useRoute).mockReturnValue(mockRoute({ practiceCode: 'PRAC100', pqId: '9' }))

    const ctx = useQuestionNavContext()
    expect(ctx.practiceCode.value).toBe('PRAC100')
    expect(ctx.pqId.value).toBe('9')
    expect(ctx.hasContext.value).toBe(true)
    expect(ctx.contextQuery.value).toEqual({ practiceCode: 'PRAC100', pqId: '9' })
    expect(ctx.getBackRoute()).toEqual({
      path: '/practice/PRAC100',
      query: { pqId: '9' },
    })
  })

  it('题集上下文解析与生成返回路径', () => {
    vi.mocked(useRoute).mockReturnValue(mockRoute({ setId: '42', itemId: '108' }))

    const ctx = useQuestionNavContext()
    expect(ctx.setId.value).toBe('42')
    expect(ctx.itemId.value).toBe('108')
    expect(ctx.hasContext.value).toBe(true)
    expect(ctx.contextQuery.value).toEqual({ setId: '42', itemId: '108' })
    expect(ctx.getBackRoute()).toEqual({
      path: '/problem-sets/42',
      query: { itemId: '108' },
    })
  })

  it('无上下文时返回缺省路径且 hasContext 为 false', () => {
    vi.mocked(useRoute).mockReturnValue(mockRoute({}))

    const ctx = useQuestionNavContext()
    expect(ctx.hasContext.value).toBe(false)
    expect(ctx.contextQuery.value).toEqual({})
    expect(ctx.getBackRoute('/questions')).toEqual({ path: '/questions' })
  })
})

