<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import ClusterConsole from '@/components/ClusterConsole.vue'
import { systemApi } from '@/api/system'
import Prism from 'prismjs'
import 'prismjs/components/prism-clike'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-sql'
import 'prismjs/components/prism-json'

const router = useRouter()

// 交互终端演示 Tab 切换
type DemoMode = 'algo' | 'sql' | 'exam'
const activeTab = ref<DemoMode>('algo')

interface DemoContent {
  filename: string
  langBadge: string
  code: string
  verdict: {
    status: string
    statusClass: string
    metrics: { label: string; value: string }[]
    details: string
  }
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
})

function start() {
  router.push('/questions')
}

// Trae 风格故障切片动效（仅在鼠标主动移动时触发，鼠标放上去不动或离开时立即完全静止）
const isGlitching = ref(false)
const glitchStyle = ref<Record<string, string>>({})
let glitchTimer: ReturnType<typeof setTimeout> | null = null

function handleTitleMouseMove(e: MouseEvent) {
  isGlitching.value = true

  const target = e.currentTarget as HTMLElement
  if (!target) return
  const rect = target.getBoundingClientRect()
  const relY = ((e.clientY - rect.top) / rect.height) * 100

  // 围绕光标 Y 动态产生阶梯切片区间
  const y1 = Math.max(5, (relY - 15) % 100)
  const y2 = Math.min(95, y1 + 18)
  const y3 = (y2 + 25) % 95
  const y4 = Math.min(98, y3 + 16)

  const s1 = (Math.random() > 0.4 ? 1 : -1) * (7 + Math.random() * 8)
  const s2 = -s1 * (0.8 + Math.random() * 0.4)

  glitchStyle.value = {
    '--shift-1': `${s1.toFixed(1)}px`,
    '--shift-2': `${s2.toFixed(1)}px`,
    '--clip-1': `polygon(0 ${y1.toFixed(1)}%, 100% ${y1.toFixed(1)}%, 100% ${y2.toFixed(1)}%, 0 ${y2.toFixed(1)}%, 0 ${y3.toFixed(1)}%, 100% ${y3.toFixed(1)}%, 100% ${y4.toFixed(1)}%, 0 ${y4.toFixed(1)}%)`,
    '--clip-2': `polygon(0 ${((y1 + 20) % 90).toFixed(1)}%, 100% ${((y1 + 20) % 90).toFixed(1)}%, 100% ${((y1 + 38) % 95).toFixed(1)}%, 0 ${((y1 + 38) % 95).toFixed(1)}%)`,
  }

  if (glitchTimer) clearTimeout(glitchTimer)
  // 鼠标停止移动 130ms 后，故障切片动效立即停止，文字完全恢复静止
  glitchTimer = setTimeout(() => {
    isGlitching.value = false
  }, 130)
}

function handleTitleMouseLeave() {
  if (glitchTimer) clearTimeout(glitchTimer)
  isGlitching.value = false
}

// 针对首页沙箱评测终端演示代码的代码与关键字高亮（支持 Java / SQL / JSON）
const highlightedCode = computed(() => {
  const current = demos[activeTab.value]
  if (!current || !current.code) return ''

  let grammar = Prism.languages.clike
  let langId = 'clike'

  if (activeTab.value === 'algo') {
    grammar = Prism.languages.java || Prism.languages.clike
    langId = 'java'
  } else if (activeTab.value === 'sql') {
    grammar = Prism.languages.sql || Prism.languages.clike
    langId = 'sql'
  } else if (activeTab.value === 'exam') {
    grammar = Prism.languages.json || Prism.languages.clike
    langId = 'json'
  }

  try {
    return Prism.highlight(current.code, grammar, langId)
  } catch {
    return current.code
  }
})

// 动态拉取后端每日更新的随机 Java 算法模板（秒级响应，离线/超时高可用保底）
onMounted(async () => {
  try {
    const res = await systemApi.getDemoSnippet()
    if (res && res.code && res.code.trim()) {
      demos.algo.filename = res.filename || 'Solution.java'
      demos.algo.langBadge = res.langBadge || 'Java 17'
      demos.algo.code = res.code
    }
  } catch (err) {
    console.warn('Failed to fetch dynamic demo snippet, using fallback template:', err)
  }
})
</script>

