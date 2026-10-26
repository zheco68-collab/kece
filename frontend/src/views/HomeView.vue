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

interface PipelineStage {
  step: string
  title: string
  meta: string
}

interface DemoContent {
  filename: string
  langBadge: string
  runnerTag: string
  targetPath: string
  targetLabel: string
  code: string
  pipeline: PipelineStage[]
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
    runnerTag: 'NODE-ALGO-01 · CGROUPS V2',
    targetPath: '/questions',
    targetLabel: '进入算法题库实测 →',
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
    pipeline: [
      { step: '01', title: '源码词法检查与字节码编译', meta: 'javac -encoding UTF-8 · 0 Errors' },
      { step: '02', title: 'Cgroups v2 容器命名空间隔离挂载', meta: 'PID / NET / IPC 独立沙箱限制' },
      { step: '03', title: '全量标准测试点并发流式对拍', meta: '10 / 10 Cases · Zero-Copy Pipe' },
      { step: '04', title: '峰值资源审计与临时容器销毁', meta: 'CPU & RSS 精确采样归档' },
    ],
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
    runnerTag: 'NODE-SQL-01 · EPHEMERAL DB',
    targetPath: '/database',
    targetLabel: '进入数据库题库实测 →',
    code: `-- 查询研发部薪资最高的前三名员工
SELECT
    e.id,
    e.name,
    e.salary
FROM emp e
WHERE e.dept = 'RD'
ORDER BY e.salary DESC, e.id ASC
LIMIT 3;`,
    pipeline: [
      { step: '01', title: '分配一次性物理隔离临时库', meta: 'CREATE SCHEMA judge_sub_01' },
      { step: '02', title: '注入建表 Schema 与基准测试数据', meta: 'InnoDB 事务级初始化快照' },
      { step: '03', title: '执行考生 SQL 与标程结果集对拍', meta: '字段类型 · 行序 · 浮点精度比对' },
      { step: '04', title: '毫秒级自动销毁临时数据库实例', meta: 'DROP SCHEMA · 零状态残留' },
    ],
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
    runnerTag: 'EXAM-ENGINE · SNAPSHOT LOCK',
    targetPath: '/exams',
    targetLabel: '进入在线考试系统 →',
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
    pipeline: [
      { step: '01', title: '开考拉取不可变题目快照副本', meta: 'Snapshot Isolation · 防中途改题影响' },
      { step: '02', title: '焦点切屏监测与防作弊行为记录', meta: 'Visibility API · 实时告警审计' },
      { step: '03', title: '客观题与编程题混合自动判分', meta: 'ACM 罚时 / OI 按测试点折算' },
      { step: '04', title: '全考场实时天梯榜单聚合结算', meta: 'Rank #1 · 成绩单即时归档' },
    ],
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

    <!-- ============================================================
         SECTION 01 · 全栈技术与工程能力评测矩阵（工业级 Bento 规格矩阵）
         ============================================================ -->
    <section class="matrix-section container">
      <!-- 工业分栏头部条 -->
      <div class="section-head-bar">
        <div class="section-head-left">
          <div class="section-eyebrow mono">
            <span class="sec-index">01</span>
            <span>// CAPABILITY MATRIX · 6 CORE MODULES</span>
          </div>
          <h2 class="section-title">全栈技术与工程能力评测矩阵</h2>
        </div>
        <div class="section-head-right">
          <p class="section-desc">
            每个评测模块均具备物理级沙箱隔离与自动化判分流水线，覆盖从日常算法刷题、随堂实训到正规机考的全链路场景。
          </p>
        </div>
      </div>

      <!-- 2 + 4 双层工业级 Bento 矩阵 -->
      <div class="bento-matrix">
        <!-- 第一层：2 大主力沙箱引擎（双宽旗舰规格卡） -->
        <div class="bento-row-primary">
          <!-- 01 算法编程 OJ -->
          <RouterLink to="/questions" class="engine-card is-primary">
            <div class="engine-card-bar mono">
              <div class="bar-left">
                <span class="engine-idx">01</span>
                <span class="engine-code">ENGINE // ALGO_SANDBOX</span>
              </div>
              <span class="engine-status-pill is-online">
                <span class="pip"></span>
                <span>CGROUPS V2 强隔离</span>
              </span>
            </div>

