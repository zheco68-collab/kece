import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import ExamListView from './ExamListView.vue';

const { examApiMock, pushMock, infoMock, userState } = vi.hoisted(() => ({
  examApiMock: {
    listExams: vi.fn(),
    ranking: vi.fn(),
  },
  pushMock: vi.fn(),
  infoMock: vi.fn(),
  userState: { isLoggedIn: true, userInfo: { id: 10, username: 'stu' } },
}));

vi.mock('@/api/exam', () => ({ examApi: examApiMock }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }));
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ info: infoMock }) }));
vi.mock('@/stores/user', () => ({ useUserStore: () => userState }));

let wrapper: ReturnType<typeof mount> | null = null;

describe('ExamListView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    userState.isLoggedIn = true;

    const now = Date.now();
    const fmt = (offsetMs: number) => new Date(now + offsetMs).toISOString();

    examApiMock.listExams.mockResolvedValue({
      list: [
        {
          id: 1,
          title: '进行中的期中机试',
          description: '进行中测试说明',
          durationMinutes: 120,
          startTime: fmt(-30 * 60_000),
          endTime: fmt(90 * 60_000),
          mode: 'ACM',
          status: 1,
          sessionStatus: 'ONGOING',
          submitted: false,
          createdBy: 1,
          questions: [],
          createdAt: fmt(-86_400_000),
          updatedAt: fmt(-86_400_000),
        },
        {
          id: 2,
          title: '未开始的下周测评',
          description: '尚未开考',
          durationMinutes: 90,
          startTime: fmt(86_400_000),
          endTime: fmt(90_000_000),
          mode: 'OI',
          status: 1,
          sessionStatus: 'UPCOMING',
          submitted: false,
          createdBy: 1,
          questions: [],
          createdAt: fmt(-86_400_000),
          updatedAt: fmt(-86_400_000),
        },
        {
          id: 3,
          title: '已结束的上周测试',
          description: '已收卷归档',
          durationMinutes: 60,
          startTime: fmt(-120 * 60_000),
          endTime: fmt(-60 * 60_000),
          mode: 'OI',
          status: 1,
          sessionStatus: 'ENDED',
          submitted: true,
          createdBy: 1,
          questions: [],
          createdAt: fmt(-86_400_000),
          updatedAt: fmt(-86_400_000),
        },
      ],
      total: 3,
    });
    examApiMock.ranking.mockResolvedValue([]);
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
    document.body.innerHTML = '';
  });

  it('renders status-specific actions and never shows 实时排名 on ended or upcoming cards', async () => {
    wrapper = mount(ExamListView, { attachTo: document.body });
    await flushPromises();

    const cards = wrapper.findAll('.exam-card');
    expect(cards).toHaveLength(3);

    // Ongoing card
    expect(cards[0]!.classes()).toContain('card-ongoing');
    expect(cards[0]!.text()).toContain('进入考试');
    expect(cards[0]!.text()).toContain('实时排名');

    // Upcoming card
    expect(cards[1]!.text()).toContain('查看安排');
    expect(cards[1]!.text()).not.toContain('实时排名');

    // Ended card
    expect(cards[2]!.text()).toContain('查看结果');
    expect(cards[2]!.text()).toContain('查看榜单');
    expect(cards[2]!.text()).not.toContain('实时排名');

    // Open schedule modal on upcoming card
    await cards[1]!.get('.btn-schedule').trigger('click');
    await nextTick();
    expect(document.body.querySelector('.schedule-modal')?.textContent).toContain('尚未开考');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await nextTick();
    expect(document.body.querySelector('.schedule-modal')).toBeNull();

    // Open ended ranking modal
    await cards[2]!.get('.btn-ranking').trigger('click');
    await flushPromises();
    const rankingText = document.body.querySelector('.modal-card')?.textContent ?? '';
    expect(rankingText).toContain('考试最终榜单');
    expect(rankingText).not.toContain('实时考生天梯榜');
    expect(rankingText).not.toContain('自动刷新');
  });
});
