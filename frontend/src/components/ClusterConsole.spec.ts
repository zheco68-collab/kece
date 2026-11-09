import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref, computed } from 'vue'
import ClusterConsole from './ClusterConsole.vue'
import * as judgeClusterModule from '@/features/judging/composables/useJudgeCluster'

describe('ClusterConsole.vue', () => {
  const refreshMock = vi.fn().mockResolvedValue(undefined)
  const mockState = {
    summary: ref({
      asOf: 1700000000000,
      freshness: 'FRESH' as const,
      readyPools: 5,
      totalPools: 5,
      pools: [
        { pool: 'cpp', name: 'C++', state: 'READY', readyNodes: 2, totalNodes: 2, pendingJobs: 0, activeJobs: 0 },
        { pool: 'java', name: 'Java', state: 'READY', readyNodes: 3, totalNodes: 3, pendingJobs: 0, activeJobs: 0 },
        { pool: 'python', name: 'Python', state: 'READY', readyNodes: 1, totalNodes: 1, pendingJobs: 0, activeJobs: 0 },
        { pool: 'mysql', name: 'MySQL', state: 'READY', readyNodes: 1, totalNodes: 1, pendingJobs: 0, activeJobs: 0 },
        { pool: 'arkts', name: 'ArkTS', state: 'READY', readyNodes: 1, totalNodes: 1, pendingJobs: 0, activeJobs: 0 },
      ],
    }),
    loading: ref(false),
    initialLoading: ref(false),
    error: ref<string | null>(null),
    isStale: computed(() => false),
    isUnavailable: computed(() => false),
    readyPools: computed(() => 5),
    totalPools: computed(() => 5),
    pools: computed(() => [
      { pool: 'cpp', name: 'C++', state: 'READY', readyNodes: 2, totalNodes: 2, pendingJobs: 0, activeJobs: 0 },
      { pool: 'java', name: 'Java', state: 'READY', readyNodes: 3, totalNodes: 3, pendingJobs: 0, activeJobs: 0 },
      { pool: 'python', name: 'Python', state: 'READY', readyNodes: 1, totalNodes: 1, pendingJobs: 0, activeJobs: 0 },
      { pool: 'mysql', name: 'MySQL', state: 'READY', readyNodes: 1, totalNodes: 1, pendingJobs: 0, activeJobs: 0 },
      { pool: 'arkts', name: 'ArkTS', state: 'READY', readyNodes: 1, totalNodes: 1, pendingJobs: 0, activeJobs: 0 },
    ]),
    refresh: refreshMock,
  }

  beforeEach(() => {
    vi.spyOn(judgeClusterModule, 'useJudgeCluster').mockReturnValue(mockState)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('正常渲染集群监控面板内容', async () => {
    const wrapper = mount(ClusterConsole)
    await flushPromises()

    expect(wrapper.text()).toContain('KECE // POOLS')
    expect(wrapper.text()).toContain('评测机集群遥测')
    expect(wrapper.text()).toContain('5/5 POOLS READY')
    expect(wrapper.text()).toContain('C++')
    expect(wrapper.text()).toContain('Java')
  })

  it('鼠标悬停在卡片上触发 3D 倾斜与光标跟随', async () => {
    const wrapper = mount(ClusterConsole)
    await flushPromises()

    const card = wrapper.get('.cluster-console')
    const wrapperEl = wrapper.get('.cluster-console-wrapper')

    // Mock getBoundingClientRect
    vi.spyOn(card.element, 'getBoundingClientRect').mockReturnValue({
      left: 100,
      top: 100,
      width: 400,
      height: 300,
      right: 500,
      bottom: 400,
      x: 100,
      y: 100,
      toJSON: () => {},
    })

    // Mouse enter & move
    await wrapperEl.trigger('mouseenter')
    await wrapperEl.trigger('mousemove', { clientX: 200, clientY: 200 })
    await flushPromises()

    expect(card.classes()).toContain('is-tilting')
    expect(card.attributes('style')).toContain('perspective(1200px)')

    // Mouse leave resets tilt
    await wrapperEl.trigger('mouseleave')
    await flushPromises()

    expect(card.classes()).not.toContain('is-tilting')
    expect(card.attributes('style')).toContain('rotateX(0deg) rotateY(0deg)')
  })

  it('点击刷新按钮触发扫描线动画与状态更新', async () => {
    const wrapper = mount(ClusterConsole)
    await flushPromises()

    const refreshBtn = wrapper.get('.refresh-btn')
    await refreshBtn.trigger('click')
    await flushPromises()

    expect(refreshMock).toHaveBeenCalledWith(true)
    expect(wrapper.find('.scanline-track').exists()).toBe(true)
    expect(wrapper.find('.scanline-bar').exists()).toBe(true)
    expect(wrapper.find('.scanline-glow').exists()).toBe(true)
  })
})