<template>
  <div class="home">
    <!-- 官网 Hero 区域（左侧硬核工程标语，右侧真实沙箱集群遥测控制台） -->
    <section class="hero container">
      <div class="hero-text">
        <div class="hero-eyebrow mono">// HIGH-PERFORMANCE JUDGMENT PLATFORM</div>
        <h1
          class="hero-title trae-glitch-wrapper"
          :class="{ 'is-glitching': isGlitching }"
          :style="glitchStyle"
          @mousemove="handleTitleMouseMove"
          @mouseleave="handleTitleMouseLeave"
        >
          <span class="trae-glitch" data-text="多维度编程与">多维度编程与</span><br />
          <span class="trae-glitch" data-text="技能评测平台">技能评测平台</span>
        </h1>
        <p class="hero-sub">
          无缝集成算法 OJ、数据库 OJ、前端 AI 智能评测及标准客观题系统。<br />
          基于高可用强隔离沙箱与独立临时库，提供确定性、防作弊的全真技术考核。
        </p>

        <div class="hero-actions">
          <button class="btn btn-primary btn-lg hero-cta" @click="start">开始使用 →</button>
          <RouterLink to="/exams" class="btn btn-ghost btn-lg">在线考试系统</RouterLink>
        </div>

        <!-- 工业规格小标条 -->
        <div class="hero-spec-strip mono">
          <span class="spec-pill">DOCKER CGROUPS V2</span>
          <span class="spec-dot">·</span>
          <span class="spec-pill">MYSQL 8.0 临时库</span>
          <span class="spec-dot">·</span>
          <span class="spec-pill">PLAYWRIGHT + AI 审计</span>
        </div>
      </div>

      <!-- 右侧真实沙箱评测集群控制台（硬核工控控制台） -->
      <div class="hero-visual">
        <ClusterConsole />
      </div>
    </section>

    <!-- 核心多维能力评测矩阵（5 大直角 Bento 宽幅卡片，清晰大方、绝不杂乱） -->
    <section class="modules-section container">
      <div class="modules-header">
        <div class="modules-eyebrow mono">// CAPABILITY MATRIX · 5 ENGINES</div>
        <h2 class="modules-title">全栈技术与工程能力评测矩阵</h2>
        <p class="modules-desc">
          每个评测引擎均拥有独立的物理隔离与自动判分机制，满足从日常练习到正规机考的全流程需求。
        </p>
      </div>

      <div class="modules-grid">
        <!-- 01 算法 OJ -->
        <RouterLink to="/questions" class="module-card">
          <div class="module-card-top">
            <span class="module-num mono">01</span>
            <span class="module-arch-tag mono">CGROUPS V2</span>
          </div>

          <div class="module-icon-box">
            <svg class="module-svg" viewBox="0 0 48 48" fill="none">
              <rect x="6" y="6" width="36" height="36" rx="0" stroke="currentColor" stroke-width="2.5" />
              <path d="M18 20L14 24L18 28" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M30 20L34 24L30 28" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M26 17L22 31" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
            </svg>
          </div>

          <div class="module-title">算法 OJ</div>
          <div class="module-desc">在线编程 · 智能评测</div>
          <div class="module-spec mono">毫秒级时空限制判定</div>
          <div class="module-arrow">进入题库 →</div>
        </RouterLink>

        <!-- 02 数据库 OJ -->
        <RouterLink to="/database" class="module-card">
          <div class="module-card-top">
            <span class="module-num mono">02</span>
            <span class="module-arch-tag mono">MYSQL 8.0</span>
          </div>

          <div class="module-icon-box">
            <svg class="module-svg" viewBox="0 0 48 48" fill="none">
              <ellipse cx="24" cy="14" rx="14" ry="5.5" stroke="currentColor" stroke-width="2.5" />
              <path d="M10 14V24C10 27 16.3 29.5 24 29.5C31.7 29.5 38 27 38 24V14" stroke="currentColor" stroke-width="2.5" />
              <path d="M10 24V34C10 37 16.3 39.5 24 39.5C31.7 39.5 38 37 38 34V24" stroke="currentColor" stroke-width="2.5" />
            </svg>
          </div>

          <div class="module-title">数据库 OJ</div>
          <div class="module-desc">SQL 实战 · 结果评测</div>
          <div class="module-spec mono">动态临时库物理强隔离</div>
          <div class="module-arrow">进入题库 →</div>
        </RouterLink>

        <!-- 03 前端 AI 评测 -->
        <RouterLink to="/frontend" class="module-card">
          <div class="module-card-top">
            <span class="module-num mono">03</span>
            <span class="module-arch-tag mono">PLAYWRIGHT</span>
          </div>

          <div class="module-icon-box">
            <svg class="module-svg" viewBox="0 0 48 48" fill="none">
              <rect x="7" y="9" width="34" height="30" rx="0" stroke="currentColor" stroke-width="2.5" />
              <line x1="7" y1="18" x2="41" y2="18" stroke="currentColor" stroke-width="2.5" />
              <circle cx="13" cy="13.5" r="1.5" fill="currentColor" />
              <circle cx="18" cy="13.5" r="1.5" fill="currentColor" />
              <circle cx="23" cy="13.5" r="1.5" fill="currentColor" />
              <path d="M16 29L20 33L32 23" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </div>

          <div class="module-title">前端 AI 评测</div>
          <div class="module-desc">代码检查 · 智能反馈</div>
          <div class="module-spec mono">DOM 交互断言与多模态审计</div>
          <div class="module-arrow">体验评测 →</div>
        </RouterLink>

        <!-- 04 标准客观题 -->
        <RouterLink to="/objective" class="module-card">
          <div class="module-card-top">
            <span class="module-num mono">04</span>
            <span class="module-arch-tag mono">LATEX MATH</span>
          </div>

          <div class="module-icon-box">
            <svg class="module-svg" viewBox="0 0 48 48" fill="none">
              <rect x="9" y="7" width="30" height="34" rx="0" stroke="currentColor" stroke-width="2.5" />
              <line x1="16" y1="16" x2="32" y2="16" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
              <line x1="16" y1="23" x2="32" y2="23" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
              <line x1="16" y1="30" x2="26" y2="30" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
              <circle cx="13" cy="16" r="1" fill="currentColor" />
              <circle cx="13" cy="23" r="1" fill="currentColor" />
              <circle cx="13" cy="30" r="1" fill="currentColor" />
            </svg>
          </div>

          <div class="module-title">标准客观题</div>
          <div class="module-desc">自动判分 · 成绩统计</div>
          <div class="module-spec mono">单选/多选/填空智能判题</div>
          <div class="module-arrow">进入题库 →</div>
        </RouterLink>

        <!-- 05 在线考试系统 -->
        <RouterLink to="/exams" class="module-card exam-card">
          <div class="module-card-top">
            <span class="module-num mono">05</span>
            <span class="module-arch-tag mono">ACM / OI</span>
          </div>

          <div class="module-icon-box">
            <svg class="module-svg" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="26" r="14" stroke="currentColor" stroke-width="2.5" />
              <path d="M24 18V26L29 29" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
              <line x1="21" y1="7" x2="27" y2="7" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
              <line x1="24" y1="7" x2="24" y2="12" stroke="currentColor" stroke-width="2.5" />
              <line x1="34" y1="14" x2="37" y2="11" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
            </svg>
          </div>

          <div class="module-title">在线考试系统</div>
          <div class="module-desc">ACM / OI 双赛制 · 实时排名</div>
          <div class="module-spec mono">题目快照防作弊天梯榜</div>
          <div class="module-arrow">进入机考 →</div>
        </RouterLink>
      </div>
    </section>

    <!-- 交互式技术体验区：实时沙箱评测终端 -->
    <section class="demo-section container">
      <div class="demo-header">
        <div class="demo-eyebrow mono">// SANDBOX RUNNER · LIVE VERDICT</div>
        <h2 class="demo-title">真实评测闭环体验</h2>
        <p class="demo-desc">
          基于 Linux 强隔离容器沙箱与独立 MySQL 临时库，提供毫秒级、防作弊、确定性的技术评测。
        </p>
      </div>

      <div class="terminal-card">
        <div class="terminal-top">
          <div class="terminal-badge">
            <span class="live-dot"></span>
            <span>JUDGE RUNNER #01</span>
          </div>

          <div class="terminal-tabs">
            <button
              class="terminal-tab"
              :class="{ active: activeTab === 'algo' }"
              @click="activeTab = 'algo'"
            >
              Java 算法 OJ
            </button>
            <button
              class="terminal-tab"
              :class="{ active: activeTab === 'sql' }"
              @click="activeTab = 'sql'"
            >
              SQL 数据库 OJ
            </button>
            <button
              class="terminal-tab"
              :class="{ active: activeTab === 'exam' }"
              @click="activeTab = 'exam'"
            >
              ACM 考试结算
            </button>
          </div>
        </div>

        <div class="terminal-meta-bar">
          <span class="file-name">{{ demos[activeTab].filename }}</span>
          <span class="lang-tag">{{ demos[activeTab].langBadge }}</span>
        </div>

        <div class="terminal-body">
          <pre class="code-view"><code v-html="highlightedCode"></code></pre>
        </div>

        <div class="terminal-hud">
          <div class="hud-status-row">
            <span class="verdict-tag" :class="demos[activeTab].verdict.statusClass">
              ● {{ demos[activeTab].verdict.status }}
            </span>
            <span class="verdict-detail">{{ demos[activeTab].verdict.details }}</span>
          </div>
          <div class="hud-metric-row">
            <div
              v-for="m in demos[activeTab].verdict.metrics"
              :key="m.label"
              class="metric-item"
            >
              <div class="metric-title">{{ m.label }}</div>
              <div class="metric-data">{{ m.value }}</div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 底部行动区 CTA -->
    <section class="cta-section container">
      <div class="cta-card">
        <div class="cta-left">
          <h2 class="cta-title">准备好开启专业级技术测评了吗？</h2>
          <p class="cta-desc">
            无论是日常算法与 SQL 训练，还是举办期末机考与企业笔试，KECE 现已全面就绪。
          </p>
        </div>
        <div class="cta-right">
          <button class="btn btn-primary btn-lg" @click="start">开始使用 →</button>
          <RouterLink to="/exams" class="btn btn-ghost btn-lg">参加在线考试</RouterLink>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  padding-bottom: 80px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, 'PingFang SC', 'Microsoft YaHei', monospace;
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
  padding-bottom: 64px;
}