            <div class="engine-card-body">
              <div class="engine-main-top">
                <div class="engine-icon-frame">
                  <svg class="engine-svg" viewBox="0 0 48 48" fill="none">
                    <rect x="6" y="6" width="36" height="36" stroke="currentColor" stroke-width="2.5" />
                    <path d="M18 20L14 24L18 28" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
                    <path d="M30 20L34 24L30 28" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
                    <path d="M26 17L22 31" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
                  </svg>
                </div>
                <div class="engine-title-group">
                  <h3 class="engine-title">算法编程 OJ</h3>
                  <p class="engine-subtitle">多语言在线编译与毫秒级时空受限沙箱评测</p>
                </div>
              </div>

              <p class="engine-detail">
                基于 Linux Cgroups v2 与容器命名空间实现严格内存与 CPU 时间片限制，支持多测试点并发流式比对与详细错误栈定位。
              </p>

              <div class="engine-spec-grid mono">
                <div class="spec-cell">
                  <span class="spec-k">LANGUAGES</span>
                  <span class="spec-v">C++17 / Java 17 / Python 3 / ArkTS</span>
                </div>
                <div class="spec-cell">
                  <span class="spec-k">ISOLATION</span>
                  <span class="spec-v">PID · NET · IPC · RSS 硬限</span>
                </div>
              </div>
            </div>

            <div class="engine-card-foot mono">
              <span class="foot-meta">VERDICT: AC / WA / TLE / MLE / RE / CE</span>
              <span class="foot-cta">进入算法题库 →</span>
            </div>
          </RouterLink>

          <!-- 02 数据库 SQL OJ -->
          <RouterLink to="/database" class="engine-card is-primary">
            <div class="engine-card-bar mono">
              <div class="bar-left">
                <span class="engine-idx">02</span>
                <span class="engine-code">ENGINE // MYSQL_EPHEMERAL</span>
              </div>
              <span class="engine-status-pill is-online">
                <span class="pip"></span>
                <span>MYSQL 8.0 临时库</span>
              </span>
            </div>

            <div class="engine-card-body">
              <div class="engine-main-top">
                <div class="engine-icon-frame">
                  <svg class="engine-svg" viewBox="0 0 48 48" fill="none">
                    <ellipse cx="24" cy="14" rx="14" ry="5.5" stroke="currentColor" stroke-width="2.5" />
                    <path d="M10 14V24C10 27 16.3 29.5 24 29.5C31.7 29.5 38 27 38 24V14" stroke="currentColor" stroke-width="2.5" />
                    <path d="M10 24V34C10 37 16.3 39.5 24 39.5C31.7 39.5 38 37 38 34V24" stroke="currentColor" stroke-width="2.5" />
                  </svg>
                </div>
                <div class="engine-title-group">
                  <h3 class="engine-title">数据库 SQL OJ</h3>
                  <p class="engine-subtitle">真实关系型数据库环境下的查询与变更评测</p>
                </div>
              </div>

              <p class="engine-detail">
                每次提交动态分配独立 MySQL 临时 Schema，自动执行建表与初始数据灌入，支持复杂 SELECT 查询与 DML 数据操纵结果集精准对拍。
              </p>

              <div class="engine-spec-grid mono">
                <div class="spec-cell">
                  <span class="spec-k">WORKLOAD</span>
                  <span class="spec-v">SELECT 检索 / INSERT · UPDATE · DELETE</span>
                </div>
                <div class="spec-cell">
                  <span class="spec-k">LIFECYCLE</span>
                  <span class="spec-v">单次提交独占 Schema · 跑完即焚</span>
                </div>
              </div>
            </div>

