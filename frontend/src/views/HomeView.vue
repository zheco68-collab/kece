<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import ClusterConsole from '@/components/ClusterConsole.vue'
import HomePracticeBoard from '@/components/HomePracticeBoard.vue'
import { systemApi } from '@/api/system'
import Prism from 'prismjs'
import 'prismjs/components/prism-clike'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-sql'
import 'prismjs/components/prism-json'

const router = useRouter()

// =========================================
// 交互终端演示 Tab 切换
// =========================================
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

// =========================================
// 首屏副标题打字机效果 (Typewriter Effect)
// =========================================
const HERO_SUB_LINE_1 = '无缝集成算法 OJ、数据库 OJ、前端 AI 智能评测及标准客观题系统。'
const HERO_SUB_LINE_2 = '基于高可用强隔离沙箱与独立临时库，提供确定性、防作弊的全真技术考核。'

const typedLine1 = ref('')
const typedLine2 = ref('')
const activeTypingLine = ref<1 | 2>(1)
let typewriterTimer: ReturnType<typeof setTimeout> | null = null

function startTypewriter() {
  if (typewriterTimer) clearTimeout(typewriterTimer)
  typedLine1.value = ''
  typedLine2.value = ''
  activeTypingLine.value = 1

  let i = 0
  let j = 0

  const stepLine1 = () => {
    if (i < HERO_SUB_LINE_1.length) {
      i += 1
      typedLine1.value = HERO_SUB_LINE_1.slice(0, i)
      const ch = HERO_SUB_LINE_1[i - 1]
      const delay = ch === '、' || ch === '。' ? 110 : 42
      typewriterTimer = setTimeout(stepLine1, delay)
    } else {
      activeTypingLine.value = 2
      typewriterTimer = setTimeout(stepLine2, 240)
    }
  }

  const stepLine2 = () => {
    if (j < HERO_SUB_LINE_2.length) {
      j += 1
      typedLine2.value = HERO_SUB_LINE_2.slice(0, j)
      const ch = HERO_SUB_LINE_2[j - 1]
      const delay = ch === '，' || ch === '、' || ch === '。' ? 110 : 42
      typewriterTimer = setTimeout(stepLine2, delay)
    } else {
      // 完整打出后保持展示 6 秒，再循环重播打字动效
      typewriterTimer = setTimeout(startTypewriter, 6000)
    }
  }

  typewriterTimer = setTimeout(stepLine1, 200)
}

onBeforeUnmount(() => {
  if (glitchTimer) clearTimeout(glitchTimer)
  if (typewriterTimer) clearTimeout(typewriterTimer)
  if (codeTypeTimer) clearTimeout(codeTypeTimer)
  if (terminalObserver) {
    terminalObserver.disconnect()
    terminalObserver = null
  }
})

// =========================================
// 交互终端代码演示：惰性加载与打字机特效
// =========================================
const typedCode = ref('')
const isTyping = ref(false)
const isVerdictReady = ref(false)
const testingProgress = ref(1)
const hasTriggeredScroll = ref(false)
const terminalSectionRef = ref<HTMLElement | null>(null)
const terminalBodyRef = ref<HTMLElement | null>(null)
let codeTypeTimer: ReturnType<typeof setTimeout> | null = null
let terminalObserver: IntersectionObserver | null = null

const totalLines = computed(() => {
  const code = hasTriggeredScroll.value
    ? (typedCode.value || ' ')
    : (demos[activeTab.value]?.code || ' ')
  return Math.max(1, code.split('\n').length)
})

// 针对首页沙箱评测终端演示代码的高亮（支持 Java / SQL / JSON，在打字过程中实时渲染）
const highlightedCode = computed(() => {
  const current = demos[activeTab.value]
  if (!current) return ''
  const text = hasTriggeredScroll.value ? typedCode.value : ''
  if (!text) return ''

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
    return Prism.highlight(text, grammar, langId)
  } catch {
    return text
  }
})

