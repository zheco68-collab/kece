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
});