            <div class="engine-card-foot mono">
              <span class="foot-meta">SCHEMA PARSER · 交互式表结构预览</span>
              <span class="foot-cta">进入数据库题库 →</span>
            </div>
          </RouterLink>
        </div>

        <!-- 第二层：4 大专项评测与教学考务系统 -->
        <div class="bento-row-secondary">
          <!-- 03 前端 AI 评测 -->
          <RouterLink to="/frontend" class="engine-card">
            <div class="engine-card-bar mono">
              <span class="engine-idx">03</span>
              <span class="engine-tag">PLAYWRIGHT + AI</span>
            </div>
            <div class="engine-card-body">
              <div class="sub-card-head">
                <div class="engine-icon-frame is-sm">
                  <svg class="engine-svg" viewBox="0 0 48 48" fill="none">
                    <rect x="7" y="9" width="34" height="30" stroke="currentColor" stroke-width="2.5" />
                    <line x1="7" y1="18" x2="41" y2="18" stroke="currentColor" stroke-width="2.5" />
                    <path d="M16 29L20 33L32 23" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </div>
                <h3 class="sub-card-title">前端 AI 智能评测</h3>
              </div>
              <p class="sub-card-desc">无头浏览器渲染快照、DOM 交互断言与多模态大模型代码质量审计。</p>
              <div class="sub-card-pills mono">
                <span class="mini-pill">DOM 断言</span>
                <span class="mini-pill">视觉审计</span>
              </div>
            </div>
            <div class="engine-card-foot mono">
              <span class="foot-meta">UI SANDBOX</span>
              <span class="foot-cta">体验评测 →</span>
            </div>
          </RouterLink>

          <!-- 04 标准客观题 -->
          <RouterLink to="/objective" class="engine-card">
            <div class="engine-card-bar mono">
              <span class="engine-idx">04</span>
              <span class="engine-tag">KATEX MATH</span>
            </div>
            <div class="engine-card-body">
              <div class="sub-card-head">
                <div class="engine-icon-frame is-sm">
                  <svg class="engine-svg" viewBox="0 0 48 48" fill="none">
                    <rect x="9" y="7" width="30" height="34" stroke="currentColor" stroke-width="2.5" />
                    <line x1="16" y1="16" x2="32" y2="16" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
                    <line x1="16" y1="23" x2="32" y2="23" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
                    <line x1="16" y1="30" x2="26" y2="30" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
                  </svg>
                </div>
                <h3 class="sub-card-title">标准客观题库</h3>
              </div>
              <p class="sub-card-desc">涵盖单选、多选与填空题型，原生支持 Markdown 与 LaTeX 数学公式渲染及即时判分解析。</p>
              <div class="sub-card-pills mono">
                <span class="mini-pill">单选/多选/填空</span>
                <span class="mini-pill">自动批改</span>
              </div>
            </div>
            <div class="engine-card-foot mono">
              <span class="foot-meta">AUTO GRADING</span>
              <span class="foot-cta">进入题库 →</span>
            </div>
          </RouterLink>

          <!-- 05 随堂课练系统 -->
          <RouterLink to="/practice" class="engine-card">
            <div class="engine-card-bar mono">
              <span class="engine-idx">05</span>
              <span class="engine-tag">LIVE MONITOR</span>
            </div>
            <div class="engine-card-body">
              <div class="sub-card-head">
                <div class="engine-icon-frame is-sm">
                  <svg class="engine-svg" viewBox="0 0 48 48" fill="none">
                    <rect x="6" y="10" width="36" height="28" stroke="currentColor" stroke-width="2.5" />
                    <path d="M14 28L20 22L26 26L34 18" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </div>
                <h3 class="sub-card-title">随堂课练实训</h3>
              </div>
              <p class="sub-card-desc">面向日常教学的算法、SQL 与客观题混合编排实训，提供教师端实时完成率与督学监控看板。</p>
              <div class="sub-card-pills mono">
                <span class="mini-pill">混合题集</span>
                <span class="mini-pill">实训看板</span>
              </div>
            </div>
            <div class="engine-card-foot mono">
              <span class="foot-meta">CLASSROOM LAB</span>
              <span class="foot-cta">进入课练 →</span>
            </div>
          </RouterLink>

