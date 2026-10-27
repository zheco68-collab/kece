<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { submissionApi } from '@/api/submission'
import { questionApi } from '@/api/question'
import type { Question, Submission, SubmissionStatus, TestCaseReport } from '@/types'
import { STATUS_ABBR, formatMemory, statusTone } from '@/utils/submission'

const route = useRoute()
const router = useRouter()

const submissionId = computed(() => {
  return Number(route.params.submissionId || route.params.id)
})

const questionId = computed(() => {
  if (route.params.id && route.params.submissionId) {
    return Number(route.params.id)
  }
  if (route.query.questionId) {
    return Number(route.query.questionId)
  }
  return 0
})

const submission = ref<Submission | null>(null)
const question = ref<Question | null>(null)
const loading = ref(true)
const error = ref('')
let pollTimer: number | null = null

const TERMINAL_STATUSES: SubmissionStatus[] = [
  'ACCEPTED',
  'WRONG_ANSWER',
  'TIME_LIMIT_EXCEEDED',
  'MEMORY_LIMIT_EXCEEDED',
  'RUNTIME_ERROR',
  'COMPILE_ERROR',
  'SYSTEM_ERROR',
]

function isTerminal(status: SubmissionStatus): boolean {
  return TERMINAL_STATUSES.includes(status)
}

const isPolling = computed(() => {
  return submission.value != null && !isTerminal(submission.value.status)
})

const progressPercent = computed(() => {
  const p = submission.value?.progress
  if (!p || p.totalCases === 0) return 0
  return Math.round((p.finishedCases / p.totalCases) * 100)
})

const reports = computed<TestCaseReport[]>(() => {
  return submission.value?.result?.testCasesReport ?? []
})

function statusClass(status: SubmissionStatus): string {
  if (status === 'ACCEPTED') return 'ok'
  if (isTerminal(status)) return 'wrong'
  return 'running'
}

function startPolling(subId: number) {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    try {
      const s = await submissionApi.get(subId)
      submission.value = s
      if (isTerminal(s.status)) {
        stopPolling()
      }
    } catch {
      stopPolling()
    }
  }, 1000)
}

