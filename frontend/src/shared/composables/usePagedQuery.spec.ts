import { describe, expect, it, vi } from 'vitest'
import { usePagedQuery, type PagedResult } from './usePagedQuery'

interface TestRow {
  id: number
  name: string
}

describe('usePagedQuery composable', () => {
  it('初始化时自动执行 fetcher 并正确填充分页数据与状态', async () => {
    const mockFetcher = vi.fn<(params: { keyword: string; page: number; size: number }) => Promise<PagedResult<TestRow>>>().mockResolvedValue({
      list: [{ id: 1, name: 'Item 1' }],
      total: 10,
    })

    const { list, total, loading, error } = usePagedQuery<TestRow, { keyword: string }>({
      fetcher: mockFetcher,
      defaultSize: 10,
      defaultParams: { keyword: 'test' },
    })

    expect(loading.value).toBe(true)
    await new Promise((r) => setTimeout(r, 0))

    expect(mockFetcher).toHaveBeenCalledWith({ keyword: 'test', page: 1, size: 10 })
    expect(list.value).toEqual([{ id: 1, name: 'Item 1' }])
    expect(total.value).toBe(10)
    expect(loading.value).toBe(false)
    expect(error.value).toBeNull()
  })

  it('防竞态保护：迟到的旧请求响应被丢弃，仅采纳最新请求的数据', async () => {
    let resolveFirst: (value: PagedResult<TestRow>) => void = () => {
      throw new Error('测试未初始化')
    }
    const firstPromise = new Promise<PagedResult<TestRow>>((resolve) => {
      resolveFirst = resolve
    })

    const mockFetcher = vi
      .fn<(params: { page: number; size: number }) => Promise<PagedResult<TestRow>>>()
      .mockImplementation(({ page }) => {
      if (page === 1) {
        return firstPromise
      }
      return Promise.resolve({
        list: [{ id: 2, name: 'Page 2 item' }],
        total: 40,
      })
    })

    const query = usePagedQuery<TestRow>({
      fetcher: mockFetcher,
      defaultSize: 20,
      immediate: false,
    })

    // 发起第 1 页请求（慢）
    const p1 = query.load(1)
    // 立即发起第 2 页请求（快）
    const p2 = query.load(2)

    await p2
    expect(query.list.value).toEqual([{ id: 2, name: 'Page 2 item' }])
    expect(query.page.value).toBe(2)

    // 让第 1 个慢请求返回
    resolveFirst({
      list: [{ id: 1, name: 'Outdated item' }],
      total: 40,
    })
    await p1

    // 依然保持第 2 页的数据，不会被旧响应覆盖
    expect(query.list.value).toEqual([{ id: 2, name: 'Page 2 item' }])
  })

  it('自动回退保护：当前页超出最大页码时自动加载最大有效页', async () => {
    let currentTotal = 30
    const mockFetcher = vi
      .fn<(params: { page: number; size: number }) => Promise<PagedResult<TestRow>>>()
      .mockImplementation(({ page }) => {
      return Promise.resolve({
        list: [{ id: page, name: `Item on page ${page}` }],
        total: currentTotal,
      })
    })

    const query = usePagedQuery<TestRow>({
      fetcher: mockFetcher,
      defaultSize: 10,
      immediate: false,
    })

    // 先加载第 3 页 (total 30, size 10 -> maxPage 3)
    await query.load(3)
    expect(query.page.value).toBe(3)

    // 模拟数据被删除，total 缩减至 15 (maxPage 变为 2)
    currentTotal = 15
    await query.load()

    // 应该自动触发回退到第 2 页
    expect(query.page.value).toBe(2)
  })

  it('错误捕获：fetcher 报错时更新 error 且 loading 正常复位', async () => {
    const mockFetcher = vi
      .fn<(params: { page: number; size: number }) => Promise<PagedResult<TestRow>>>()
      .mockRejectedValue(new Error('Network error'))

    const query = usePagedQuery<TestRow>({
      fetcher: mockFetcher,
      immediate: false,
    })

    await query.load(1)
    expect(query.loading.value).toBe(false)
    expect(query.error.value).toBe('Network error')
  })
})