          <!-- 06 在线考试系统 -->
          <RouterLink to="/exams" class="engine-card">
            <div class="engine-card-bar mono">
              <span class="engine-idx">06</span>
              <span class="engine-tag">ACM / OI MODE</span>
            </div>
            <div class="engine-card-body">
              <div class="sub-card-head">
                <div class="engine-icon-frame is-sm">
                  <svg class="engine-svg" viewBox="0 0 48 48" fill="none">
                    <circle cx="24" cy="26" r="14" stroke="currentColor" stroke-width="2.5" />
                    <path d="M24 18V26L29 29" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
                    <line x1="20" y1="7" x2="28" y2="7" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
                  </svg>
                </div>
                <h3 class="sub-card-title">在线机考与天梯</h3>
              </div>
              <p class="sub-card-desc">支持 ACM 罚时赛制与 OI 按点计分赛制，内置开考题目快照冻结、切屏防作弊统计与实时榜单。</p>
              <div class="sub-card-pills mono">
                <span class="mini-pill">题目快照</span>
                <span class="mini-pill">切屏审计</span>
              </div>
            </div>
            <div class="engine-card-foot mono">
              <span class="foot-meta">PROCTORED EXAM</span>
              <span class="foot-cta">进入机考 →</span>
            </div>
          </RouterLink>
        </div>
      </div>
    </section>

    <!-- ============================================================
         SECTION 02 · 真实评测闭环体验（左：语法高亮代码终端 / 右：工业级判题遥测检视器）
         ============================================================ -->
    <section class="demo-section container">
      <!-- 工业分栏头部条 + 模式切换器 -->
      <div class="section-head-bar">
        <div class="section-head-left">
          <div class="section-eyebrow mono">
            <span class="sec-index">02</span>
            <span>// SANDBOX RUNNER · LIVE VERDICT</span>
          </div>
          <h2 class="section-title">真实评测闭环体验</h2>
        </div>

        <div class="workbench-Select-group mono" role="tablist">
          <button
            type="button"
            class="wb-mode-btn"
            :class="{ active: activeTab === 'algo' }"
            @click="activeTab = 'algo'"
          >
            <span class="wb-mode-num">01</span>
            <span>Java 算法 OJ</span>
          </button>
          <button
            type="button"
            class="wb-mode-btn"
            :class="{ active: activeTab === 'sql' }"
            @click="activeTab = 'sql'"
          >
            <span class="wb-mode-num">02</span>
            <span>SQL 数据库 OJ</span>
          </button>
          <button
            type="button"
            class="wb-mode-btn"
            :class="{ active: activeTab === 'exam' }"
            @click="activeTab = 'exam'"
          >
            <span class="wb-mode-num">03</span>
            <span>ACM 考试结算</span>
          </button>
        </div>
      </div>

      <!-- 双栏工业评测工作台：左侧保留代码展示与语法高亮，右侧配对 ClusterConsole 同款工业风遥测检视面板 -->
      <div class="workbench-split">
        <!-- 左侧：代码展示与 Prism 语法高亮终端（保留原有改动） -->
        <div class="terminal-card">
          <div class="terminal-top">
            <div class="terminal-badge">
              <span class="live-dot"></span>
              <span>JUDGE RUNNER #01</span>
              <span class="runner-divider">/</span>
              <span class="runner-sub">{{ demos[activeTab].runnerTag }}</span>
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
            <span class="lang-tag">{{ demos[activeTab].langBadge }} · UTF-8</span>
          </div>

          <div class="terminal-body">
            <pre class="code-view"><code v-html="highlightedCode"></code></pre>
          </div>
        </div>

