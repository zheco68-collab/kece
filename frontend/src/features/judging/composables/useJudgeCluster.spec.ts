import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { systemApi, type JudgeClusterSummary } from '@/api/system'
import { provideJudgeCluster, useJudgeCluster } from './useJudgeCluster'

describe('useJudgeCluster composable', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  const mockSummary: JudgeClusterSummary = {
    asOf: 1700000000000,
    freshness: 'FRESH',
    readyPools: 1,
    totalPools: 5,
    pools: [
      {
        pool: 'python',
        name: 'Python',
        state: 'READY',
        readyNodes: 1,
        totalNodes: 1,
        pendingJobs: 0,
        activeJobs: 0,
      },
      {
        pool: 'cpp',
        name: 'C++',
        state: 'PENDING_SWITCH',
        readyNodes: 0,
        totalNodes: 0,
        pendingJobs: 0,
        activeJobs: 0,
      },
      {
        pool: 'java',
        name: 'Java',
        state: 'PENDING_SWITCH',
        readyNodes: 0,
        totalNodes: 0,
        pendingJobs: 0,
        activeJobs: 0,
      },
      {
        pool: 'mysql',
        name: 'MySQL',
        state: 'PENDING_SWITCH',
        readyNodes: 0,
        totalNodes: 0,
        pendingJobs: 0,
        activeJobs: 0,
      },
      {
        pool: 'arkts',
        name: 'ArkTS',
        state: 'PENDING_SWITCH',
        readyNodes: 0,
        totalNodes: 0,
        pendingJobs: 0,
        activeJobs: 0,
      },
    ],
  }

  it('挂载时发起首次查询并正确向下 provide/inject 共享状态', async () => {
    const getSummarySpy = vi
      .spyOn(systemApi, 'getClusterSummary')
      .mockResolvedValue(mockSummary)

    let injectedState: ReturnType<typeof useJudgeCluster> | null = null

    const ChildComponent = defineComponent({
      setup() {
        injectedState = useJudgeCluster()
        return () => h('div', `ready: ${injectedState?.readyPools.value}`)
      },
    })

    const ParentComponent = defineComponent({
      setup() {
        provideJudgeCluster(30000)
        return () => h(ChildComponent)
      },
    })

    const wrapper = mount(ParentComponent)
    expect(getSummarySpy).toHaveBeenCalledTimes(1)

    await flushPromises()

    expect(injectedState).not.toBeNull()
    expect(injectedState!.readyPools.value).toBe(1)
    expect(injectedState!.totalPools.value).toBe(5)
    expect(injectedState!.pools.value.length).toBe(5)
    expect(injectedState!.isStale.value).toBe(true) // 1700000000000 is older than 60s
    expect(wrapper.text()).toContain('ready: 1')

    wrapper.unmount()
  })

  it('同一时刻只允许一个请求在途，杜绝重复并发请求', async () => {
    let resolveFirst: (val: JudgeClusterSummary) => void = () => {}
    const slowPromise = new Promise<JudgeClusterSummary>((resolve) => {
      resolveFirst = resolve
    })

    const getSummarySpy = vi
      .spyOn(systemApi, 'getClusterSummary')
      .mockReturnValue(slowPromise)

    const TestComponent = defineComponent({
      setup() {
        const cluster = provideJudgeCluster(30000)
        return { cluster }
      },
      template: '<div>test</div>',
    })

    const wrapper = mount(TestComponent)
    expect(getSummarySpy).toHaveBeenCalledTimes(1)

    // 在途时再次调用 refresh
    wrapper.vm.cluster.refresh()
    expect(getSummarySpy).toHaveBeenCalledTimes(1) // 不会重复发起

    resolveFirst(mockSummary)
    await flushPromises()

    expect(wrapper.vm.cluster.readyPools.value).toBe(1)
    wrapper.unmount()
  })
})
