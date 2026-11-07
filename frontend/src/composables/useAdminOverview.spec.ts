import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { defineComponent, h } from 'vue';
import type { OverviewActivities } from '@/api/adminOverview';
import { useUserStore } from '@/stores/user';
import { useAdminOverview } from './useAdminOverview';

const api = vi.hoisted(() => ({
  activities: vi.fn(),
  submissionTrend: vi.fn(),
  listQuestions: vi.fn(),
  listDbQuestions: vi.fn(),
  listObjectiveQuestions: vi.fn(),
}));
vi.mock('@/api/adminOverview', () => ({ adminOverviewApi: api }));
vi.mock('@/api/admin', () => ({ adminApi: api }));

function snapshot(title: string): OverviewActivities {
  return {
    asOf: '2026-11-07T06:30:00Z',
    active: {
      total: 1,
      items: [{ kind: 'PRACTICE', id: 1, title, startAt: null, endAt: null, memberCount: 0 }],
    },
    upcoming: { total: 0, items: [] },
    ended: { total: 0, items: [] },
  };
}

let cleanup: (() => void) | undefined;

function mountOverview() {
  const pinia = createPinia();
  const user = useUserStore(pinia);
  function setTeacher(id: number) {
    user.setAuth({
      token: `teacher-${id}`,
      refreshToken: `refresh-${id}`,
      user: { id, username: `teacher-${id}`, email: null, roles: ['TEACHER'] },
    });
  }
  setTeacher(1);
  let page!: ReturnType<typeof useAdminOverview>;
  const wrapper = mount(
    defineComponent({
      setup() {
        page = useAdminOverview();
        return () => h('div');
      },
    }),
    { global: { plugins: [pinia] } },
  );
  cleanup = () => wrapper.unmount();
  return { page, setTeacher };
}

describe('admin overview data', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.resetAllMocks();
    api.activities.mockResolvedValue(snapshot('我的课练'));
    api.submissionTrend.mockResolvedValue({ asOf: '2026-11-07T06:30:00Z', buckets: [] });
    api.listQuestions.mockResolvedValue({ total: 3 });
    api.listDbQuestions.mockResolvedValue({ total: 2 });
    api.listObjectiveQuestions.mockResolvedValue({ total: 0 });
  });

  afterEach(() => {
    cleanup?.();
    cleanup = undefined;
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('keeps other panels usable and allows retrying only the failed count', async () => {
    api.listDbQuestions.mockRejectedValueOnce(new Error('database count unavailable'));
    const { page } = mountOverview();
    await flushPromises();
    expect(page.activities.value.data?.active.total).toBe(1);
    expect(page.catalog[0]?.state.value.data).toBe(3);
    expect(page.catalog[1]?.state.value).toMatchObject({ data: null, failed: true });
    expect(page.catalog[2]?.state.value.data).toBe(0);
    expect(page.catalogTotal.value).toBeNull();

    await page.catalog[1]?.load();
    expect(page.catalogTotal.value).toBe(5);
    expect(api.activities).toHaveBeenCalledTimes(1);
    expect(api.listDbQuestions).toHaveBeenCalledTimes(2);
  });

  it('discards the old response when the authenticated teacher changes', async () => {
    let resolveOld!: (value: OverviewActivities) => void;
    api.activities.mockImplementationOnce(
      () =>
        new Promise<OverviewActivities>((resolve) => {
          resolveOld = resolve;
        }),
    );
    api.activities.mockResolvedValueOnce(snapshot('另一位教师的课练'));
    const { page, setTeacher } = mountOverview();
    const signal: AbortSignal = api.activities.mock.calls[0]?.[0];
    page.refresh();
    expect(api.activities).toHaveBeenCalledTimes(1);
    setTeacher(2);
    expect(signal.aborted).toBe(true);
    expect(page.activities.value.data).toBeNull();
    await flushPromises();
    resolveOld(snapshot('旧账号课练'));
    await flushPromises();
    expect(page.activities.value.data?.active.items[0]?.title).toBe('另一位教师的课练');
  });

  it('suspends polling while hidden and refreshes only the activities on resume', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    mountOverview();
    await flushPromises();
    hidden.mockReturnValue(true);
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(61_000);
    expect(api.activities).toHaveBeenCalledTimes(1);
    hidden.mockReturnValue(false);
    document.dispatchEvent(new Event('visibilitychange'));
    await flushPromises();
    expect(api.activities).toHaveBeenCalledTimes(2);
    expect(api.submissionTrend).toHaveBeenCalledTimes(1);
    expect(api.listQuestions).toHaveBeenCalledTimes(1);
  });
});
