<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import ClusterConsole from '@/components/ClusterConsole.vue';
import HomePracticeBoard from '@/components/HomePracticeBoard.vue';
import { systemApi } from '@/api/system';
import DOMPurify from 'dompurify';
import Prism from 'prismjs';
import 'prismjs/components/prism-clike';
import 'prismjs/components/prism-java';
import 'prismjs/components/prism-sql';
import 'prismjs/components/prism-json';

const router = useRouter();

// =========================================
// 交互终端演示 Tab 切换
// =========================================
type DemoMode = 'algo' | 'sql' | 'exam';
const activeTab = ref<DemoMode>('algo');
const hasLiveAlgoSnippet = ref(false);

interface DemoContent {
  filename: string;
  langBadge: string;
  code: string;
  verdict: {
    status: string;
    statusClass: string;
    metrics: { label: string; value: string }[];
    details: string;
  };
}

const demos = reactive<Record<DemoMode, DemoContent>>({
  algo: {
    filename: 'Solution.java',
    langBadge: 'Java 17',
    code: `// ==========================================
// 算法模板 · 二分查找与边界判定 (Java 17)
// ==========================================
public class Solution {
    public int search(int[] nums, int target) {
        int left = 0, right = nums.length - 1;
        while (left <= right) {
            int mid = left + (right - left) / 2;
            if (nums[mid] == target) return mid;
            if (nums[mid] < target) left = mid + 1;
            else right = mid - 1;
        }
        return -1;
    }
}`,
    verdict: {
      status: 'ACCEPTED (AC)',
      statusClass: 'status-ac',
      metrics: [
        { label: '运行耗时', value: '15 ms' },
        { label: '内存消耗', value: '38.4 MB' },
        { label: '测试点通过', value: '10 / 10' },
      ],
      details: 'Docker Cgroups v2 强隔离沙箱运行 · 资源严格受控',
    },
  },
  sql: {
    filename: 'query.sql',
    langBadge: 'MySQL 8.0',
    code: `-- 查询研发部薪资最高的前三名员工
SELECT
    e.id,
    e.name,
    e.salary
FROM emp e
WHERE e.dept = 'RD'
ORDER BY e.salary DESC, e.id ASC
LIMIT 3;`,
    verdict: {
      status: 'ACCEPTED (AC)',
      statusClass: 'status-ac',
      metrics: [
        { label: '执行耗时', value: '18 ms' },
        { label: '行序校验', value: '严格匹配' },
        { label: '数据比对', value: '3 / 3 完全一致' },
      ],
      details: '独立判题临时库 judge_sub_01 执行 · 跑完即自动销毁',
    },
  },
  exam: {
    filename: 'session_report.json',
    langBadge: 'ACM 赛制',
    code: `{
  "exam_id": 1024,
  "title": "2026 技术工程能力机试",
  "mode": "ACM",
  "rank": 1,
  "solved_count": 4,
  "penalty_minutes": 118,
  "screen_switch_count": 0,
  "status": "COMPLETED"
}`,
    verdict: {
      status: 'RANK #1 (100 分)',
      statusClass: 'status-exam',
      metrics: [
        { label: '通过题数', value: '4 题' },
        { label: '罚时', value: '118 分钟' },
        { label: '切屏记录', value: '0 次（正常）' },
      ],
      details: 'ACM 实时榜单结算 · 客观题 + 编程题自动折算分值',
    },
  },
});

const isLiveSnippet = computed(() => activeTab.value === 'algo' && hasLiveAlgoSnippet.value);
const snippetSourceLabel = computed(() =>
  isLiveSnippet.value ? 'LIVE SNIPPET · 每日题库模板' : 'SIMULATION PREVIEW · 沙箱演练样例',
);

function start() {
  router.push('/questions');
}

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

// =========================================
// 交互终端代码演示：惰性加载与减弱动效适配
// =========================================
const typedCode = ref('');
const isTyping = ref(false);
const isVerdictReady = ref(false);
const testingProgress = ref(1);
const hasTriggeredScroll = ref(false);
const terminalSectionRef = ref<HTMLElement | null>(null);
let codeTypeTimer: ReturnType<typeof setTimeout> | null = null;
let terminalObserver: IntersectionObserver | null = null;
let isUnmounted = false;