        <!-- 右侧：与项目直角工业风一致的判题流水线与遥测检视面板 -->
        <aside class="verdict-inspector">
          <!-- 检视器顶栏 -->
          <div class="inspector-head mono">
            <div class="ins-head-left">
              <span class="ins-dot"></span>
              <span class="ins-title">// VERDICT TELEMETRY</span>
            </div>
            <span class="ins-verdict-badge" :class="demos[activeTab].verdict.statusClass">
              ● {{ demos[activeTab].verdict.status }}
            </span>
          </div>

          <!-- 核心指标 3 宫格 -->
          <div class="inspector-metrics">
            <div
              v-for="m in demos[activeTab].verdict.metrics"
              :key="m.label"
              class="ins-metric-box"
            >
              <div class="ins-metric-label">{{ m.label }}</div>
              <div class="ins-metric-val mono">{{ m.value }}</div>
            </div>
          </div>

          <!-- 沙箱判题 4 步确定性流水线 -->
          <div class="inspector-pipeline">
            <div class="pipeline-head mono">
              <span>EXECUTION PIPELINE</span>
              <span>4 STAGES · VERIFIED</span>
            </div>
            <div class="pipeline-list">
              <div
                v-for="stage in demos[activeTab].pipeline"
                :key="stage.step"
                class="pipeline-item"
              >
                <span class="stage-num mono">{{ stage.step }}</span>
                <div class="stage-info">
                  <div class="stage-title">{{ stage.title }}</div>
                  <div class="stage-meta mono">{{ stage.meta }}</div>
                </div>
                <span class="stage-check mono">OK</span>
              </div>
            </div>
          </div>

          <!-- 检视器底栏：隔离说明与快速实测入口 -->
          <div class="inspector-foot">
            <div class="ins-foot-note mono">
              {{ demos[activeTab].verdict.details }}
            </div>
            <RouterLink :to="demos[activeTab].targetPath" class="ins-action-link mono">
              {{ demos[activeTab].targetLabel }}
            </RouterLink>
          </div>
        </aside>
      </div>
    </section>

    <!-- ============================================================
         SECTION 03 · 工业级安全底座规格与快速启航控制台
         ============================================================ -->
    <section class="arch-section container">
      <div class="arch-console">
        <!-- 顶部：4 项底层工程规格横条 -->
        <div class="arch-specs-row">
          <div class="arch-spec-item">
            <div class="arch-spec-top mono">
              <span class="arch-code">SPEC // 01</span>
              <span class="arch-badge">KERNEL</span>
            </div>
            <div class="arch-spec-name">Cgroups v2 资源硬限</div>
            <p class="arch-spec-desc">进程数、内存水位与 CPU 时间片内核级隔离，杜绝 Fork 炸弹与恶意系统调用。</p>
          </div>

          <div class="arch-spec-item">
            <div class="arch-spec-top mono">
              <span class="arch-code">SPEC // 02</span>
              <span class="arch-badge">DATABASE</span>
            </div>
            <div class="arch-spec-name">独立临时库对拍</div>
            <p class="arch-spec-desc">每次 SQL 判题动态创建隔离 Schema 并自动回滚销毁，保障并发判题零脏数据干扰。</p>
          </div>

          <div class="arch-spec-item">
            <div class="arch-spec-top mono">
              <span class="arch-code">SPEC // 03</span>
              <span class="arch-badge">SECURITY</span>
            </div>
            <div class="arch-spec-name">题目快照与防作弊审计</div>
            <p class="arch-spec-desc">开考瞬间冻结试卷快照，全程记录切屏次数与提交轨迹，保障考核公平可追溯。</p>
          </div>

          <div class="arch-spec-item">
            <div class="arch-spec-top mono">
              <span class="arch-code">SPEC // 04</span>
              <span class="arch-badge">RANKING</span>
            </div>
            <div class="arch-spec-name">双赛制实时天梯结算</div>
            <p class="arch-spec-desc">支持 ACM 罚时榜与 OI 得分榜双引擎实时聚合计算，提供多维个人能力画像。</p>
          </div>
        </div>