function startCodeTyping() {
  if (codeTypeTimer) {
    clearTimeout(codeTypeTimer)
    codeTypeTimer = null
  }
  const fullCode = demos[activeTab.value]?.code || ''
  if (!fullCode) return

  typedCode.value = ''
  isTyping.value = true
  isVerdictReady.value = false
  testingProgress.value = 1

  let index = 0
  const totalLen = fullCode.length

  const typeNext = () => {
    if (index < totalLen) {
      // 真实编码打字节奏：缩进空白一次性快速跃进
      if (
        fullCode.slice(index, index + 4) === '    ' ||
        fullCode.slice(index, index + 2) === '  '
      ) {
        index += fullCode.slice(index, index + 4) === '    ' ? 4 : 2
      } else if (fullCode[index] === '\t') {
        index += 1
      } else {
        const step = Math.min(totalLen - index, Math.random() > 0.45 ? 2 : 1)
        index += step
      }

      typedCode.value = fullCode.slice(0, index)

      // 动态推进测试点进度 (1 -> 10)
      const ratio = index / totalLen
      testingProgress.value = Math.min(10, Math.max(1, Math.ceil(ratio * 10)))

      // 终端视口随光标自动滚动
      nextTick(() => {
        if (terminalBodyRef.value) {
          terminalBodyRef.value.scrollTop = terminalBodyRef.value.scrollHeight
        }
      })

      const lastChar = fullCode[index - 1]
      let delay = 10 + Math.random() * 12

      if (lastChar === '\n') {
        delay = 55 + Math.random() * 30
      } else if (lastChar === ';' || lastChar === '{' || lastChar === '}') {
        delay = 35 + Math.random() * 20
      }

      codeTypeTimer = setTimeout(typeNext, delay)
    } else {
      typedCode.value = fullCode
      // 代码敲完，模拟沙箱 280ms 综合判定延迟，随后 ACCEPTED 判定落地
      codeTypeTimer = setTimeout(() => {
        isTyping.value = false
        isVerdictReady.value = true
        testingProgress.value = 10
      }, 280)
    }
  }

  codeTypeTimer = setTimeout(typeNext, 160)
}

function replayTyping() {
  startCodeTyping()
}

watch(activeTab, () => {
  if (hasTriggeredScroll.value) {
    startCodeTyping()
  }
})