.hero-text {
  max-width: 490px;
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
  cursor: default;
}

/* =========================================
   Trae 官网风格故障风切片 Hover 动效 (Glitch Slicing)
   ========================================= */
.trae-glitch-wrapper {
  position: relative;
  display: inline-block;
}

.trae-glitch {
  position: relative;
  display: inline-block;
  user-select: none;
}

.trae-glitch::before,
.trae-glitch::after {
  content: attr(data-text);
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  color: var(--ink);
  background: var(--bg);
  pointer-events: none;
  opacity: 0;
}

/* 仅在鼠标主动滑动（.is-glitching）时触发切片与阶梯位移；鼠标静止或离开时完全静止，绝不持续自颤 */
.trae-glitch-wrapper.is-glitching .trae-glitch::before {
  opacity: 1;
  clip-path: var(--clip-1, polygon(0 20%, 100% 20%, 100% 40%, 0 40%, 0 65%, 100% 65%, 100% 85%, 0 85%));
  transform: translate(var(--shift-1, 10px), 0);
  transition: transform 0.04s ease-out;
}

.trae-glitch-wrapper.is-glitching .trae-glitch::after {
  opacity: 1;
  clip-path: var(--clip-2, polygon(0 10%, 100% 10%, 100% 30%, 0 30%, 0 50%, 100% 50%, 100% 70%, 0 70%));
  transform: translate(var(--shift-2, -10px), 0);
  transition: transform 0.04s ease-out;
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
  gap: 8px;
  font-size: 10.5px;
  color: var(--ink-tertiary);
  letter-spacing: 0.4px;
}