        <!-- 底部：浅色工业风行动控制台 -->
        <div class="arch-cta-bar">
          <div class="arch-cta-left">
            <div class="arch-cta-eyebrow mono">// READY FOR PRODUCTION ASSESSMENT</div>
            <h2 class="arch-cta-title">准备好开启专业级技术测评了吗？</h2>
            <p class="arch-cta-desc">
              无论是日常算法与 SQL 实战训练，还是组织随堂课练与正规机考，KECE 评测集群现已全面就绪。
            </p>
          </div>
          <div class="arch-cta-actions">
            <button class="btn btn-primary btn-lg" @click="start">立即开始刷题 →</button>
            <RouterLink to="/exams" class="btn btn-ghost btn-lg">参加在线考试</RouterLink>
            <RouterLink to="/rankings" class="btn btn-ghost btn-lg">全站天梯榜</RouterLink>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  padding-bottom: 88px;
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
   通用工业分栏头部 (Section Header Bar)
   ========================================= */
.section-head-bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  padding-top: 28px;
  padding-bottom: 24px;
  margin-bottom: 24px;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.section-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.8px;
  color: var(--ink-tertiary);
  margin-bottom: 8px;
}

.sec-index {
  display: inline-block;
  padding: 1px 6px;
  background: var(--ink);
  color: #ffffff;
  font-size: 10.5px;
  font-weight: 700;
}

.section-title {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--ink);
  margin: 0;
  line-height: 1.25;
}

.section-head-right {
  max-width: 460px;
}

.section-desc {
  font-size: 14px;
  color: var(--ink-secondary);
  line-height: 1.6;
  margin: 0;
}

/* =========================================
   2. 全栈技术与工程能力评测矩阵 (Industrial Bento Matrix)
   ========================================= */
.matrix-section {
  padding-top: 8px;
  padding-bottom: 56px;
}

.bento-matrix {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.bento-row-primary {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.bento-row-secondary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.engine-card {
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 0;
  display: flex;
  flex-direction: column;
  text-decoration: none;
  color: inherit;
  box-shadow: var(--shadow-sm);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.engine-card:hover {
  border-color: var(--line-strong);
  transform: translateY(-3px);
  box-shadow: 0 14px 32px rgba(17, 17, 17, 0.07);
}

/* 卡片顶部工业规格栏 */
.engine-card-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 18px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  font-size: 11px;
}

.bar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.engine-idx {
  font-weight: 700;
  color: var(--ink);
  background: #ffffff;
  border: 1px solid var(--line);
  padding: 1px 6px;
}

.engine-code {
  font-weight: 700;
  color: var(--ink-secondary);
  letter-spacing: 0.4px;
}

.engine-tag {
  font-size: 10.5px;
  font-weight: 700;
  color: var(--ink-tertiary);
  background: #ffffff;
  border: 1px solid var(--line);
  padding: 1px 6px;
  letter-spacing: 0.4px;
}

.engine-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  font-weight: 600;
  padding: 1px 8px;
  background: var(--success-bg);
  border: 1px solid #b2f2bb;
  color: var(--success);
}

.engine-status-pill .pip {
  width: 5px;
  height: 5px;
  background: var(--success);
  box-shadow: 0 0 4px rgba(47, 158, 68, 0.6);
}

/* 主力引擎卡片主体 */
.engine-card-body {
  padding: 20px 18px;
  display: flex;
  flex-direction: column;
  flex: 1;
}

.engine-main-top {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 12px;
}

.engine-icon-frame {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  background: var(--surface);
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink);
  transition: all 0.2s ease;
}

.engine-icon-frame.is-sm {
  width: 36px;
  height: 36px;
}

.engine-card:hover .engine-icon-frame {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
}

.engine-svg {
  width: 22px;
  height: 22px;
}

.engine-icon-frame.is-sm .engine-svg {
  width: 18px;
  height: 18px;
}

.engine-title-group {
  min-width: 0;
}

.engine-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 3px;
  line-height: 1.25;
}

.engine-subtitle {
  font-size: 13px;
  color: var(--ink-secondary);
  margin: 0;
}

.engine-detail {
  font-size: 13.5px;
  color: var(--ink-secondary);
  line-height: 1.6;
  margin: 0 0 16px;
}

