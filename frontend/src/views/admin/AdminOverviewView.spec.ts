import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { ref, computed } from 'vue';
import AdminOverviewView from './AdminOverviewView.vue';
import type { JudgeClusterSummary } from '@/api/system';
import { useUserStore } from '@/stores/user';

const { adminApiMock, overviewApiMock, refreshClusterMock } = vi.hoisted(() => ({
  adminApiMock: {
    listQuestions: vi.fn(),
    listDbQuestions: vi.fn(),
    listObjectiveQuestions: vi.fn(),
    listCodeSubmissions: vi.fn(),
  },
  overviewApiMock: {
    activities: vi.fn(),
    submissionTrend: vi.fn(),
  },
  refreshClusterMock: vi.fn(),
}));

const clusterSummaryRef = ref<JudgeClusterSummary | null>(null);
const clusterErrorRef = ref<string | null>(null);
const clusterState = {
  summary: clusterSummaryRef,
  loading: ref(false),
  initialLoading: ref(false),
  error: clusterErrorRef,
  isStale: computed(() => false),
  isUnavailable: computed(() => clusterSummaryRef.value === null && clusterErrorRef.value !== null),
  readyPools: computed(() => clusterSummaryRef.value?.readyPools ?? 0),
  totalPools: computed(() => clusterSummaryRef.value?.totalPools ?? 0),
  pools: computed(() => clusterSummaryRef.value?.pools ?? []),
  refresh: refreshClusterMock,
};

vi.mock('@/api/admin', () => ({ adminApi: adminApiMock }));
vi.mock('@/api/adminOverview', () => ({ adminOverviewApi: overviewApiMock }));
vi.mock('@/features/judging/composables/useJudgeCluster', () => ({
  useJudgeCluster: () => clusterState,
}));

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="typeof to === \'string\' ? to : to.name"><slot /></a>',
};

let wrapper: ReturnType<typeof mount> | null = null;

describe('AdminOverviewView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clusterState.summary.value = {
      asOf: Date.now(),
      freshness: 'FRESH',
      readyPools: 2,
      totalPools: 2,
      pools: [
        { pool: 'cpp', name: 'C/C++', state: 'READY', readyNodes: 2, totalNodes: 2, pendingJobs: 0, activeJobs: 1 },
        { pool: 'sql', name: 'SQL', state: 'READY', readyNodes: 1, totalNodes: 1, pendingJobs: 0, activeJobs: 0 },
      ],
    };
    clusterState.error.value = null;

    overviewApiMock.activities.mockResolvedValue({
      asOf: '2026-11-15T10:00:00Z',
      active: { total: 0, items: [] },
      upcoming: { total: 0, items: [] },
      ended: { total: 0, items: [] },
    });
    overviewApiMock.submissionTrend.mockResolvedValue({
      asOf: '2026-11-15T10:00:00Z',
      buckets: [{ from: '2026-11-09', to: '2026-11-15', algoCount: 10, dbCount: 4 }],
    });
    adminApiMock.listQuestions.mockResolvedValue({ total: 12 });
    adminApiMock.listDbQuestions.mockResolvedValue({ total: 5 });
    adminApiMock.listObjectiveQuestions.mockResolvedValue({ total: 8 });
    adminApiMock.listCodeSubmissions.mockResolvedValue({
      list: [
        {
          submissionId: 501,
          userId: 3,
          username: 'student1',
          questionId: 12,
          questionTitle: '快速排序',
          language: 'CPP',
          status: 'ACCEPTED',
          score: 100,
          timeUsedMs: 12,
          memoryUsedKb: 1024,
          codeContent: '...',
          createdAt: '2026-11-15T09:50:00Z',
        },
      ],
      total: 1,
    });
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
  });

  function mountView() {
    const pinia = createPinia();
    const userStore = useUserStore(pinia);
    userStore.setAuth({
      token: 't',
      refreshToken: 'r',
      user: { id: 1, username: 'admin', email: null, roles: ['ADMIN'] },
    });
    wrapper = mount(AdminOverviewView, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    });
  }

  it('displays real metrics, compact empty exam state, real cluster pools, and recent submissions without scores', async () => {
    mountView();
    await flushPromises();

    // 4 summary metrics: active=0, upcoming=0, 7d submissions=14, total bank=25
    const values = wrapper!.findAll('.metric-value').map((n) => n.text());
    expect(values).toEqual(['0', '0', '14', '25']);

    expect(wrapper!.text()).toContain('当前没有正在进行的考务场次');
    expect(wrapper!.text()).toContain('全部就绪');
    expect(wrapper!.text()).toContain('2 / 2');
    expect(wrapper!.text()).toContain('#501');
    expect(wrapper!.text()).toContain('快速排序');
    expect(wrapper!.find('.recent-panel').text()).not.toContain('得分');
  });

  it('does not fabricate normal cluster status when judge cluster is unreachable', async () => {
    clusterState.summary.value = null;
    clusterState.error.value = '503 Service Unavailable';

    mountView();
    await flushPromises();

    expect(wrapper!.text()).toContain('不可达');
    expect(wrapper!.text()).not.toContain('全部就绪');
    expect(wrapper!.text()).toContain('503 Service Unavailable');
  });
});