.spec-pill {
  padding: 2px 6px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.spec-dot {
  color: var(--ink-tertiary);
}

.hero-visual {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

/* =========================================
   2. 全宽 5 大能力评测矩阵（Bento Grid）
   ========================================= */
.modules-section {
  padding-top: 10px;
  padding-bottom: 64px;
}

.modules-header {
  text-align: center;
  max-width: 620px;
  margin: 0 auto 36px;
}

.modules-eyebrow {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.8px;
  color: var(--ink-tertiary);
  margin-bottom: 8px;
}

.modules-title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--ink);
  margin: 0 0 10px;
}

.modules-desc {
  font-size: 14.5px;
  color: var(--ink-secondary);
  line-height: 1.6;
  margin: 0;
}

.modules-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}

.module-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  padding: 24px 20px 22px;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-sm);
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  position: relative;
  text-decoration: none;
  color: inherit;
}

.module-card:hover {
  transform: translateY(-4px);
  border-color: var(--line-strong);
  background: #ffffff;
  box-shadow: 0 16px 36px rgba(17, 24, 39, 0.08);
}

.module-card.exam-card {
  background: #ffffff;
}

.module-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.module-num {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
  background: #ffffff;
  border: 1px solid var(--line);
  padding: 1px 6px;
}

.module-arch-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink-tertiary);
  letter-spacing: 0.4px;
}

.module-icon-box {
  width: 48px;
  height: 48px;
  border-radius: 0;
  background: #ffffff;
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink);
  margin-bottom: 16px;
  transition: all 0.25s ease;
}

.module-card:hover .module-icon-box {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}

.module-svg {
  width: 24px;
  height: 24px;
}

.module-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 4px;
}

.module-desc {
  font-size: 12.5px;
  color: var(--ink-secondary);
  margin-bottom: 12px;
  line-height: 1.4;
}

