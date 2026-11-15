import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import AdminSubmissionsView from './AdminSubmissionsView.vue';

const { adminApiMock, confirmMock, successMock, errorMock } = vi.hoisted(() => ({
  adminApiMock: {
    listCodeSubmissions: vi.fn(),
    listDbSubmissions: vi.fn(),
    listObjectiveSubmissions: vi.fn(),
    rejudgeSubmission: vi.fn(),
    rejudgeDbSubmission: vi.fn(),
    rejudgeObjectiveSubmission: vi.fn(),
    rejudgeObjectiveQuestion: vi.fn(),
  },
  confirmMock: vi.fn(),
  successMock: vi.fn(),
  errorMock: vi.fn(),
}));

vi.mock('@/api/admin', () => ({ adminApi: adminApiMock }));
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => ({ confirm: confirmMock }) }));
vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ success: successMock, error: errorMock }),
}));

let wrapper: ReturnType<typeof mount> | null = null;

describe('AdminSubmissionsView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    confirmMock.mockResolvedValue(true);

    adminApiMock.listCodeSubmissions.mockResolvedValue({
      list: [
        {
          submissionId: 101,
          userId: 7,
          username: 'alice',
          questionId: 12,
          questionTitle: 'A + B Problem',
          language: 'CPP',
          status: 'ACCEPTED',
          score: 100,
          timeUsedMs: 14,
          memoryUsedKb: 2048,
          codeContent: 'int main(){return 0;}',
          createdAt: '2026-11-15T10:00:00Z',
        },
      ],
      total: 1,
    });

    adminApiMock.listDbSubmissions.mockResolvedValue({
      list: [
        {
          submissionId: 202,
          userId: 8,
          username: 'bob',
          questionId: 22,
          questionTitle: '查询选课人数',
          status: 'WRONG_ANSWER',
          score: 0,
          timeUsedMs: 8,
          sqlContent: 'SELECT * FROM course;',
          createdAt: '2026-11-15T10:05:00Z',
        },
      ],
      total: 1,
    });

    adminApiMock.listObjectiveSubmissions.mockResolvedValue({
      list: [
        {
          submissionId: 303,
          userId: 9,
          username: 'carol',
          questionId: 33,
          questionTitle: 'TCP 三次握手',
          type: 'SINGLE',
          answer: 'A',
          correctAnswer: 'B',
          correct: 0,
          score: 0,
          fullScore: 5,
          createdAt: '2026-11-15T10:10:00Z',
        },
      ],
      total: 1,
    });

    adminApiMock.rejudgeSubmission.mockResolvedValue({ rejudged: 1 });
    adminApiMock.rejudgeDbSubmission.mockResolvedValue({ rejudged: 1 });
    adminApiMock.rejudgeObjectiveSubmission.mockResolvedValue({ rejudged: 1 });
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
    document.body.innerHTML = '';
  });

  it('hides score columns and details across code, database, and objective tabs', async () => {
    wrapper = mount(AdminSubmissionsView, { attachTo: document.body });
    await flushPromises();

    expect(wrapper.text()).not.toContain('得分');
    expect(wrapper.text()).toContain('#101');
    expect(wrapper.text()).toContain('alice');
    expect(wrapper.text()).toContain('14ms');

    await wrapper.get('.action-btn').trigger('click');
    await nextTick();
    const drawer = document.body.querySelector('.detail-drawer');
    expect(drawer).not.toBeNull();
    expect(drawer?.textContent).not.toContain('得分');
    expect(drawer?.textContent).toContain('int main(){return 0;}');

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await nextTick();
    expect(document.body.querySelector('.detail-drawer')).toBeNull();

    const tabs = wrapper.findAll('[role="tab"]');
    await tabs[1]!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('得分');
    await wrapper.get('.action-btn').trigger('click');
    await nextTick();
    expect(document.body.querySelector('.detail-drawer')?.textContent).toContain('SELECT * FROM course;');
    expect(document.body.querySelector('.detail-drawer')?.textContent).not.toContain('得分');

    await tabs[2]!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('得分');
    await wrapper.get('.action-btn').trigger('click');
    await nextTick();
    const objDrawerText = document.body.querySelector('.detail-drawer')?.textContent ?? '';
    expect(objDrawerText).toContain('学生作答');
    expect(objDrawerText).toContain('标准答案');
    expect(objDrawerText).not.toContain('得分');
    expect(objDrawerText).not.toContain('/ 5');
  });

  it('supports drawer rejudge confirmation while preserving filters and reset', async () => {
    wrapper = mount(AdminSubmissionsView, { attachTo: document.body });
    await flushPromises();

    const statusSelect = wrapper.get('select');
    await statusSelect.setValue('ACCEPTED');
    await flushPromises();
    expect(adminApiMock.listCodeSubmissions).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'ACCEPTED', page: 1 }),
    );

    await wrapper.get('.action-btn').trigger('click');
    await nextTick();
    const rejudgeBtn = document.body.querySelector<HTMLButtonElement>('.rejudge-btn');
    expect(rejudgeBtn).not.toBeNull();
    rejudgeBtn!.click();
    await flushPromises();

    expect(confirmMock).toHaveBeenCalledTimes(1);
    expect(adminApiMock.rejudgeSubmission).toHaveBeenCalledWith(101);
    // Reload keeps filter
    expect(adminApiMock.listCodeSubmissions).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'ACCEPTED', page: 1 }),
    );

    const resetBtn = wrapper.findAll('button').find((b) => b.text() === '重置');
    expect(resetBtn).toBeDefined();
    await resetBtn!.trigger('click');
    await flushPromises();
    expect(adminApiMock.listCodeSubmissions).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: undefined, page: 1 }),
    );
  });
});