.engine-spec-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: auto;
}

.spec-cell {
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.spec-k {
  font-size: 10px;
  font-weight: 700;
  color: var(--ink-tertiary);
  letter-spacing: 0.4px;
}

.spec-v {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 第二层专项卡片内部排版 */
.sub-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.sub-card-title {
  font-size: 15.5px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
}

.sub-card-desc {
  font-size: 13px;
  color: var(--ink-secondary);
  line-height: 1.55;
  margin: 0 0 14px;
  flex: 1;
}

.sub-card-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: auto;
}

.mini-pill {
  font-size: 10.5px;
  padding: 2px 7px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

/* 卡片底栏 */
.engine-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 18px;
  background: var(--surface);
  border-top: 1px solid var(--line);
  font-size: 11.5px;
  transition: background 0.2s ease, color 0.2s ease;
}

.foot-meta {
  color: var(--ink-tertiary);
  font-size: 10.5px;
}

.foot-cta {
  font-weight: 700;
  color: var(--ink);
  transition: transform 0.15s ease;
}

.engine-card:hover .engine-card-foot {
  background: var(--ink);
  border-top-color: var(--ink);
}

.engine-card:hover .foot-meta {
  color: #a0a4ab;
}

.engine-card:hover .foot-cta {
  color: #ffffff;
  transform: translateX(2px);
}

/* =========================================
   3. 交互式沙箱评测工作台 (Split Workbench: 左代码高亮 + 右工业检视器)
   ========================================= */
.demo-section {
  padding-top: 8px;
  padding-bottom: 56px;
}

.workbench-Select-group {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.wb-mode-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  background: var(--surface);
  border: 1px solid var(--line);
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.wb-mode-btn:hover {
  border-color: var(--line-strong);
  color: var(--ink);
  background: #ffffff;
}

.wb-mode-btn.active {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
}

.wb-mode-num {
  font-size: 10.5px;
  padding: 0 5px;
  border: 1px solid currentColor;
  opacity: 0.75;
}

.workbench-split {
  display: grid;
  grid-template-columns: 1.32fr 1fr;
  gap: 18px;
  align-items: stretch;
}

/* 左侧：暗色代码终端与语法高亮（完整保留） */
.terminal-card {
  background: #141415;
  border-radius: var(--radius);
  border: 1px solid #28282a;
  color: #f1f1f1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-md);
}

.terminal-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px;
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
  color: #9da0a8;
  letter-spacing: 0.4px;
}

.runner-divider {
  color: #45464c;
}

.runner-sub {
  color: #6c6f78;
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
  font-size: 11.5px;
  padding: 4px 11px;
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
  padding: 8px 18px;
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
  flex: 1;
  min-height: 280px;
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

/* 右侧：工业风沙箱判题遥测检视面板 (对齐 ClusterConsole 视觉语言) */
.verdict-inspector {
  background: #ffffff;
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-sm);
}

.inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 18px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  gap: 10px;
}

.ins-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ins-dot {
  width: 7px;
  height: 7px;
  background: var(--success);
  box-shadow: 0 0 6px rgba(47, 158, 68, 0.7);
}

.ins-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: 0.5px;
}

.ins-verdict-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 9px;
  border: 1px solid var(--line);
}

.ins-verdict-badge.status-ac {
  background: var(--success-bg);
  color: var(--success);
  border-color: #b2f2bb;
}

.ins-verdict-badge.status-exam {
  background: #e7f5ff;
  color: #1971c2;
  border-color: #a5d8ff;
}

.inspector-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  padding: 16px 18px 12px;
}

.ins-metric-box {
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 10px 12px;
}

.ins-metric-label {
  font-size: 11px;
  color: var(--ink-tertiary);
  margin-bottom: 3px;
}