.module-spec {
  font-size: 10.5px;
  color: var(--ink-tertiary);
  background: #ffffff;
  border: 1px solid var(--line);
  padding: 3px 6px;
  margin-bottom: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.module-arrow {
  margin-top: auto;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-secondary);
  transition: all 0.15s ease;
}

.module-card:hover .module-arrow {
  color: var(--ink);
  transform: translateX(3px);
}

/* =========================================
   3. 交互式沙箱评测终端
   ========================================= */
.demo-section {
  padding-top: 20px;
  padding-bottom: 60px;
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
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--ink);
  margin: 0 0 8px;
}

.demo-desc {
  font-size: 15px;
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

.terminal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: #1b1b1d;
  border-bottom: 1px solid #28282a;
  gap: 12px;
}

.terminal-badge {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
  color: #8c8c90;
  letter-spacing: 0.5px;
}

.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 0;
  background: #40c057;
  box-shadow: 0 0 8px rgba(64, 192, 87, 0.7);
}

.terminal-tabs {
  display: flex;
  gap: 6px;
}

.terminal-tab {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  padding: 5px 14px;
  border-radius: var(--pill);
  color: #88888e;
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
  background: #2c2c30;
  border-color: #404046;
}

.terminal-meta-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 20px;
  background: #171719;
  border-bottom: 1px solid #232326;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  color: #66666e;
}

.file-name {
  color: #9da0a8;
}

.lang-tag {
  color: #7b7e87;
}

.terminal-body {
  padding: 20px;
  background: #111112;
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13.5px;
  line-height: 1.6;
  overflow-x: auto;
  min-height: 200px;
}

.code-view {
  margin: 0;
  color: #e2e4e9;
  white-space: pre;
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
  color: #7f848e;
  font-style: italic;
}

.code-view :deep(.token.operator) {
  color: #56b6c2;
}

.code-view :deep(.token.punctuation) {
  color: #abb2bf;
}

.code-view :deep(.token.property) {
  color: #e06c75;
}

.code-view :deep(.token.namespace) {
  color: #abb2bf;
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
  gap: 12px;
  margin-bottom: 14px;
}

.verdict-tag {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
  padding: 3px 12px;
  border-radius: var(--pill);
}

.verdict-tag.status-ac {
  background: rgba(47, 158, 68, 0.16);
  color: #40c057;
  border: 1px solid rgba(47, 158, 68, 0.35);
}

.verdict-tag.status-exam {
  background: rgba(34, 139, 230, 0.16);
  color: #4dabf7;
  border: 1px solid rgba(34, 139, 230, 0.35);
}

.verdict-detail {
  font-size: 12px;
  color: #8c8f98;
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
  color: #72747d;
  margin-bottom: 2px;
}

.metric-data {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
  color: #ffffff;
}

/* =========================================
   4. 底部 CTA 行动区
   ========================================= */
.cta-section {
  padding-top: 20px;
}

.cta-card {
  background: #141416;
  border-radius: var(--radius);
  border: 1px solid #26262a;
  padding: 44px 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  color: #ffffff;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.08);
}

.cta-title {
  font-size: 26px;
  font-weight: 700;
  color: #ffffff;
  margin: 0 0 8px;
  letter-spacing: -0.4px;
}

.cta-desc {
  font-size: 15px;
  color: #9fa2ab;
  margin: 0;
  max-width: 520px;
  line-height: 1.6;
}

.cta-right {
  display: flex;
  gap: 14px;
  flex-shrink: 0;
}

.cta-card .btn-primary {
  background: #ffffff;
  color: #111111;
}

.cta-card .btn-primary:hover {
  background: #f0f0f0;
}

.cta-card .btn-ghost {
  border-color: #3e3e46;
  color: #e1e3e8;
}

.cta-card .btn-ghost:hover {
  background: #25252a;
  color: #ffffff;
}

/* =========================================
   5. 响应式适配
   ========================================= */
@media (max-width: 1080px) {
  .modules-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

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
  .modules-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .cta-card {
    flex-direction: column;
    text-align: center;
    padding: 32px 24px;
  }
  .cta-right {
    width: 100%;
    flex-direction: column;
  }
  .cta-right .btn {
    width: 100%;
  }
}

@media (max-width: 540px) {
  .hero-title {
    font-size: 34px;
  }
  .modules-grid {
    grid-template-columns: 1fr;
  }
  .hud-metric-row {
    grid-template-columns: 1fr;
  }
  .terminal-tabs {
    display: none;
  }
}
</style>