function stopPolling() {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const s = await submissionApi.get(submissionId.value)
    submission.value = s

    // 如果仍在运行中，拉起定时轮询
    if (!isTerminal(s.status)) {
      startPolling(submissionId.value)
    }

    // 若有对应题目 id，拉取题目标题信息
    if (questionId.value > 0) {
      try {
        question.value = await questionApi.detail(questionId.value)
      } catch {
        /* 忽略题面拉取失败 */
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '获取评测结果失败'
  } finally {
    loading.value = false
  }
}

function reEdit() {
  if (questionId.value > 0) {
    const query = fromSetId.value
      ? { setId: fromSetId.value, itemId: String(route.query.itemId || '') }
      : undefined
    router.push({ path: `/questions/${questionId.value}/submit`, query })
  } else {
    router.push(fromSetId.value ? `/problem-sets/${fromSetId.value}` : '/questions')
  }
}

const fromSetId = computed(() => {
  const s = route.query.setId
  return s ? String(s) : null
})

function backToQuestion() {
  if (questionId.value > 0) {
    const query = fromSetId.value
      ? { setId: fromSetId.value, itemId: String(route.query.itemId || '') }
      : undefined
    router.push({ path: `/questions/${questionId.value}`, query })
  } else {
    router.push(fromSetId.value ? `/problem-sets/${fromSetId.value}` : '/questions')
  }
}

onMounted(() => {
  loadData()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <div class="result-page container">
    <!-- 加载与异常状态 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在读取安全沙箱评测报告…</p>
    </div>

    <div v-else-if="error" class="state-card card">
      <div class="error-title">获取结果失败</div>
      <p class="error-msg">{{ error }}</p>
      <button class="btn btn-outline" @click="router.push('/submissions')">返回提交记录列表</button>
    </div>

    <!-- 评测结果独立页面 -->
    <template v-else-if="submission">
      <!-- 顶部综合概览卡片 -->
      <header class="overview-card card">
        <div class="overview-top">
          <div class="overview-breadcrumbs">
            <button class="btn-back" @click="backToQuestion">
              <svg class="back-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="19" y1="12" x2="5" y2="12" />
                <polyline points="12 19 5 12 12 5" />
              </svg>
              <span>{{ question ? '返回题目描述' : '返回题库' }}</span>
            </button>
            <span class="sep">/</span>
            <span class="sub-num">提交 #{{ submission.submissionId }}</span>
            <span v-if="question" class="q-anchor" @click="backToQuestion">
              · #{{ question.id }} {{ question.title }}
            </span>
          </div>

          <!-- 综合状态徽章 -->
          <div class="status-badge-wrap">
            <div class="status-badge" :class="statusClass(submission.status)">
              <span class="status-dot"></span>
              <span class="status-label">{{ STATUS_ABBR[submission.status] ?? submission.status }}</span>
            </div>
          </div>
        </div>

        <!-- 评测核心指标数据行 -->
        <div class="metrics-row">
          <div class="metric-cell">
            <span class="metric-k">综合得分</span>
            <span class="metric-v score-v">
              {{ submission.result?.score != null ? submission.result.score : (submission.status === 'ACCEPTED' ? 100 : '-') }}
            </span>
          </div>
          <div class="metric-cell">
            <span class="metric-k">运行总耗时</span>
            <span class="metric-v">
              {{ submission.result?.timeUsedMs != null ? submission.result.timeUsedMs + ' ms' : '-' }}
            </span>
          </div>
          <div class="metric-cell">
            <span class="metric-k">峰值内存空间</span>
            <span class="metric-v">
              {{ submission.result?.memoryUsedKb != null ? formatMemory(submission.result.memoryUsedKb) : '-' }}
            </span>
          </div>
          <div class="metric-cell">
            <span class="metric-k">评测节点进度</span>
            <span class="metric-v">
              {{ reports.length ? `${reports.length} 个测试用例已结算` : (isPolling ? '沙箱执行中…' : '就绪') }}
            </span>
          </div>
        </div>
      </header>

      <!-- 沙箱运行中进度条 -->
      <section v-if="submission.progress && isPolling" class="running-card card">
        <div class="running-head">
          <div class="running-title">
            <span class="pulse-indicator"></span>
            <span>判题机正在通过隔离沙箱运行测试集…</span>
          </div>
          <span class="running-num">
            {{ submission.progress.finishedCases }} / {{ submission.progress.totalCases }} ({{ progressPercent }}%)
          </span>
        </div>
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: progressPercent + '%' }"></div>
        </div>
      </section>

      <!-- 编译错误报错面板 -->
      <section v-if="submission.result?.compileErrorLog" class="ce-card card">
        <div class="ce-head">
          <span class="ce-title">编译器日志 (Compile Error Log)</span>
        </div>
        <pre class="ce-content">{{ submission.result.compileErrorLog }}</pre>
      </section>

      <!-- 核心主体：150×150px 测试点状态彩色方块（整版宽敞平铺展示，严格保持原设计不动） -->
      <section v-if="reports.length" class="nodes-section card">
        <div class="nodes-head">
          <div class="nodes-title-group">
            <h2 class="nodes-title">测试点状态详情</h2>
            <span class="nodes-subtitle">共 {{ reports.length }} 个独立沙箱评测节点</span>
          </div>
          <div class="legend-group">
            <span class="legend-item"><span class="legend-dot ac"></span>AC 通过</span>
            <span class="legend-item"><span class="legend-dot wa"></span>WA 答案错误</span>
            <span class="legend-item"><span class="legend-dot tle"></span>TLE 超时</span>
            <span class="legend-item"><span class="legend-dot mle"></span>MLE 超内存</span>
            <span class="legend-item"><span class="legend-dot re"></span>RE 运行时错误</span>
          </div>
        </div>

        <div class="nodes-content">
          <!-- -------------------------------------------------------------
               用户的核心标志性设计：150×150px 测试点状态彩色方块（严格保持不动）
               ------------------------------------------------------------- -->
          <div class="node-statuses">
            <div
              v-for="(c, i) in reports"
              :key="c.caseId"
              class="node-status"
              :class="statusTone(c.status as SubmissionStatus)"
            >
              <span class="node-no">#{{ i + 1 }}</span>
              <span class="node-abbr">{{ STATUS_ABBR[c.status as SubmissionStatus] ?? c.status }}</span>
              <span class="node-meta">
                {{ c.timeMs != null ? c.timeMs + 'ms' : '-' }} · {{ formatMemory(c.memoryKb) }}
              </span>
            </div>
          </div>
        </div>
      </section>

      <!-- 底部操作导航条 -->
      <footer class="result-actions-bar card">
        <div class="actions-left">
          <button class="btn btn-primary" @click="backToQuestion">
            返回题目描述
          </button>
          <RouterLink to="/submissions" class="btn btn-primary">
            查看所有历史提交
          </RouterLink>
        </div>

        <div class="actions-right">
          <button class="btn btn-primary btn-re-edit" @click="reEdit">
            <svg class="edit-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 20h9" />
              <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
            </svg>
            <span>修改代码并重新提交</span>
          </button>
        </div>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.result-page {
  padding-top: 24px;
  padding-bottom: 64px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 状态加载卡片 */
.state-card {
  padding: 64px 32px;
  text-align: center;
  border-radius: 0;
}
.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--line);
  border-top-color: var(--ink);
  animation: spin 0.8s linear infinite;
  margin: 0 auto 16px;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.error-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--danger);
  margin-bottom: 8px;
}
.error-msg {
  color: var(--ink-secondary);
  margin-bottom: 24px;
}