onBeforeUnmount(() => {
  isUnmounted = true;
  if (codeTypeTimer) {
    clearTimeout(codeTypeTimer);
    codeTypeTimer = null;
  }
  if (terminalObserver) {
    terminalObserver.disconnect();
    terminalObserver = null;
  }
});

const totalLines = computed(() => {
  const code = hasTriggeredScroll.value
    ? typedCode.value || ' '
    : demos[activeTab.value]?.code || ' ';
  return Math.max(1, code.split('\n').length);
});

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (ch) => {
    const entities: Record<string, string> = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;',
    };
    return entities[ch] ?? ch;
  });
}

// 针对首页沙箱评测终端演示代码的高亮（支持 Java / SQL / JSON）
const highlightedCode = computed(() => {
  const current = demos[activeTab.value];
  if (!current) return '';
  const text = hasTriggeredScroll.value ? typedCode.value : '';
  if (!text) return '';

  let grammar = Prism.languages.clike;
  let langId = 'clike';

  if (activeTab.value === 'algo') {
    grammar = Prism.languages.java || Prism.languages.clike;
    langId = 'java';
  } else if (activeTab.value === 'sql') {
    grammar = Prism.languages.sql || Prism.languages.clike;
    langId = 'sql';
  } else if (activeTab.value === 'exam') {
    grammar = Prism.languages.json || Prism.languages.clike;
    langId = 'json';
  }

  try {
    return DOMPurify.sanitize(Prism.highlight(text, grammar, langId), {
      ALLOWED_TAGS: ['span'],
      ALLOWED_ATTR: ['class'],
    });
  } catch {
    return escapeHtml(text);
  }
});

function startCodeTyping() {
  if (codeTypeTimer) {
    clearTimeout(codeTypeTimer);
    codeTypeTimer = null;
  }
  if (isUnmounted) return;

  const fullCode = (demos[activeTab.value]?.code || '').trim();
  if (!fullCode) return;

  if (prefersReducedMotion()) {
    typedCode.value = fullCode;
    isTyping.value = false;
    isVerdictReady.value = true;
    testingProgress.value = 10;
    return;
  }

  typedCode.value = '';
  isTyping.value = true;
  isVerdictReady.value = false;
  testingProgress.value = 1;

  let index = 0;
  const totalLen = fullCode.length;

  const typeNext = () => {
    if (isUnmounted) return;
    if (index < totalLen) {
      // 真实编码打字节奏：缩进空白一次性快速跃进
      if (
        fullCode.slice(index, index + 4) === '    ' ||
        fullCode.slice(index, index + 2) === '  '
      ) {
        index += fullCode.slice(index, index + 4) === '    ' ? 4 : 2;
      } else if (fullCode[index] === '\t') {
        index += 1;
      } else {
        const step = Math.min(totalLen - index, Math.random() > 0.45 ? 2 : 1);
        index += step;
      }

      typedCode.value = fullCode.slice(0, index);

      // 动态推进测试点进度 (1 -> 10)
      const ratio = index / totalLen;
      testingProgress.value = Math.min(10, Math.max(1, Math.ceil(ratio * 10)));

      const lastChar = fullCode[index - 1];
      let delay = 10 + Math.random() * 12;

      if (lastChar === '\n') {
        delay = 55 + Math.random() * 30;
      } else if (lastChar === ';' || lastChar === '{' || lastChar === '}') {
        delay = 35 + Math.random() * 20;
      }

      codeTypeTimer = setTimeout(typeNext, delay);
    } else {
      typedCode.value = fullCode;
      // 代码敲完，模拟沙箱 280ms 综合判定延迟，随后 ACCEPTED 判定落地
      codeTypeTimer = setTimeout(() => {
        if (isUnmounted) return;
        isTyping.value = false;
        isVerdictReady.value = true;
        testingProgress.value = 10;
      }, 280);
    }
  };

  codeTypeTimer = setTimeout(typeNext, 160);
}

function replayTyping() {
  hasTriggeredScroll.value = true;
  startCodeTyping();
}