.ins-metric-val {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

.inspector-pipeline {
  padding: 6px 18px 16px;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.pipeline-head {
  display: flex;
  justify-content: space-between;
  font-size: 10.5px;
  font-weight: 700;
  color: var(--ink-tertiary);
  letter-spacing: 0.5px;
  padding-bottom: 8px;
  margin-bottom: 10px;
  border-bottom: 1px dashed var(--line);
}

.pipeline-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pipeline-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  transition: border-color 0.15s ease, background 0.15s ease;
}

.pipeline-item:hover {
  background: #ffffff;
  border-color: var(--line-strong);
}

.stage-num {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 6px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink);
}

.stage-info {
  flex: 1;
  min-width: 0;
}

.stage-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.3;
}

.stage-meta {
  font-size: 11px;
  color: var(--ink-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stage-check {
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 6px;
  background: var(--success-bg);
  border: 1px solid #b2f2bb;
  color: var(--success);
}

.inspector-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  background: var(--surface);
  border-top: 1px solid var(--line);
  margin-top: auto;
}

.ins-foot-note {
  font-size: 11px;
  color: var(--ink-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ins-action-link {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
  white-space: nowrap;
  padding: 4px 10px;
  background: #ffffff;
  border: 1px solid var(--line-strong);
  transition: all 0.15s ease;
}

.ins-action-link:hover {
  background: var(--ink);
  color: #ffffff;
}

/* =========================================
   4. 工业级安全底座规格与底部行动控制台 (Architecture & Action Console)
   ========================================= */
.arch-section {
  padding-top: 8px;
}

.arch-console {
  background: #ffffff;
  border: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
}

.arch-specs-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  border-bottom: 1px solid var(--line);
}

.arch-spec-item {
  padding: 22px 20px;
  border-right: 1px solid var(--line);
  background: var(--surface);
  transition: background 0.2s ease;
}

.arch-spec-item:last-child {
  border-right: none;
}

.arch-spec-item:hover {
  background: #ffffff;
}

.arch-spec-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 10.5px;
}

.arch-code {
  font-weight: 700;
  color: var(--ink-tertiary);
}

.arch-badge {
  font-weight: 700;
  padding: 1px 6px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink);
}

.arch-spec-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 6px;
}

.arch-spec-desc {
  font-size: 12.5px;
  color: var(--ink-secondary);
  line-height: 1.55;
  margin: 0;
}

.arch-cta-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  padding: 32px 28px;
  background: #ffffff;
}

.arch-cta-eyebrow {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.8px;
  color: var(--ink-tertiary);
  margin-bottom: 6px;
}

.arch-cta-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 6px;
  letter-spacing: -0.4px;
}

.arch-cta-desc {
  font-size: 14px;
  color: var(--ink-secondary);
  margin: 0;
  max-width: 540px;
}

.arch-cta-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  flex-wrap: wrap;
}

/* =========================================
   5. 响应式适配
   ========================================= */
@media (max-width: 1024px) {
  .bento-row-secondary {
    grid-template-columns: repeat(2, 1fr);
  }
  .workbench-split {
    grid-template-columns: 1fr;
  }
  .arch-specs-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .arch-spec-item:nth-child(2) {
    border-right: none;
  }
  .arch-spec-item:nth-child(1),
  .arch-spec-item:nth-child(2) {
    border-bottom: 1px solid var(--line);
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
  .section-head-bar {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .bento-row-primary {
    grid-template-columns: 1fr;
  }
  .arch-cta-bar {
    flex-direction: column;
    align-items: flex-start;
    padding: 24px 20px;
  }
  .arch-cta-actions {
    width: 100%;
  }
}

@media (max-width: 540px) {
  .hero-title {
    font-size: 34px;
  }
  .bento-row-secondary {
    grid-template-columns: 1fr;
  }
  .engine-spec-grid {
    grid-template-columns: 1fr;
  }
  .inspector-metrics {
    grid-template-columns: 1fr;
  }
  .arch-specs-row {
    grid-template-columns: 1fr;
  }
  .arch-spec-item {
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
  .arch-spec-item:last-child {
    border-bottom: none;
  }
  .terminal-tabs {
    display: none;
  }
}
</style>