/* 概览卡片 */
.overview-card {
  padding: 24px 28px;
  border-radius: 0;
  background: var(--surface);
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.overview-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.overview-breadcrumbs {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 500;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-back:hover {
  background: var(--surface);
  color: var(--ink);
  border-color: var(--ink);
}
.back-icon {
  width: 14px;
  height: 14px;
}
.sep {
  color: var(--ink-tertiary);
}
.sub-num {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 700;
  font-size: 18px;
  color: var(--ink);
}
.q-anchor {
  font-size: 15px;
  color: var(--ink-secondary);
  cursor: pointer;
  transition: color 0.15s ease;
}
.q-anchor:hover {
  color: var(--ink);
  text-decoration: underline;
}

/* 状态徽章 */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  font-size: 15px;
  font-weight: 800;
  border: 1px solid var(--line);
  border-radius: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 0;
  background: currentColor;
}
.status-badge.ok {
  background: #e8f5e9;
  color: var(--success);
  border-color: var(--success);
}
.status-badge.wrong {
  background: #ffebee;
  color: var(--danger);
  border-color: var(--danger);
}
.status-badge.running {
  background: #e1f5fe;
  color: #0288d1;
  border-color: #0288d1;
}

/* 指标数据行 */
.metrics-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.metric-k {
  font-size: 11px;
  color: var(--ink-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.metric-v {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}
.score-v {
  font-size: 20px;
}

/* 轮询卡片 */
.running-card {
  padding: 18px 24px;
  border-radius: 0;
  background: var(--surface);
  border-left: 4px solid #0288d1;
}
.running-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.running-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.pulse-indicator {
  width: 8px;
  height: 8px;
  background: #0288d1;
  border-radius: 0;
  animation: pulse 1s infinite alternate;
}
@keyframes pulse {
  from { opacity: 0.3; transform: scale(0.9); }
  to { opacity: 1; transform: scale(1.1); }
}
.running-num {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 700;
  font-size: 13px;
  color: var(--ink-secondary);
}
.progress-bar-track {
  height: 8px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 0;
  overflow: hidden;
}
.progress-bar-fill {
  height: 100%;
  background: var(--ink);
  border-radius: 0;
  transition: width 0.25s ease;
}

/* 编译错误 */
.ce-card {
  border-radius: 0;
  border: 1px solid var(--danger);
  overflow: hidden;
}
.ce-head {
  padding: 10px 16px;
  background: #ffebee;
  color: var(--danger);
  font-weight: 700;
  font-size: 13px;
  border-bottom: 1px solid var(--danger);
}
.ce-content {
  margin: 0;
  padding: 16px;
  background: #fff;
  color: var(--danger);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

/* -------------------------------------------------------------
   核心：150×150px 测试点大方块独立展示区（整版呈现）
   ------------------------------------------------------------- */
.nodes-section {
  border-radius: 0;
  background: var(--surface);
  overflow: hidden;
}
.nodes-head {
  padding: 16px 24px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.nodes-title-group {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.nodes-title {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: var(--ink);
}
.nodes-subtitle {
  font-size: 12px;
  color: var(--ink-tertiary);
}
.legend-group {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-secondary);
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 0;
}
.legend-dot.ac { background: var(--success); }
.legend-dot.wa { background: var(--danger); }
.legend-dot.tle { background: #000; }
.legend-dot.mle { background: #f57c00; }
.legend-dot.re { background: #9c27b0; }

.nodes-content {
  padding: 28px 24px 36px;
}

/* 用户的核心标志性设计：150×150px 测试点状态彩色方块（严格保持不动） */
.node-statuses {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.node-status {
  position: relative;
  width: 150px;
  height: 150px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  color: #fff;
  border-radius: 0;
  box-sizing: border-box;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.node-status.ac {
  background: var(--success);
}
.node-status.wa {
  background: var(--danger);
}
.node-status.tle {
  background: #000;
}
.node-status.mle {
  background: #f57c00;
}
.node-status.re {
  background: #9c27b0;
}
.node-status.ce,
.node-status.se {
  background: #607d8b;
}
.node-no {
  position: absolute;
  top: 8px;
  left: 10px;
  font-size: 12px;
  font-weight: 400;
  opacity: 0.85;
  line-height: 1;
}
.node-abbr {
  font-size: 22px;
  font-weight: 700;
  line-height: 1;
}
.node-meta {
  font-size: 11px;
  font-weight: 400;
  opacity: 0.9;
  line-height: 1.3;
}

/* 底部操作条 */
.result-actions-bar {
  padding: 18px 28px;
  border-radius: 0;
  background: var(--surface);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.actions-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.actions-left .btn {
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 700;
  border-radius: 0;
  cursor: pointer;
}
.actions-right {
  display: flex;
  align-items: center;
}
.btn-re-edit {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  font-size: 14px;
  font-weight: 700;
  border-radius: 0;
  cursor: pointer;
}
.edit-icon {
  width: 15px;
  height: 15px;
}
</style>

