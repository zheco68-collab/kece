import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { useSubmissionMonitor } from './useSubmissionMonitor'

interface TestSubmission {
  id: number
  status: 'JUDGING' | 'ACCEPTED'
  progress?: { finishedCases: number; totalCases: number }
}

describe('useSubmissionMonitor composable', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('监听到合法 submissionId 时立即首查，并在达成终态时停止轮询且触发 onSuccess', async () => {
    const subId = ref<number | null>(null)
    const onSuccess = vi.fn()

    let callCount = 0
    const mockFetcher = vi.fn<(id: number) => Promise<TestSubmission>>().mockImplementation(async (id) => {
      callCount++
      if (callCount === 1) {
        return { id, status: 'JUDGING', progress: { finishedCases: 1, totalCases: 2 } }
      }
      return { id, status: 'ACCEPTED', progress: { finishedCases: 2, totalCases: 2 } }
    })

    const { result, polling } = useSubmissionMonitor({
      submissionId: subId,
      fetcher: mockFetcher,
      isTerminal: (res) => res.status === 'ACCEPTED',
      onSuccess,
      intervalMs: 1000,
    })

    expect(polling.value).toBe(false)
    subId.value = 88

    // 立即首查
    await flushPromises()
    expect(mockFetcher).toHaveBeenCalledTimes(1)
    expect(result.value?.status).toBe('JUDGING')
    expect(polling.value).toBe(true)
    expect(onSuccess).not.toHaveBeenCalled()

    // 推进 1000ms 触发第 2 次轮询
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(mockFetcher).toHaveBeenCalledTimes(2)
    expect(result.value?.status).toBe('ACCEPTED')
    expect(polling.value).toBe(false)
    expect(onSuccess).toHaveBeenCalledWith(
      expect.objectContaining({ id: 88, status: 'ACCEPTED' }),
    )
  })

  it('切换 submissionId 时立即中断旧轮询，丢弃迟到响应并重置状态', async () => {
    const subId = ref<number | null>(101)

    let resolveFirst: (value: TestSubmission) => void = () => {
      throw new Error('测试未初始化')
    }
    const slowPromise = new Promise<TestSubmission>((resolve) => {
      resolveFirst = resolve
    })

    const mockFetcher = vi.fn<(id: number) => Promise<TestSubmission>>().mockImplementation((id) => {
      if (id === 101) {
        return slowPromise
      }
      return Promise.resolve({ id, status: 'ACCEPTED' })
    })

    const { result } = useSubmissionMonitor<TestSubmission>({
      submissionId: subId,
      fetcher: mockFetcher,
      isTerminal: (res) => res.status === 'ACCEPTED',
      intervalMs: 1000,
    })

    // 切换为 102
    subId.value = 102
    await flushPromises()

    expect(result.value?.id).toBe(102)

    // 让 101 的慢响应返回
    resolveFirst({ id: 101, status: 'JUDGING' })
    await flushPromises()

    // 结果仍然必须是 102，不能被 101 覆盖
    expect(result.value?.id).toBe(102)
  })

  it('fetcher 报错时记录错误信息并停止轮询', async () => {
    const subId = ref<number | null>(99)
    const mockFetcher = vi
      .fn<(id: number) => Promise<TestSubmission>>()
      .mockRejectedValue(new Error('评测服务离线'))

    const { error, polling } = useSubmissionMonitor({
      submissionId: subId,
      fetcher: mockFetcher,
      isTerminal: () => false,
    })

    await flushPromises()

    expect(error.value).toBe('评测服务离线')
    expect(polling.value).toBe(false)
  })

  it('A -> B -> A 快速切换时，A 的首个慢请求迟到响应不能覆盖第二次切换到 A 的最新结果', async () => {
    const subId = ref<number | null>(null)

    let resolveFirstA: (val: TestSubmission) => void = () => {}
    const slowPromiseA1 = new Promise<TestSubmission>((resolve) => {
      resolveFirstA = resolve
    })

    const mockFetcher = vi.fn<(id: number) => Promise<TestSubmission>>().mockImplementation((id) => {
      if (id === 201) {
        if (mockFetcher.mock.calls.filter((c) => c[0] === 201).length === 1) {
          return slowPromiseA1 // 第一次针对 201 的慢请求
        }
        return Promise.resolve({ id: 201, status: 'ACCEPTED' }) // 第二次针对 201 的即时响应
      }
      return Promise.resolve({ id: 202, status: 'ACCEPTED' })
    })

    const { result } = useSubmissionMonitor<TestSubmission>({
      submissionId: subId,
      fetcher: mockFetcher,
      isTerminal: (res) => res.status === 'ACCEPTED',
    })

    // 1. 设置为 201
    subId.value = 201
    await flushPromises()

    // 2. 切换为 202
    subId.value = 202
    await flushPromises()
    expect(result.value?.id).toBe(202)

    // 3. 再次切换回 201
    subId.value = 201
    await flushPromises()
    expect(result.value?.id).toBe(201)
    expect(result.value?.status).toBe('ACCEPTED')

    // 4. 让第一次针对 201 的慢请求返回（返回状态为 JUDGING）
    resolveFirstA({ id: 201, status: 'JUDGING' })
    await flushPromises()

    // 结果仍然必须保持为最新的 ACCEPTED，不能被第一次的迟到 JUDGING 覆盖
    expect(result.value?.id).toBe(201)
    expect(result.value?.status).toBe('ACCEPTED')
  })

  it('手动调用 stop 之后迟到响应被丢弃，且不再安排新的轮询', async () => {
    const subId = ref<number | null>(301)

    let resolvePending: (val: TestSubmission) => void = () => {}
    const pendingPromise = new Promise<TestSubmission>((resolve) => {
      resolvePending = resolve
    })

    const mockFetcher = vi.fn<(id: number) => Promise<TestSubmission>>().mockReturnValue(pendingPromise)

    const { result, polling, stop } = useSubmissionMonitor<TestSubmission>({
      submissionId: subId,
      fetcher: mockFetcher,
      isTerminal: (res) => res.status === 'ACCEPTED',
    })

    await flushPromises()
    expect(polling.value).toBe(true)

    // 在请求在途时手动 stop
    stop()
    expect(polling.value).toBe(false)

    // 迟到响应返回
    resolvePending({ id: 301, status: 'JUDGING' })
    await flushPromises()

    // 结果不被写入，polling 仍为 false
    expect(result.value).toBeNull()
    expect(polling.value).toBe(false)
  })
})