watch(activeTab, () => {
  if (hasTriggeredScroll.value) {
    startCodeTyping();
  }
});

// 动态拉取后端每日更新的随机 Java 算法模板（秒级响应，离线/超时高可用保底）
onMounted(async () => {
  // 减弱动效或缺少 IntersectionObserver 时直接呈现终端内容；否则滚动进入视口再启动打字演示
  if (
    prefersReducedMotion() ||
    typeof IntersectionObserver === 'undefined' ||
    !terminalSectionRef.value
  ) {
    hasTriggeredScroll.value = true;
    startCodeTyping();
  } else {
    terminalObserver = new IntersectionObserver(
      (entries) => {
        const entry = entries[0];
        if (entry && entry.isIntersecting) {
          if (!hasTriggeredScroll.value) {
            hasTriggeredScroll.value = true;
            startCodeTyping();
          }
        }
      },
      { threshold: 0.2 },
    );
    terminalObserver.observe(terminalSectionRef.value);
  }

  try {
    const res = await systemApi.getDemoSnippet();
    if (isUnmounted) return;
    if (res && res.code && res.code.trim()) {
      demos.algo.filename = res.filename || 'Solution.java';
      demos.algo.langBadge = res.langBadge || 'Java 17';
      demos.algo.code = res.code.trim();
      hasLiveAlgoSnippet.value = true;
      if (activeTab.value === 'algo' && hasTriggeredScroll.value) {
        startCodeTyping();
      }
    }
  } catch (err) {
    console.warn('Failed to fetch dynamic demo snippet, using fallback template:', err);
  }
});
</script>