// 动态拉取后端每日更新的随机 Java 算法模板（秒级响应，离线/超时高可用保底）
onMounted(async () => {
  startTypewriter()

  // 惰性加载：当且仅当用户滚动翻到沙箱演示区时才启动代码打字动效
  if (typeof IntersectionObserver !== 'undefined' && terminalSectionRef.value) {
    terminalObserver = new IntersectionObserver(
      (entries) => {
        const entry = entries[0]
        if (entry && entry.isIntersecting) {
          if (!hasTriggeredScroll.value) {
            hasTriggeredScroll.value = true
            startCodeTyping()
          }
        }
      },
      { threshold: 0.2 }
    )
    terminalObserver.observe(terminalSectionRef.value)
  } else {
    hasTriggeredScroll.value = true
    startCodeTyping()
  }

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
          <!-- 隐形占位层：锁定段落高度，防止打字换行过程中下方按钮跳动 -->
          <span class="hero-sub-ghost" aria-hidden="true">
            {{ HERO_SUB_LINE_1 }}<br />
            {{ HERO_SUB_LINE_2 }}
          </span>
          <!-- 实时打字机渲染层 -->
          <span class="hero-sub-typed">
            <span>{{ typedLine1 }}</span><span v-if="activeTypingLine === 1" class="typewriter-cursor"></span>
            <br v-if="activeTypingLine === 2 || typedLine2.length > 0" />
            <span>{{ typedLine2 }}</span><span v-if="activeTypingLine === 2" class="typewriter-cursor"></span>
          </span>
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

    <HomePracticeBoard />

    <!-- 交互式技术体验区：实时沙箱评测终端（支持惰性加载打字动效与真实沙箱评测联动） -->
    <section ref="terminalSectionRef" class="demo-section container">
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
            <span class="live-dot" :class="{ 'is-running': isTyping }"></span>
            <span>JUDGE RUNNER #01</span>
            <span v-if="isTyping" class="badge-running-pill mono">RUNNING</span>
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
            <button
              type="button"
              class="terminal-tab btn-replay"
              title="重新播放代码打字与沙箱判定动效"
              @click="replayTyping"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" class="replay-icon">
                <path fill-rule="evenodd" d="M8 3a5 5 0 1 0 4.546 2.914.5.5 0 0 1 .908-.417A6 6 0 1 1 8 2v1z"/>
                <path d="M8 4.466V.534a.25.25 0 0 1 .41-.192l2.36 1.966c.12.1.12.284 0 .384L8.41 4.658A.25.25 0 0 1 8 4.466z"/>
              </svg>
              <span>重播</span>
            </button>
          </div>
        </div>

        <div class="terminal-meta-bar">
          <span class="file-name">{{ demos[activeTab].filename }}</span>
          <span class="lang-tag">{{ demos[activeTab].langBadge }}</span>
        </div>

        <div ref="terminalBodyRef" class="terminal-body">
          <div class="terminal-code-wrapper">
            <div class="line-numbers mono" aria-hidden="true">
              <span v-for="n in totalLines" :key="n">{{ n }}</span>
            </div>
            <pre class="code-view"><template v-if="!hasTriggeredScroll"><span class="waiting-hint mono">// 终端待命中 · 滚动进入视口自动启动沙箱...</span></template><template v-else>
              <!-- eslint-disable-next-line vue/no-v-html -- highlightedCode is Prism output from static local demo strings. -->
              <code v-html="highlightedCode"></code><span class="terminal-cursor" :class="{ typing: isTyping }"></span>
            </template></pre>
          </div>
        </div>

        <div class="terminal-hud">
          <div class="hud-status-row">
            <template v-if="!hasTriggeredScroll">
              <span class="verdict-tag status-idle mono">
                ● STANDBY 待命
              </span>
              <span class="verdict-detail">沙箱环境就绪 · 滚动至此加载全流程评测</span>
            </template>
            <template v-else-if="!isVerdictReady">
              <span class="verdict-tag status-running mono">
                <span class="pulse-spin"></span>
                <span>SANDBOX EXECUTING...</span>
              </span>
              <span class="verdict-detail mono">
                Docker Cgroups 隔离测试中 · 对拍用例 {{ testingProgress }} / 10
              </span>
            </template>
            <template v-else>
              <span class="verdict-tag" :class="[demos[activeTab].verdict.statusClass, 'just-arrived']">
                ● {{ demos[activeTab].verdict.status }}
              </span>
              <span class="verdict-detail">{{ demos[activeTab].verdict.details }}</span>
            </template>
          </div>
          <div class="hud-metric-row">
            <div
              v-for="m in demos[activeTab].verdict.metrics"
              :key="m.label"
              class="metric-item"
            >
              <div class="metric-title">{{ m.label }}</div>
              <div class="metric-data" :class="{ 'is-loading': !isVerdictReady }">
                <span v-if="isVerdictReady">{{ m.value }}</span>
                <span v-else-if="!hasTriggeredScroll" class="metric-idle mono">待测</span>
                <span v-else class="metric-skeleton mono">
                  <span v-if="m.label.includes('耗时') || m.label.includes('执行')">{{ (testingProgress * 1.5).toFixed(0) }} ms...</span>
                  <span v-else-if="m.label.includes('内存')">cgroups 采样</span>
                  <span v-else-if="m.label.includes('测试点') || m.label.includes('数据') || m.label.includes('通过')">{{ testingProgress }} / 10 判定</span>
                  <span v-else>实时计算中...</span>
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 底部行动区 CTA（对齐项目浅色直角工业风） -->
    <section class="cta-section container">
      <div class="cta-card">
        <div class="cta-left">
          <div class="cta-eyebrow mono">// READY FOR ASSESSMENT</div>
          <h2 class="cta-title">准备好开启专业级技术测评了吗？</h2>
          <p class="cta-desc">
            无论是日常算法与 SQL 训练，还是举办随堂课练与正规机考，KECE 现已全面就绪。
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
  padding-bottom: 84px;
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
  padding-bottom: 40px;
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
  position: relative;
  font-size: 15.5px;
  line-height: 1.65;
  color: var(--ink-secondary);
  margin: 0 0 32px;
  font-weight: 400;
}

.hero-sub-ghost {
  display: block;
  visibility: hidden;
  pointer-events: none;
  user-select: none;
}

.hero-sub-typed {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
}

.typewriter-cursor {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  background-color: var(--ink);
  vertical-align: text-bottom;
  margin-left: 2px;
  animation: typewriter-blink 0.85s step-end infinite;
}

@keyframes typewriter-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
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
   3. 交互式沙箱评测终端（保留全宽结构与语法高亮）
   ========================================= */
.demo-section {
  padding-top: 12px;
  padding-bottom: 68px;
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

.terminal-code-wrapper {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.line-numbers {
  display: flex;
  flex-direction: column;
  user-select: none;
  color: #4b4b52;
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
  background: #3b82f6;
  vertical-align: -2.5px;
  margin-left: 2px;
  animation: cursorBlink 0.85s infinite;
}

.terminal-cursor.typing {
  background: #60a5fa;
  animation: none;
  opacity: 1;
}

@keyframes cursorBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.waiting-hint {
  color: #636674;
  font-style: italic;
}

.badge-running-pill {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  background: rgba(59, 130, 246, 0.2);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.4);
  letter-spacing: 0.5px;
  animation: pulseRunning 1.2s infinite;
}

@keyframes pulseRunning {
  0%, 100% { opacity: 0.9; }
  50% { opacity: 0.45; }
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
  background: #3b82f6;
  box-shadow: 0 0 10px rgba(59, 130, 246, 0.9);
}

.verdict-tag.status-idle {
  background: rgba(140, 140, 144, 0.14);
  color: #9da0a8;
  border: 1px solid rgba(140, 140, 144, 0.28);
}

.verdict-tag.status-running {
  background: rgba(59, 130, 246, 0.16);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.38);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pulse-spin {
  display: inline-block;
  width: 8px;
  height: 8px;
  border: 1.5px solid rgba(96, 165, 250, 0.3);
  border-top-color: #60a5fa;
  border-radius: 50%;
  animation: spinVerdict 0.6s linear infinite;
}

@keyframes spinVerdict {
  to { transform: rotate(360deg); }
}

.verdict-tag.just-arrived {
  animation: verdictPopIn 0.45s cubic-bezier(0.2, 0.8, 0.2, 1);
}

@keyframes verdictPopIn {
  0% { transform: scale(1.08); filter: brightness(1.5); }
  100% { transform: scale(1); filter: brightness(1); }
}

.metric-idle {
  color: #55555c;
  font-size: 12px;
}

.metric-skeleton {
  color: #60a5fa;
  font-size: 12px;
  animation: skeletonPulse 1s infinite alternate;
}

@keyframes skeletonPulse {
  0% { opacity: 0.6; }
  100% { opacity: 1; }
}

.terminal-body {
  padding: 20px;
  background: #111112;
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
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
   4. 底部 CTA 行动区（对齐项目浅色直角卡片风格）
   ========================================= */
.cta-section {
  padding-top: 8px;
}

.cta-card {
  background: var(--surface);
  border-radius: 0;
  border: 1px solid var(--line);
  padding: 40px 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  box-shadow: var(--shadow-sm);
}

.cta-eyebrow {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.8px;
  color: var(--ink-tertiary);
  margin-bottom: 8px;
}

.cta-title {
  font-size: 25px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 8px;
  letter-spacing: -0.4px;
}

.cta-desc {
  font-size: 14.5px;
  color: var(--ink-secondary);
  margin: 0;
  max-width: 520px;
  line-height: 1.6;
}

.cta-right {
  display: flex;
  gap: 14px;
  flex-shrink: 0;
}

.cta-card .btn-ghost {
  background: #ffffff;
}

/* =========================================
   5. 响应式适配
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

@media (max-width: 640px) {
  .hero-title {
    font-size: 34px;
  }
  .hud-metric-row {
    grid-template-columns: 1fr;
  }
  .terminal-tabs {
    display: none;
  }
}
</style>
