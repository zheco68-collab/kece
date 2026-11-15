import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils';

const { pushMock, getDemoSnippetMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  getDemoSnippetMock: vi.fn(),
}));

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>();
  return {
    ...actual,
    useRouter: () => ({ push: pushMock }),
  };
});

vi.mock('@/api/system', () => ({
  systemApi: {
    getDemoSnippet: getDemoSnippetMock,
  },
}));

import HomeView from './HomeView.vue';

async function withReducedMotion(enabled: boolean, run: () => Promise<void> | void) {
  const originalMatchMedia = window.matchMedia;
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: enabled && query.includes('prefers-reduced-motion'),
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
    await run();
  } finally {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: originalMatchMedia,
    });
  }
}

function mountHome() {
  return mount(HomeView, {
    global: {
      stubs: {
        RouterLink: RouterLinkStub,
        ClusterConsole: { template: '<div data-test="cluster-console-stub" />' },
        HomePracticeBoard: { template: '<div data-test="practice-board-stub" />' },
      },
    },
  });
}

describe('HomeView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getDemoSnippetMock.mockRejectedValue(new Error('offline'));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('移除故障切片、双份打字机占位与冗余底部 CTA，并对齐四大已上线核心路由', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const wrapper = mountHome();
    await flushPromises();

    expect(wrapper.find('.trae-glitch-wrapper').exists()).toBe(false);
    expect(wrapper.find('.trae-glitch').exists()).toBe(false);
    expect(wrapper.find('.is-glitching').exists()).toBe(false);
    expect(wrapper.find('.hero-sub-ghost').exists()).toBe(false);
    expect(wrapper.find('.typewriter-cursor').exists()).toBe(false);
    expect(wrapper.find('.cta-card').exists()).toBe(false);
    expect(wrapper.find('.cta-section').exists()).toBe(false);

    expect(wrapper.get('h1').text()).toContain('多维度编程与');
    expect(wrapper.get('h2.demo-title').text()).toBe('真实评测闭环体验');
    expect(wrapper.text()).not.toContain('前端 AI');
    expect(wrapper.text()).not.toContain('PLAYWRIGHT + AI');

    const navLinks = wrapper.findAllComponents(RouterLinkStub).map((link) => link.props('to'));
    expect(navLinks).toEqual(
      expect.arrayContaining(['/questions', '/database', '/objective', '/exams']),
    );
    expect(navLinks).not.toContain('/frontend');

    await wrapper.get('button.hero-cta').trigger('click');
    expect(pushMock).toHaveBeenCalledWith('/questions');

    warnSpy.mockRestore();
    wrapper.unmount();
  });

  it('在 prefers-reduced-motion: reduce 下不调度打字定时器并直接展示完整代码与判定结果', async () => {
    vi.useFakeTimers();
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await withReducedMotion(true, async () => {
      const wrapper = mountHome();
      await flushPromises();

      expect(vi.getTimerCount()).toBe(0);
      expect(wrapper.get('.code-view').text()).toContain('public class Solution');
      expect(wrapper.get('.verdict-tag').text()).toContain('ACCEPTED (AC)');
      expect(wrapper.get('.snippet-source-badge').text()).toBe('SIMULATION PREVIEW · 沙箱演练样例');

      const tabs = wrapper.findAll('button.terminal-tab');
      await tabs[1].trigger('click');
      expect(vi.getTimerCount()).toBe(0);
      expect(wrapper.get('.code-view').text()).toContain('SELECT');
      expect(wrapper.get('.verdict-tag').text()).toContain('ACCEPTED (AC)');

      await tabs[2].trigger('click');
      expect(vi.getTimerCount()).toBe(0);
      expect(wrapper.get('.code-view').text()).toContain('"mode": "ACM"');
      expect(wrapper.get('.verdict-tag').text()).toContain('RANK #1 (100 分)');

      wrapper.unmount();
    });

    warnSpy.mockRestore();
  });

  it('准确区分每日题库动态模板与本地沙箱演练样例来源标识', async () => {
    getDemoSnippetMock.mockResolvedValueOnce({
      filename: 'DailyTwoSum.java',
      langBadge: 'Java 17',
      code: 'public class DailyTwoSum {\n    public int solve() { return 42; }\n}',
    });

    await withReducedMotion(true, async () => {
      const wrapper = mountHome();
      await flushPromises();

      expect(wrapper.get('.file-name').text()).toBe('DailyTwoSum.java');
      expect(wrapper.get('.code-view').text()).toContain('DailyTwoSum');
      expect(wrapper.get('.snippet-source-badge').text()).toBe('LIVE SNIPPET · 每日题库模板');
      expect(wrapper.get('.snippet-source-badge').classes()).toContain('is-live');

      const tabs = wrapper.findAll('button.terminal-tab');
      await tabs[1].trigger('click');
      expect(wrapper.get('.snippet-source-badge').text()).toBe('SIMULATION PREVIEW · 沙箱演练样例');
      expect(wrapper.get('.snippet-source-badge').classes()).toContain('is-simulation');

      await tabs[0].trigger('click');
      expect(wrapper.get('.snippet-source-badge').text()).toBe('LIVE SNIPPET · 每日题库模板');

      wrapper.unmount();
    });
  });
});
