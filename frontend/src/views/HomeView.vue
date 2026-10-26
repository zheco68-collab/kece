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
  id: DemoMode
  num: string
  label: string
  summary: string
  filename: string
  langBadge: string
  targetPath: string
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
    id: 'algo',
    num: '01',
    label: 'Java 算法 OJ',
    summary: 'Cgroups v2 隔离容器 · 毫秒级时空限制判定',
    filename: 'Solution.java',
    langBadge: 'Java 17',
    targetPath: '/questions',
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
    id: 'sql',
    num: '02',
    label: 'SQL 数据库 OJ',
    summary: '独立临时 Schema 对拍 · 跑完即自动销毁',
    filename: 'query.sql',
    langBadge: 'MySQL 8.0',
    targetPath: '/database',
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
    id: 'exam',
    num: '03',
    label: 'ACM 考试结算',
    summary: '试卷快照防作弊锁定 · 实时天梯榜单折算',
    filename: 'session_report.json',
    langBadge: 'ACM 赛制',
    targetPath: '/exams',
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

const demoList: DemoMode[] = ['algo', 'sql', 'exam']

const capabilities = [
  {
    num: '01',
    title: '算法编程 OJ',
    desc: '多语言在线编译与流式测试点对拍，基于 Cgroups v2 实现毫秒级时间与内存峰值硬限制。',
    spec: 'C++17 · Java 17 · Python 3 · ArkTS',
    path: '/questions',
  },
  {
    num: '02',
    title: '数据库 SQL OJ',
    desc: '每次提交动态分配独立 MySQL 临时库，支持复杂 SELECT 查询与 DML 数据操纵结果比对。',
    spec: 'MySQL 8.0 · 独立临时库 · 表结构可视',
    path: '/database',
  },
  {
    num: '03',
    title: '前端 AI 智能评测',
    desc: '结合 Playwright 无头浏览器渲染断言与多模态大模型，自动完成 DOM 交互与代码审计。',
    spec: 'Playwright · DOM 断言 · 智能反馈',
    path: '/frontend',
  },
  {
    num: '04',
    title: '标准客观题库',
    desc: '支持单选、多选与填空题型，内置 Markdown 与 KaTeX 数学公式渲染，交卷即时判分解析。',
    spec: '单选 / 多选 / 填空 · LaTeX 公式',
    path: '/objective',
  },
  {
    num: '05',
    title: '随堂课练实训',
    desc: '面向日常教学的编程与客观题混排练习，提供教师端实时完成率统计与督学监控看板。',
    spec: '混合题集 · 实时进度 · 教学实训',
    path: '/practice',
  },
  {
    num: '06',
    title: '在线考试系统',
    desc: '支持 ACM 罚时与 OI 按点计分双赛制，具备开考题目快照冻结、切屏监控与实时天梯榜。',
    spec: 'ACM / OI 双赛制 · 防作弊快照',
    path: '/exams',
  },
]

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

    <!-- ============================================================
         01 · 能力矩阵：极简瑞士十字通栏网格（无碎框、大留白、强秩序）
         ============================================================ -->
    <section class="matrix-section container">
      <div class="sec-header">
        <div class="sec-eyebrow mono">// 01 · CAPABILITY MATRIX</div>
        <div class="sec-head-row">
          <h2 class="sec-title">六大核心评测与考务引擎</h2>
          <p class="sec-sub">统一架构底座，覆盖日常训练、随堂实训与正规机考全场景。</p>
        </div>
      </div>

      <div class="swiss-grid">
        <RouterLink
          v-for="item in capabilities"
          :key="item.num"
          :to="item.path"
          class="swiss-cell"
        >
          <div class="cell-top mono">
            <span class="cell-num">{{ item.num }}</span>
            <span class="cell-arrow">↗</span>
          </div>
          <h3 class="cell-title">{{ item.title }}</h3>
          <p class="cell-desc">{{ item.desc }}</p>
          <div class="cell-spec mono">{{ item.spec }}</div>
        </RouterLink>
      </div>
    </section>

    <!-- ============================================================
         02 · 沙箱闭环：左文右码（与首屏形成视觉呼应，保留代码与语法高亮）
         ============================================================ -->
    <section class="sandbox-section container">
      <div class="sandbox-layout">
        <!-- 左侧：大留白叙事与极简模式切换列表（无外框，呼应首屏左栏） -->
        <div class="sandbox-left">
          <div class="sec-eyebrow mono">// 02 · SANDBOX VERDICT</div>
          <h2 class="sec-title">真实评测闭环体验</h2>
          <p class="sandbox-intro">
            从代码提交、沙箱隔离运行到多维度结果校验，每一次评测均在受控环境中以毫秒级完成。
          </p>

          <div class="mode-list" role="tablist">
            <button
              v-for="mode in demoList"
              :key="mode"
              type="button"
              class="mode-item"
              :class="{ active: activeTab === mode }"
              @click="activeTab = mode"
            >
              <span class="mode-num mono">{{ demos[mode].num }}</span>
              <div class="mode-text">
                <div class="mode-name">{{ demos[mode].label }}</div>
                <div class="mode-summary">{{ demos[mode].summary }}</div>
              </div>
            </button>
          </div>

          <div class="sandbox-action">
            <RouterLink :to="demos[activeTab].targetPath" class="link-explore mono">
              前往 {{ demos[activeTab].label }} 实测 →
            </RouterLink>
          </div>
        </div>

        <!-- 右侧：保留代码展示与 Prism 关键字语法高亮 -->
        <div class="terminal-card">
          <div class="terminal-top">
            <div class="terminal-badge">
              <span class="live-dot"></span>
              <span>{{ demos[activeTab].filename }}</span>
            </div>
            <span class="lang-tag mono">{{ demos[activeTab].langBadge }}</span>
          </div>

          <div class="terminal-body">
            <pre class="code-view"><code v-html="highlightedCode"></code></pre>
          </div>

          <div class="terminal-foot mono">
            <span class="verdict-tag" :class="demos[activeTab].verdict.statusClass">
              ● {{ demos[activeTab].verdict.status }}
            </span>
            <div class="foot-metrics">
              <span v-for="m in demos[activeTab].verdict.metrics" :key="m.label" class="foot-metric-item">
                <span class="fm-k">{{ m.label }}</span>
                <span class="fm-v">{{ m.value }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ============================================================
         03 · 极简底部行动横栏
         ============================================================ -->
    <section class="closing-section container">
      <div class="closing-bar">
        <div class="closing-left">
          <div class="sec-eyebrow mono">// 03 · GET STARTED</div>
          <h2 class="closing-title">准备好开启技术评测了吗？</h2>
        </div>
        <div class="closing-actions">
          <button class="btn btn-primary btn-lg" @click="start">开始使用 →</button>
          <RouterLink to="/rankings" class="btn btn-ghost btn-lg">查看全站排名</RouterLink>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  padding-bottom: 96px;
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
  padding-bottom: 72px;
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
   通用极简章节头部
   ========================================= */
.sec-eyebrow {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.8px;
  color: var(--ink-tertiary);
  margin-bottom: 10px;
}

.sec-title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.5px;
  color: var(--ink);
  margin: 0;
  line-height: 1.25;
}

