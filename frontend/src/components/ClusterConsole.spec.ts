import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { computed, ref } from 'vue';
import type { JudgeClusterSummary } from '@/api/system';
import ClusterConsole from './ClusterConsole.vue';
import * as judgeClusterModule from '@/features/judging/composables/useJudgeCluster';

describe('ClusterConsole', () => {
  const summary = ref<JudgeClusterSummary | null>(null);
  const loading = ref(false);
  const initialLoading = ref(false);
  const error = ref<string | null>(null);
  const refresh = vi.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    summary.value = {
      asOf: 1700000000000,
      freshness: 'FRESH',
      readyPools: 1,
      totalPools: 2,
      pools: [
        {
          pool: 'cpp',
          name: 'C++',
          state: 'READY',
          readyNodes: 2,
          totalNodes: 2,
          pendingJobs: 0,
          activeJobs: 1,
        },
        {
          pool: 'java',
          name: 'Java',
          state: 'OFFLINE',
          readyNodes: 0,
          totalNodes: 3,
          pendingJobs: 4,
          activeJobs: 0,
        },
      ],
    };
    loading.value = false;
    initialLoading.value = false;
    error.value = null;
    refresh.mockClear();
    vi.spyOn(judgeClusterModule, 'useJudgeCluster').mockReturnValue({
      summary,
      loading,
      initialLoading,
      error,
      isStale: computed(() => summary.value?.freshness === 'STALE'),
      isUnavailable: computed(() => !summary.value && !!error.value && !initialLoading.value),
      readyPools: computed(() => summary.value?.readyPools ?? 0),
      totalPools: computed(() => summary.value?.totalPools ?? 5),
      pools: computed(() => summary.value?.pools ?? []),
      refresh,
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('汇总各专池任务并显示离线专池和节点数量', () => {
    const wrapper = mount(ClusterConsole);
    expect(wrapper.findAll('dd').map((item) => item.text())).toEqual(['1 / 2', '1', '4']);
    const [, java] = wrapper.findAll('tbody tr');
    expect(java.get('th').text()).toBe('Java');
    expect(java.findAll('td').map((item) => item.text())).toEqual(['离线', '0/3', '0', '4']);
    wrapper.unmount();
  });

  it('保留过期快照并标注数据来源', () => {
    summary.value!.freshness = 'STALE';
    const wrapper = mount(ClusterConsole);
    expect(wrapper.text()).toContain('显示上次数据');
    expect(wrapper.text()).toContain('Java');
    wrapper.unmount();
  });

  it('接口失败时显示不可用并允许手动刷新', async () => {
    summary.value = null;
    error.value = 'request failed';
    const wrapper = mount(ClusterConsole);
    expect(wrapper.text()).toContain('暂时无法获取评测状态');
    expect(wrapper.findAll('dd').map((item) => item.text())).toEqual(['—', '—', '—']);
    await wrapper.get('button[aria-label="刷新评测状态"]').trigger('click');
    expect(refresh).toHaveBeenCalledWith(true);
    wrapper.unmount();
  });

  it('在鼠标悬停时提供不超过 1.5° 的阻尼倾斜并保持刷新按钮可点击', async () => {
    const wrapper = mount(ClusterConsole);
    const panel = wrapper.get('section.cluster-console');

    vi.spyOn(panel.element, 'getBoundingClientRect').mockReturnValue({
      left: 100,
      top: 50,
      width: 400,
      height: 300,
      right: 500,
      bottom: 350,
      x: 100,
      y: 50,
      toJSON: () => ({}),
    } as DOMRect);

    await panel.trigger('mousemove', { clientX: 700, clientY: 500 });
    const transform = (panel.element as HTMLElement).style.transform;
    expect(transform).toContain('perspective(900px)');

    const rotateX = Number(transform.match(/rotateX\((-?[\d.]+)deg\)/)?.[1]);
    const rotateY = Number(transform.match(/rotateY\((-?[\d.]+)deg\)/)?.[1]);
    expect(Math.abs(rotateX)).toBeLessThanOrEqual(1.5);
    expect(Math.abs(rotateY)).toBeLessThanOrEqual(1.5);
    expect(rotateX).toBe(-1.5);
    expect(rotateY).toBe(1.5);

    await wrapper.get('button[aria-label="刷新评测状态"]').trigger('click');
    expect(refresh).toHaveBeenCalledWith(true);

    await panel.trigger('mouseleave');
    expect((panel.element as HTMLElement).style.transform).toBe('');
    wrapper.unmount();
  });

  it('在减少动态效果偏好下禁用 3D 倾斜并在加载时旋转刷新图标', async () => {
    const originalMatchMedia = window.matchMedia;
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
    });

    try {
      const wrapper = mount(ClusterConsole);
      const panel = wrapper.get('section.cluster-console');

      vi.spyOn(panel.element, 'getBoundingClientRect').mockReturnValue({
        left: 0,
        top: 0,
        width: 400,
        height: 300,
        right: 400,
        bottom: 300,
        x: 0,
        y: 0,
        toJSON: () => ({}),
      } as DOMRect);

      await panel.trigger('mousemove', { clientX: 400, clientY: 300 });
      expect((panel.element as HTMLElement).style.transform).toBe('');

      loading.value = true;
      await wrapper.vm.$nextTick();
      const refreshBtn = wrapper.get('button[aria-label="刷新评测状态"]');
      expect(refreshBtn.attributes('disabled')).toBeDefined();
      expect(refreshBtn.text()).toContain('刷新中');
      expect(refreshBtn.get('svg').classes()).toContain('is-spinning');
      wrapper.unmount();
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        writable: true,
        value: originalMatchMedia,
      });
    }
  });
});
