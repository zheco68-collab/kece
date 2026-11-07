import { computed, onMounted, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { adminApi } from '@/api/admin';
import { adminOverviewApi, type TrendRange } from '@/api/adminOverview';
import { ApiError } from '@/api/request';
import { useUserStore } from '@/stores/user';

interface OverviewResource<T> {
  data: T | null;
  loadedAt: number | null;
  pending: boolean;
  failed: boolean;
}

function emptyResource<T>(): OverviewResource<T> {
  return { data: null, loadedAt: null, pending: false, failed: false };
}

function useResource<T>(request: (signal: AbortSignal) => Promise<T>) {
  const state = shallowRef(emptyResource<T>());
  let inFlight: AbortController | null = null;

  async function load() {
    if (inFlight) return;
    const controller = new AbortController();
    inFlight = controller;
    state.value = { ...state.value, pending: true };
    try {
      const data = await request(controller.signal);
      if (inFlight === controller) {
        state.value = { data, loadedAt: Date.now(), pending: false, failed: false };
      }
    } catch (error) {
      if (inFlight !== controller) return;
      const accessDenied =
        error instanceof ApiError &&
        (error.status === 401 ||
          error.status === 403 ||
          error.code === 4002 ||
          error.code === 4003);
      state.value = {
        ...(accessDenied ? emptyResource<T>() : state.value),
        pending: false,
        failed: true,
      };
    } finally {
      if (inFlight === controller) inFlight = null;
    }
  }

  function reset() {
    inFlight?.abort();
    inFlight = null;
    state.value = emptyResource<T>();
  }

  onScopeDispose(reset);
  return { state, load, reset };
}

export function useAdminOverview() {
  const user = useUserStore();
  const isAdmin = computed(() => user.hasRole('ADMIN'));
  const scope = computed(() => {
    if (!user.isLoggedIn || !user.userInfo) return null;
    if (isAdmin.value) return `ADMIN:${user.userInfo.id}`;
    return user.hasRole('TEACHER') ? `TEACHER:${user.userInfo.id}` : null;
  });
  const activities = useResource(adminOverviewApi.activities);
  const range = ref<TrendRange>(7);
  const trends = {
    7: useResource((signal) => adminOverviewApi.submissionTrend(7, signal)),
    28: useResource((signal) => adminOverviewApi.submissionTrend(28, signal)),
  };
  const trend = computed(() => trends[range.value].state.value);
  const page = { page: 1, size: 1 };
  const catalog = [
    {
      title: '编程题',
      route: 'admin-questions',
      ...useResource(async (signal) => (await adminApi.listQuestions(page, signal)).total),
    },
    {
      title: '数据库题',
      route: 'admin-db-questions',
      ...useResource(async (signal) => (await adminApi.listDbQuestions(page, signal)).total),
    },
    {
      title: '客观题',
      route: 'admin-objective-questions',
      ...useResource(async (signal) => (await adminApi.listObjectiveQuestions(page, signal)).total),
    },
  ];
  const catalogTotal = computed(() => {
    let total = 0;
    for (const item of catalog) {
      const { data, failed } = item.state.value;
      if (data === null || failed) return null;
      total += data;
    }
    return total;
  });
  const refreshing = computed(
    () =>
      activities.state.value.pending ||
      trend.value.pending ||
      catalog.some((item) => item.state.value.pending),
  );
  const wallTime = ref(Date.now());
  const serverNow = computed(() => {
    const { data, loadedAt, failed } = activities.state.value;
    if (!data || loadedAt === null || failed) return null;
    return Date.parse(data.asOf) + Math.max(0, wallTime.value - loadedAt);
  });
  let mounted = false;
  let pollTimer: number | undefined;
  let clockTimer: number | undefined;

  function refresh() {
    if (!scope.value) return;
    void activities.load();
    void trends[range.value].load();
    for (const item of catalog) void item.load();
  }

  function loadTrend() {
    if (scope.value) void trends[range.value].load();
  }

  function changeRange(days: TrendRange) {
    if (range.value === days) return;
    range.value = days;
    loadTrend();
  }

  function stopTimers() {
    window.clearInterval(pollTimer);
    window.clearInterval(clockTimer);
  }

  function startTimers() {
    stopTimers();
    if (document.hidden || !scope.value) return;
    wallTime.value = Date.now();
    pollTimer = window.setInterval(() => void activities.load(), 60_000);
    clockTimer = window.setInterval(() => (wallTime.value = Date.now()), 1_000);
  }

  function onVisibilityChange() {
    startTimers();
    if (document.hidden || !scope.value) return;
    const last = activities.state.value.loadedAt;
    if (last === null || Date.now() - last >= 60_000) void activities.load();
  }

  watch(
    scope,
    () => {
      activities.reset();
      trends[7].reset();
      trends[28].reset();
      for (const item of catalog) item.reset();
      if (mounted) {
        startTimers();
        refresh();
      }
    },
    { flush: 'sync' },
  );
  onMounted(() => {
    mounted = true;
    document.addEventListener('visibilitychange', onVisibilityChange);
    startTimers();
    refresh();
  });
  onScopeDispose(() => {
    mounted = false;
    document.removeEventListener('visibilitychange', onVisibilityChange);
    stopTimers();
  });

  return {
    isAdmin,
    activities: activities.state,
    reloadActivities: activities.load,
    catalog,
    catalogTotal,
    range,
    trend,
    loadTrend,
    changeRange,
    refresh,
    refreshing,
    serverNow,
  };
}