<template>
  <div class="home">
    <!-- 官网 Hero 区域（左侧硬核工程标语，右侧真实沙箱集群遥测控制台） -->
    <section class="hero container">
      <div class="hero-text">
        <div class="hero-eyebrow mono">// HIGH-PERFORMANCE JUDGMENT PLATFORM</div>
        <h1 class="hero-title">
          多维度编程与<br />
          技能评测平台
        </h1>
        <p class="hero-sub">
          集成算法 OJ、MySQL 8.0 数据库 OJ、标准客观题库与赛制考核系统。<br />
          基于高可用强隔离沙箱与独立临时库，提供确定性、防作弊的全真技术考核。
        </p>

        <div class="hero-actions">
          <button type="button" class="btn btn-primary btn-lg hero-cta" @click="start">
            开始使用 →
          </button>
          <RouterLink to="/exams" class="btn btn-ghost btn-lg">在线考试系统</RouterLink>
        </div>

        <!-- 核心引擎导航条（诚实对齐已上线四大主路由） -->
        <nav class="hero-spec-strip mono" aria-label="核心评测引擎导航">
          <RouterLink to="/questions" class="spec-pill">算法 OJ · Docker 沙箱</RouterLink>
          <RouterLink to="/database" class="spec-pill">数据库 OJ · MySQL 8.0</RouterLink>
          <RouterLink to="/objective" class="spec-pill">客观题 · 自动批改</RouterLink>
          <RouterLink to="/exams" class="spec-pill">赛制考核 · 在线考试</RouterLink>
        </nav>
      </div>

      <!-- 右侧真实沙箱评测集群控制台（硬核工控控制台） -->
      <div class="hero-visual">
        <ClusterConsole />
      </div>
    </section>

    <HomePracticeBoard />

    <!-- 交互式技术体验区：沙箱评测终端体验（明确标注每日模板与演练样例来源） -->
    <section ref="terminalSectionRef" class="demo-section container">
      <div class="demo-header">
        <div class="demo-eyebrow mono">// SANDBOX RUNNER · INTERACTIVE PREVIEW</div>
        <h2 class="demo-title">真实评测闭环体验</h2>
        <p class="demo-desc">
          基于 Linux 强隔离容器沙箱与独立 MySQL 临时库，提供毫秒级、防作弊、确定性的技术评测。
        </p>
      </div>

      <div class="terminal-card">
        <div class="terminal-top">
          <div class="terminal-badge">
            <span class="live-dot" :class="{ 'is-running': isTyping }"></span>
            <span>JUDGE RUNNER #01</span>
            <span v-if="isTyping" class="badge-running-pill mono">RUNNING</span>
          </div>

          <div class="terminal-tabs">
            <button
              type="button"
              class="terminal-tab"
              :class="{ active: activeTab === 'algo' }"
              :aria-pressed="activeTab === 'algo'"
              @click="activeTab = 'algo'"
            >
              Java 算法 OJ
            </button>
            <button
              type="button"
              class="terminal-tab"
              :class="{ active: activeTab === 'sql' }"
              :aria-pressed="activeTab === 'sql'"
              @click="activeTab = 'sql'"
            >
              SQL 数据库 OJ
            </button>
            <button
              type="button"
              class="terminal-tab"
              :class="{ active: activeTab === 'exam' }"
              :aria-pressed="activeTab === 'exam'"
              @click="activeTab = 'exam'"
            >
              ACM 考试结算
            </button>
            <button
              type="button"
              class="terminal-tab btn-replay"
              title="重新播放代码打字与沙箱判定动效"
              @click="replayTyping"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" class="replay-icon" aria-hidden="true">
                <path
                  fill-rule="evenodd"
                  d="M8 3a5 5 0 1 0 4.546 2.914.5.5 0 0 1 .908-.417A6 6 0 1 1 8 2v1z"
                />
                <path
                  d="M8 4.466V.534a.25.25 0 0 1 .41-.192l2.36 1.966c.12.1.12.284 0 .384L8.41 4.658A.25.25 0 0 1 8 4.466z"
                />
              </svg>
              <span>重播</span>
            </button>
          </div>
        </div>

        <div class="terminal-meta-bar">
          <div class="terminal-meta-info">
            <span class="file-name">{{ demos[activeTab].filename }}</span>
            <span class="lang-tag">{{ demos[activeTab].langBadge }}</span>
          </div>
          <span class="snippet-source-badge" :class="isLiveSnippet ? 'is-live' : 'is-simulation'">
            {{ snippetSourceLabel }}
          </span>
        </div>

        <div class="terminal-body">
          <div class="terminal-code-wrapper">
            <div class="line-numbers mono" aria-hidden="true">
              <span v-for="n in totalLines" :key="n">{{ n }}</span>
            </div>
            <!-- eslint-disable vue/no-v-html -- highlightedCode 已经过 Prism 高亮与 DOMPurify 清洗 -->
            <pre
              class="code-view mono"
            ><template v-if="!hasTriggeredScroll"><span class="waiting-hint">// 终端待命中 · 滚动进入视口自动启动沙箱...</span></template><template v-else><code v-html="highlightedCode"></code><span class="terminal-cursor" :class="{ typing: isTyping }" aria-hidden="true"></span></template></pre>
            <!-- eslint-enable vue/no-v-html -->
          </div>
        </div>

        <div class="terminal-hud">
          <div class="hud-status-row">
            <template v-if="!hasTriggeredScroll">
              <span class="verdict-tag status-idle mono"> ● STANDBY 待命 </span>
              <span class="verdict-detail">沙箱环境就绪 · 滚动至此加载全流程评测</span>
            </template>
            <template v-else-if="!isVerdictReady">
              <span class="verdict-tag status-running mono">
                <span class="pulse-spin" aria-hidden="true"></span>
                <span>SANDBOX EXECUTING...</span>
              </span>
              <span class="verdict-detail mono">
                Docker Cgroups 隔离测试中 · 对拍用例 {{ testingProgress }} / 10
              </span>
            </template>
            <template v-else>
              <span
                class="verdict-tag"
                :class="[demos[activeTab].verdict.statusClass, 'just-arrived']"
              >
                ● {{ demos[activeTab].verdict.status }}
              </span>
              <span class="verdict-detail">{{ demos[activeTab].verdict.details }}</span>
            </template>
          </div>
          <div class="hud-metric-row">
            <div v-for="m in demos[activeTab].verdict.metrics" :key="m.label" class="metric-item">
              <div class="metric-title">{{ m.label }}</div>
              <div class="metric-data" :class="{ 'is-loading': !isVerdictReady }">
                <span v-if="isVerdictReady">{{ m.value }}</span>
                <span v-else-if="!hasTriggeredScroll" class="metric-idle mono">待测</span>
                <span v-else class="metric-skeleton mono">
                  <span v-if="m.label.includes('耗时') || m.label.includes('执行')"
                    >{{ (testingProgress * 1.5).toFixed(0) }} ms...</span
                  >
                  <span v-else-if="m.label.includes('内存')">cgroups 采样</span>
                  <span
                    v-else-if="
                      m.label.includes('测试点') ||
                      m.label.includes('数据') ||
                      m.label.includes('通过')
                    "
                    >{{ testingProgress }} / 10 判定</span
                  >
                  <span v-else>实时计算中...</span>
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  padding-bottom: 40px;
}