/* =========================================
   2. 极简瑞士十字通栏网格 (Swiss Architectural Grid)
   ========================================= */
.matrix-section {
  padding-top: 48px;
  padding-bottom: 80px;
  border-top: 1px solid var(--line);
}

.sec-header {
  margin-bottom: 36px;
}

.sec-head-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.sec-sub {
  font-size: 14.5px;
  color: var(--ink-secondary);
  margin: 0;
}

/* 单一 1px 细线交织的 3x2 建筑感网格，彻底告别碎片化嵌套边框 */
.swiss-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-top: 1px solid var(--line);
  border-left: 1px solid var(--line);
}

.swiss-cell {
  padding: 36px 32px;
  background: #ffffff;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  text-decoration: none;
  color: inherit;
  position: relative;
  transition: background 0.2s ease;
}

.swiss-cell::before {
  content: '';
  position: absolute;
  top: -1px;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--ink);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.swiss-cell:hover {
  background: #fafafa;
}

.swiss-cell:hover::before {
  transform: scaleX(1);
}

.cell-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  font-size: 12px;
  color: var(--ink-tertiary);
}

.cell-num {
  font-weight: 600;
}

.cell-arrow {
  font-size: 14px;
  color: var(--ink-tertiary);
  transition: transform 0.2s ease, color 0.2s ease;
}