.mono {
  font-family: var(
    --font-mono,
    'SFMono-Regular',
    Consolas,
    'Liberation Mono',
    Menlo,
    'PingFang SC',
    'Microsoft YaHei',
    monospace
  );
}

/* =========================================
   1. Hero 区域（对齐 VI 规范与硬核工控控制台）
   ========================================= */
.hero {
  display: grid;
  grid-template-columns: 1fr 1.05fr;
  gap: 48px;
  align-items: flex-start;
  padding-top: 52px;
  padding-bottom: 40px;
}

.hero-text {
  max-width: 520px;
}

.hero-eyebrow {
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-tertiary);
  letter-spacing: 0.8px;
  margin-bottom: 12px;
}

.hero-title {
  font-size: 46px;
  line-height: 1.2;
  font-weight: 700;
  letter-spacing: -0.6px;
  color: var(--ink);
  margin: 0 0 18px;
}

.hero-sub {
  font-size: 15.5px;
  line-height: 1.65;
  color: var(--ink-secondary);
  margin: 0 0 32px;
  font-weight: 400;
}

.hero-actions {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 24px;
}

.hero-cta {
  padding: 13px 36px;
  font-size: 16px;
  font-weight: 600;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.hero-cta:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.18);
}

.hero-spec-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-tertiary);
  letter-spacing: 0.3px;
}

.spec-pill {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  font-size: 12px;
  line-height: 1.4;
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--kece-border, var(--line));
  color: var(--ink-secondary);
  text-decoration: none;
  transition:
    border-color 0.15s ease,
    color 0.15s ease,
    background-color 0.15s ease;
}

.spec-pill:hover {
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink, #0f5f53);
  background: var(--kece-accent-soft, #eef6f4);
}

.hero-visual {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

/* =========================================
   3. 交互式沙箱评测终端（保留全宽结构与语法高亮）
   ========================================= */
.demo-section {
  padding-top: 12px;
  padding-bottom: 48px;
}

.demo-header {
  text-align: center;
  margin-bottom: 32px;
}

.demo-eyebrow {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.8px;
  color: var(--ink-tertiary);
  margin-bottom: 8px;
}

.demo-title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--ink);
  margin: 0 0 10px;
}

.demo-desc {
  font-size: 14.5px;
  color: var(--ink-secondary);
  margin: 0 auto;
  max-width: 580px;
}

.terminal-card {
  background: #141415;
  border-radius: var(--radius);
  border: 1px solid #28282a;
  color: #f1f1f1;
  overflow: hidden;
  box-shadow:
    0 20px 48px rgba(0, 0, 0, 0.14),
    0 2px 6px rgba(0, 0, 0, 0.08);
}

.terminal-card button:focus-visible {
  outline: 2px solid #2dd4bf;
  outline-offset: 2px;
}

.terminal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 12px 20px;
  background: #1b1b1d;
  border-bottom: 1px solid #28282a;
  gap: 12px;
}

.terminal-badge {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
  font-size: 12px;
  font-weight: 600;
  color: #cbd5e1;
  letter-spacing: 0.5px;
}

.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 0;
  background: #4ade80;
  box-shadow: 0 0 8px rgba(74, 222, 128, 0.55);
}

.terminal-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.terminal-tab {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
  font-size: 12px;
  padding: 5px 14px;
  border-radius: var(--pill);
  color: #a1a7b3;
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
}

.terminal-tab:hover {
  color: #ffffff;
  background: #252528;
}

.terminal-tab.active {
  color: #ffffff;
  background: #222a29;
  border-color: #2dd4bf;
}

.terminal-meta-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 8px 20px;
  background: #171719;
  border-bottom: 1px solid #232326;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
  font-size: 12px;
  color: #a1a7b3;
}

.terminal-meta-info {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.file-name {
  color: #cbd5e1;
}

.lang-tag {
  color: #a1a7b3;
}

.snippet-source-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  letter-spacing: 0.04em;
  border-radius: 0px;
}

.snippet-source-badge.is-live {
  color: #2dd4bf;
  background: rgba(45, 212, 191, 0.14);
  border: 1px solid rgba(45, 212, 191, 0.38);
}

.snippet-source-badge.is-simulation {
  color: #cbd5e1;
  background: rgba(161, 167, 179, 0.14);
  border: 1px solid rgba(161, 167, 179, 0.35);
}

.terminal-code-wrapper {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.line-numbers {
  display: flex;
  flex-direction: column;
  user-select: none;
  color: #a1a7b3;
  text-align: right;
  font-size: 13.5px;
  line-height: 1.6;
  min-width: 22px;
  padding-right: 8px;
  border-right: 1px solid #232326;
}

.terminal-cursor {
  display: inline-block;
  width: 7.5px;
  height: 15px;
  background: #2dd4bf;
  vertical-align: -2.5px;
  margin-left: 2px;
  animation: cursorBlink 0.85s infinite;
}

.terminal-cursor.typing {
  background: #5eead4;
  animation: none;
  opacity: 1;
}

@keyframes cursorBlink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

.waiting-hint {
  color: #a1a7b3;
  font-style: italic;
}

.badge-running-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 6px;
  background: rgba(45, 212, 191, 0.16);
  color: #2dd4bf;
  border: 1px solid rgba(45, 212, 191, 0.42);
  letter-spacing: 0.5px;
  animation: pulseRunning 1.2s infinite;
}

@keyframes pulseRunning {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.85;
  }
}