.swiss-cell:hover .cell-arrow {
  color: var(--ink);
  transform: translate(3px, -3px);
}

.cell-title {
  font-size: 19px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 10px;
  letter-spacing: -0.3px;
}

.cell-desc {
  font-size: 14px;
  line-height: 1.65;
  color: var(--ink-secondary);
  margin: 0 0 28px;
  flex: 1;
}

.cell-spec {
  font-size: 11.5px;
  color: var(--ink-tertiary);
  padding-top: 16px;
  border-top: 1px dashed #e5e7eb;
}

/* =========================================
   3. 沙箱闭环体验：左文右码（呼应首屏布局）
   ========================================= */
.sandbox-section {
  padding-top: 64px;
  padding-bottom: 80px;
  border-top: 1px solid var(--line);
}

.sandbox-layout {
  display: grid;
  grid-template-columns: 380px 1fr;
  gap: 56px;
  align-items: flex-start;
}

.sandbox-left {
  display: flex;
  flex-direction: column;
}

.sandbox-intro {
  font-size: 14.5px;
  line-height: 1.65;
  color: var(--ink-secondary);
  margin: 14px 0 32px;
}

/* 极简纵向切换列表 */
.mode-list {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--line);
}

.mode-item {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 18px 16px;
  text-align: left;
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--line);
  border-left: 3px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
}

.mode-item:hover {
  background: var(--surface);
}

.mode-item.active {
  background: var(--surface);
  border-left-color: var(--ink);
}

.mode-num {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-tertiary);
  padding-top: 2px;
}

.mode-item.active .mode-num {
  color: var(--ink);
}

.mode-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 4px;
}

.mode-summary {
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.sandbox-action {
  margin-top: 24px;
}

.link-explore {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
  padding-bottom: 3px;
  border-bottom: 1.5px solid var(--ink);
  transition: opacity 0.15s ease;
}

.link-explore:hover {
  opacity: 0.7;
}

/* 右侧：暗色代码终端与语法高亮 */
.terminal-card {
  background: #141415;
  border-radius: 0;
  border: 1px solid #28282a;
  color: #f1f1f1;
  overflow: hidden;
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.12);
}

.terminal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 20px;
  background: #1b1b1d;
  border-bottom: 1px solid #28282a;
}

.terminal-badge {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
  color: #d4d6dd;
}

.live-dot {
  width: 7px;
  height: 7px;
  background: #40c057;
  box-shadow: 0 0 8px rgba(64, 192, 87, 0.7);
}

.lang-tag {
  font-size: 11.5px;
  color: #7b7e87;
}

.terminal-body {
  padding: 24px 22px;
  background: #111112;
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13.5px;
  line-height: 1.65;
  overflow-x: auto;
  min-height: 290px;
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

/* 终端底部极简一行流遥测条 */
.terminal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  padding: 12px 20px;
  background: #18181a;
  border-top: 1px solid #262629;
  font-size: 12px;
}

.verdict-tag {
  font-weight: 700;
}

.verdict-tag.status-ac {
  color: #40c057;
}

.verdict-tag.status-exam {
  color: #4dabf7;
}

.foot-metrics {
  display: flex;
  align-items: center;
  gap: 20px;
}

.foot-metric-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.fm-k {
  color: #72747d;
}

.fm-v {
  color: #e2e4e9;
  font-weight: 600;
}

/* =========================================
   4. 极简底部行动横栏
   ========================================= */
.closing-section {
  padding-top: 48px;
  border-top: 1px solid var(--line);
}

.closing-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  flex-wrap: wrap;
}

.closing-title {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--ink);
  margin: 0;
}

.closing-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* =========================================
   5. 响应式适配
   ========================================= */
@media (max-width: 960px) {
  .swiss-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .sandbox-layout {
    grid-template-columns: 1fr;
    gap: 32px;
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
}

@media (max-width: 600px) {
  .hero-title {
    font-size: 34px;
  }
  .swiss-grid {
    grid-template-columns: 1fr;
  }
  .closing-bar {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