.btn-replay {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.replay-icon {
  width: 12px;
  height: 12px;
}

.live-dot.is-running {
  background: #2dd4bf;
  box-shadow: 0 0 10px rgba(45, 212, 191, 0.75);
}

.verdict-tag.status-idle {
  background: rgba(161, 167, 179, 0.14);
  color: #cbd5e1;
  border: 1px solid rgba(161, 167, 179, 0.35);
}

.verdict-tag.status-running {
  background: rgba(45, 212, 191, 0.16);
  color: #2dd4bf;
  border: 1px solid rgba(45, 212, 191, 0.4);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pulse-spin {
  display: inline-block;
  width: 8px;
  height: 8px;
  border: 1.5px solid rgba(45, 212, 191, 0.32);
  border-top-color: #2dd4bf;
  border-radius: 0px;
  animation: spinVerdict 0.6s linear infinite;
}

@keyframes spinVerdict {
  to {
    transform: rotate(360deg);
  }
}

.verdict-tag.just-arrived {
  animation: verdictPopIn 0.45s cubic-bezier(0.2, 0.8, 0.2, 1);
}

@keyframes verdictPopIn {
  0% {
    transform: scale(1.08);
    filter: brightness(1.5);
  }
  100% {
    transform: scale(1);
    filter: brightness(1);
  }
}

.metric-idle {
  color: #a1a7b3;
  font-size: 12px;
}

.metric-skeleton {
  color: #2dd4bf;
  font-size: 12px;
  animation: skeletonPulse 1s infinite alternate;
}

@keyframes skeletonPulse {
  0% {
    opacity: 0.6;
  }
  100% {
    opacity: 1;
  }
}

.terminal-body {
  padding: 20px;
  background: #111112;
  font-family: var(
    --font-mono,
    'JetBrains Mono',
    ui-monospace,
    SFMono-Regular,
    Menlo,
    Monaco,
    Consolas,
    monospace
  );
  font-size: 13.5px;
  line-height: 1.6;
  overflow-x: auto;
  min-height: 200px;
  scrollbar-width: thin;
  scrollbar-color: #2c2c30 #111112;
}

.terminal-body::-webkit-scrollbar {
  height: 6px;
  width: 6px;
}

.terminal-body::-webkit-scrollbar-track {
  background: #111112;
}

.terminal-body::-webkit-scrollbar-thumb {
  background: #2c2c30;
}

.code-view {
  margin: 0;
  color: #e2e4e9;
  white-space: pre;
  font-family:
    'Consolas', 'SFMono-Regular', 'Liberation Mono', Menlo, Monaco, 'Courier New', monospace !important;
  font-size: 13.5px;
  line-height: 1.6;
  tab-size: 4;
  letter-spacing: 0;
  word-spacing: normal;
  font-variant-ligatures: none;
}

.code-view code {
  font-family: inherit !important;
  font-size: inherit;
  line-height: inherit;
  letter-spacing: inherit;
  word-spacing: inherit;
}

/* 语法与关键字高亮体系 (Dark 主题高保真对比度) */
.code-view :deep(.token.keyword) {
  color: #c678dd;
  font-weight: 600;
}

.code-view :deep(.token.class-name),
.code-view :deep(.token.type) {
  color: #e5c07b;
}

.code-view :deep(.token.function) {
  color: #61afef;
}

.code-view :deep(.token.string) {
  color: #98c379;
}

.code-view :deep(.token.number) {
  color: #d19a66;
}

.code-view :deep(.token.boolean) {
  color: #d19a66;
  font-weight: 600;
}

.code-view :deep(.token.comment) {
  color: #a1a7b3;
  font-style: italic;
}

.code-view :deep(.token.operator) {
  color: #56b6c2;
}

.code-view :deep(.token.punctuation) {
  color: #cbd5e1;
}

.code-view :deep(.token.property) {
  color: #e06c75;
}

.code-view :deep(.token.namespace) {
  color: #cbd5e1;
}

.terminal-hud {
  padding: 16px 20px;
  background: #171719;
  border-top: 1px solid #262629;
}

.hud-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
}

.verdict-tag {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
  font-size: 12px;
  font-weight: 700;
  padding: 3px 12px;
  border-radius: var(--pill);
}

.verdict-tag.status-ac {
  background: rgba(47, 158, 68, 0.18);
  color: #4ade80;
  border: 1px solid rgba(74, 222, 128, 0.4);
}

.verdict-tag.status-exam {
  background: rgba(45, 212, 191, 0.16);
  color: #2dd4bf;
  border: 1px solid rgba(45, 212, 191, 0.4);
}

.verdict-detail {
  font-size: 12px;
  color: #cbd5e1;
}

.hud-metric-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.metric-item {
  background: #1f1f22;
  border: 1px solid #2a2a2e;
  border-radius: 0;
  padding: 9px 12px;
}

.metric-title {
  font-size: 11px;
  color: #a1a7b3;
  margin-bottom: 2px;
}

.metric-data {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
  font-size: 13px;
  font-weight: 600;
  color: #ffffff;
}

/* =========================================
   4. 响应式与减弱动效适配
   ========================================= */
@media (max-width: 860px) {
  .hero {
    grid-template-columns: 1fr;
    padding-top: 36px;
    padding-bottom: 48px;
    gap: 36px;
  }
  .hero-text {
    max-width: 100%;
    text-align: center;
  }
  .hero-sub br {
    display: none;
  }
  .hero-actions {
    justify-content: center;
  }
  .hero-spec-strip {
    justify-content: center;
  }
}

@media (max-width: 640px) {
  .hero-title {
    font-size: 34px;
  }
  .hud-metric-row {
    grid-template-columns: 1fr;
  }
  .terminal-tab {
    padding: 4px 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hero-cta,
  .hero-cta:hover,
  .spec-pill,
  .terminal-tab {
    transition: none;
    transform: none;
  }

  .terminal-cursor,
  .badge-running-pill,
  .pulse-spin,
  .verdict-tag.just-arrived,
  .metric-skeleton {
    animation: none;
    opacity: 1;
  }
}
</style>
