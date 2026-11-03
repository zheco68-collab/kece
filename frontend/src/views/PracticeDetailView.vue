<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { practiceApi } from '@/api/practice'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import { useUserStore } from '@/stores/user'
import { copyToClipboard } from '@/utils/clipboard'
import { renderMarkdown } from '@/utils/markdown'
import { LANGUAGE_LABELS } from '@/utils/submission'
import type {
  PracticeDetail,
  PracticeObjectiveQuestion,
  PracticePracticalQuestion,
  PracticeScoreboard,
} from '@/types/practice'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { success, error: toastError } = useToast()
const { confirm } = useConfirm()

const code = computed(() => String(route.params.code).toUpperCase())
const activeTab = ref<'questions' | 'scoreboard'>('questions')
const loading = ref(true)
const scoreboardLoading = ref(false)
const submittingPractice = ref(false)

const detail = ref<PracticeDetail | null>(null)
const scoreboard = ref<PracticeScoreboard | null>(null)

// 章节折叠状态
const collapsedSections = ref<Record<string, boolean>>({
  objective: false,
  db: false,
  algo: false,
})

// 成绩榜脱敏开关
const isAnonymous = ref(false)

// 倒计时与状态轮询
let countdownTimer: number | null = null
let scoreboardPollTimer: number | null = null
let pendingJudgePollTimer: number | null = null
const remainingSec = ref<number | null>(null)

// 客观题内联抽屉状态
const objectiveDrawerVisible = ref(false)
const currentObjectiveQuestion = ref<PracticeObjectiveQuestion | null>(null)
const objectiveAnswers = ref<Record<number, string>>({})
const multiDraft = ref<string[]>([])
const fillDraft = ref('')
const savingObjective = ref(false)

const isTeacherPreview = computed(() =>
  Boolean(detail.value?.canManage && !detail.value?.myJoined),
)

const isPracticeLocked = computed(() =>
  Boolean(detail.value?.status === 'ENDED' || detail.value?.mySubmitted),
)

const isAnswerReadonly = computed(() =>
  Boolean(!detail.value || isPracticeLocked.value || isTeacherPreview.value),
)

const formattedRemaining = computed(() => {
  if (detail.value?.status === 'ENDED') {
    return '00:00 (已截止)'
  }
  if (detail.value?.durationMinutes === 0) {
    return '不限时'
  }
  if (remainingSec.value !== null && remainingSec.value <= 0) {
    return '00:00 (已截止)'
  }
  if (remainingSec.value === null) return '--:--'
  const m = Math.floor(remainingSec.value / 60)
  const s = remainingSec.value % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})

// 章节分类
const objectiveQuestions = computed(() => detail.value?.objectiveQuestions ?? [])
const dbQuestions = computed(
  () => detail.value?.practicalQuestions.filter((q) => q.questionType === 'DB') ?? [],
)
const algoQuestions = computed(
  () => detail.value?.practicalQuestions.filter((q) => q.questionType === 'ALGO') ?? [],
)

const totalQuestionsCount = computed(() => {
  return objectiveQuestions.value.length + dbQuestions.value.length + algoQuestions.value.length
})

const totalFullScore = computed(() => {
  const o = objectiveQuestions.value.reduce((s, q) => s + (q.score || 0), 0)
  const d = dbQuestions.value.reduce((s, q) => s + (q.score || 0), 0)
  const a = algoQuestions.value.reduce((s, q) => s + (q.score || 0), 0)
  return o + d + a
})

// 动态连续章节编号（避免缺某类题型时从 02/03 跳号）
const objectiveSecIdx = computed(() => '01')
const dbSecIdx = computed(() =>
  String((objectiveQuestions.value.length > 0 ? 1 : 0) + 1).padStart(2, '0'),
)
const algoSecIdx = computed(() =>
  String(
    (objectiveQuestions.value.length > 0 ? 1 : 0) +
      (dbQuestions.value.length > 0 ? 1 : 0) +
      1,
  ).padStart(2, '0'),
)

// 各章节统计
const objectiveStat = computed(() => {
  const list = objectiveQuestions.value
  const passed = list.filter((q) => q.isCorrect).length
  const earned = list.reduce((sum, q) => sum + (q.userScore || 0), 0)
  const total = list.reduce((sum, q) => sum + (q.score || 0), 0)
  return { passed, totalCount: list.length, earned, total }
})

const dbStat = computed(() => {
  const list = dbQuestions.value
  const passed = list.filter((q) => q.userStatus === 'ACCEPTED').length
  const earned = list.reduce((sum, q) => sum + (q.userScore || 0), 0)
  const total = list.reduce((sum, q) => sum + (q.score || 0), 0)
  return { passed, totalCount: list.length, earned, total }
})

const algoStat = computed(() => {
  const list = algoQuestions.value
  const passed = list.filter((q) => q.userStatus === 'ACCEPTED').length
  const earned = list.reduce((sum, q) => sum + (q.userScore || 0), 0)
  const total = list.reduce((sum, q) => sum + (q.score || 0), 0)
  return { passed, totalCount: list.length, earned, total }
})

const currentObjectiveStemHtml = computed(() =>
  renderMarkdown(currentObjectiveQuestion.value?.content ?? ''),
)

function plainStemPreview(raw: string): string {
  return (raw || '')
    .replace(/```[\s\S]*?```/g, ' [代码片段] ')
    .replace(/[#>*`~]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function stripOptionPrefix(opt: string): string {
  return (opt || '').replace(/^[A-Za-z][.、)）:：]\s*/, '').trim()
}

function normalizeJudgeValue(val?: string | null): '对' | '错' | null {
  if (!val) return null
  const v = val.trim().toUpperCase()
  if (['对', 'TRUE', 'T', '1', '正确', 'YES', '√', '✓'].includes(v)) return '对'
  if (['错', 'FALSE', 'F', '0', '错误', 'NO', '×', '✕', '✗'].includes(v)) return '错'
  return null
}

function getObjectivePill(type?: string) {
  const t = (type || 'SINGLE').toUpperCase()
  if (t === 'MULTIPLE') return { label: '多选题', cls: 'pill-multiple' }
  if (t === 'JUDGE') return { label: '判断题', cls: 'pill-judge' }
  if (t === 'FILL') return { label: '填空题', cls: 'pill-fill' }
  return { label: '单选题', cls: 'pill-single' }
}

function getDbPill(q: PracticePracticalQuestion) {
  const t = (q.difficulty || 'SELECT').toUpperCase()
  if (t === 'DML') return { label: 'DML 操作', cls: 'pill-dml' }
  return { label: 'SELECT 查询', cls: 'pill-select' }
}

function getAlgoPill(q: PracticePracticalQuestion) {
  const d = (q.difficulty || 'MEDIUM').toUpperCase()
  if (d === 'EASY') return { label: '简单', cls: 'pill-easy' }
  if (d === 'HARD') return { label: '困难', cls: 'pill-hard' }
  return { label: '中等', cls: 'pill-medium' }
}

function getAlgoLangLabels(q: PracticePracticalQuestion): string[] {
  const langs = q.languages && q.languages.length > 0 ? q.languages : ['cpp', 'java', 'python']
  return langs.map((l) => LANGUAGE_LABELS[l] ?? l)
}

function isPendingStatus(st?: string): boolean {
  return st === 'PENDING' || st === 'COMPILING' || st === 'JUDGING'
}

const STATUS_SHORT_MAP: Record<string, string> = {
  WRONG_ANSWER: 'WA',
  TIME_LIMIT_EXCEEDED: 'TLE',
  MEMORY_LIMIT_EXCEEDED: 'MLE',
  RUNTIME_ERROR: 'RE',
  COMPILE_ERROR: 'CE',
  SYSTEM_ERROR: 'SE',
}

function getPracticalBadge(q: PracticePracticalQuestion): { cls: string; text: string } {
  const st = q.userStatus || 'UNATTEMPTED'
  if (st === 'ACCEPTED') {
    return { cls: 'ac', text: `✓ ${q.userScore}/${q.score} AC` }
  }
  if (isPendingStatus(st)) {
    return { cls: 'running', text: '⟳ 评测中…' }
  }
  if ((q.userScore ?? 0) > 0) {
    return { cls: 'partial', text: `! ${q.userScore}/${q.score} PARTIAL` }
  }
  if (st === 'UNATTEMPTED') {
    return { cls: 'none', text: '未作答' }
  }
  const abbr = STATUS_SHORT_MAP[st] || st
  return { cls: 'wa', text: `✗ 0/${q.score} ${abbr}` }
}

function getPracticalCtaText(q: PracticePracticalQuestion): string {
  if (isPracticeLocked.value && q.submissionId) {
    return '评测详情'
  }
  if (q.userStatus === 'ACCEPTED') {
    return q.questionType === 'DB' ? '复查 SQL' : '查看提交'
  }
  if (q.userStatus && q.userStatus !== 'UNATTEMPTED') {
    return q.questionType === 'DB' ? '继续调试' : '继续调试'
  }
  return q.questionType === 'DB' ? '进入实操' : '进入编程'
}

function syncPendingJudgePolling() {
  const hasPending = (detail.value?.practicalQuestions ?? []).some((q) =>
    isPendingStatus(q.userStatus),
  )
  if (hasPending && activeTab.value === 'questions') {
    if (!pendingJudgePollTimer) {
      pendingJudgePollTimer = window.setInterval(() => {
        fetchDetail(true)
      }, 2500)
    }
  } else if (pendingJudgePollTimer) {
    clearInterval(pendingJudgePollTimer)
    pendingJudgePollTimer = null
  }
}

async function fetchDetail(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await practiceApi.detail(code.value)
    detail.value = res
    if (res.status === 'ENDED') {
      remainingSec.value = 0
    } else if (res.durationMinutes === 0) {
      remainingSec.value = null
    } else if (res.remainingSeconds !== undefined && res.remainingSeconds !== null) {
      remainingSec.value = res.remainingSeconds
    }
    if (res.objectiveQuestions) {
      for (const q of res.objectiveQuestions) {
        if (q.userAnswer != null && q.userAnswer !== '') {
          objectiveAnswers.value[q.practiceQuestionId] = q.userAnswer
        }
      }
      // 如果抽屉正打开，同步当前题目对象
      if (currentObjectiveQuestion.value) {
        const latest = res.objectiveQuestions.find(
          (item) => item.practiceQuestionId === currentObjectiveQuestion.value?.practiceQuestionId,
        )
        if (latest) {
          currentObjectiveQuestion.value = latest
        }
      }
    }
    syncPendingJudgePolling()
  } catch (e) {
    const msg = e instanceof Error ? e.message : '加载课练详情失败'
    if (!silent) toastError(msg)
    if (!detail.value) {
      router.replace({ path: '/practice', query: { unlock: code.value } })
    }
  } finally {
    if (!silent) loading.value = false
  }
}

async function fetchScoreboard(silent = false) {
  if (!silent) scoreboardLoading.value = true
  try {
    const res = await practiceApi.scoreboard(code.value)
    scoreboard.value = res
    if (res.status === 'ENDED') {
      remainingSec.value = 0
      if (detail.value) detail.value.status = 'ENDED'
    } else if (res.remainingSeconds !== undefined && res.remainingSeconds !== null) {
      remainingSec.value = res.remainingSeconds
    }
  } catch (e) {
    if (!silent) toastError(e instanceof Error ? e.message : '加载实时成绩榜失败')
  } finally {
    if (!silent) scoreboardLoading.value = false
  }
}

function switchTab(tab: 'questions' | 'scoreboard') {
  activeTab.value = tab
  if (tab === 'scoreboard') {
    if (pendingJudgePollTimer) {
      clearInterval(pendingJudgePollTimer)
      pendingJudgePollTimer = null
    }
    fetchScoreboard()
    startScoreboardPolling()
  } else {
    stopScoreboardPolling()
    fetchDetail()
  }
}

function startCountdown() {
  countdownTimer = window.setInterval(() => {
    if (detail.value?.durationMinutes === 0) return
    if (remainingSec.value !== null && remainingSec.value > 0) {
      remainingSec.value--
      if (remainingSec.value === 0 && detail.value) {
        detail.value.status = 'ENDED'
      }
    }
  }, 1000)
}

function startScoreboardPolling() {
  stopScoreboardPolling()
  scoreboardPollTimer = window.setInterval(() => {
    if (activeTab.value === 'scoreboard' && detail.value?.status !== 'ENDED') {
      fetchScoreboard(true)
    }
  }, 5000)
}

function stopScoreboardPolling() {
  if (scoreboardPollTimer) {
    clearInterval(scoreboardPollTimer)
    scoreboardPollTimer = null
  }
}

function toggleSection(sec: 'objective' | 'db' | 'algo') {
  collapsedSections.value[sec] = !collapsedSections.value[sec]
}

async function copyRoomCode() {
  if (!detail.value?.accessCode) return
  const ok = await copyToClipboard(detail.value.accessCode)
  if (ok) {
    success(`房间口令 ${detail.value.accessCode} 已复制到剪贴板`)
  } else {
    toastError('复制失败，请手动复制')
  }
}

// 打开客观题作答抽屉并初始化对应题型草稿
function openObjectiveDrawer(q: PracticeObjectiveQuestion) {
  currentObjectiveQuestion.value = q
  const saved = objectiveAnswers.value[q.practiceQuestionId] ?? q.userAnswer ?? ''
  const qType = (q.type || 'SINGLE').toUpperCase()
  if (qType === 'MULTIPLE') {
    multiDraft.value = saved
      .split(',')
      .map((s) => s.trim().toUpperCase())
      .filter(Boolean)
  } else if (qType === 'FILL') {
    fillDraft.value = saved
  }
  objectiveDrawerVisible.value = true
}

function closeObjectiveDrawer() {
  objectiveDrawerVisible.value = false
  currentObjectiveQuestion.value = null
}

function isSingleSelected(optIdx: number): boolean {
  const q = currentObjectiveQuestion.value
  if (!q) return false
  const letter = String.fromCharCode(65 + optIdx)
  const saved = (objectiveAnswers.value[q.practiceQuestionId] ?? q.userAnswer ?? '')
    .trim()
    .toUpperCase()
  return saved === letter
}

function isJudgeSelected(target: '对' | '错'): boolean {
  const q = currentObjectiveQuestion.value
  if (!q) return false
  const saved = objectiveAnswers.value[q.practiceQuestionId] ?? q.userAnswer ?? ''
  return normalizeJudgeValue(saved) === target
}

function isMultiOptionChecked(optIdx: number): boolean {
  const letter = String.fromCharCode(65 + optIdx)
  return multiDraft.value.includes(letter)
}

function toggleMultiOption(optIdx: number) {
  if (isAnswerReadonly.value) return
  const letter = String.fromCharCode(65 + optIdx)
  const idx = multiDraft.value.indexOf(letter)
  if (idx >= 0) {
    multiDraft.value.splice(idx, 1)
  } else {
    multiDraft.value.push(letter)
    multiDraft.value.sort()
  }
}

async function persistObjectiveAnswer(answerValue: string) {
  const q = currentObjectiveQuestion.value
  if (!q || isAnswerReadonly.value || savingObjective.value) return
  const pqId = q.practiceQuestionId
  objectiveAnswers.value[pqId] = answerValue

  savingObjective.value = true
  try {
    const res = await practiceApi.submitObjective(code.value, { [pqId]: answerValue })
    const itemRes = res.items.find((i) => i.practiceQuestionId === pqId)
    if (itemRes) {
      q.userAnswer = answerValue
      q.userScore = itemRes.score
      q.isCorrect = itemRes.correct
      q.answered = answerValue.trim().length > 0
      if (itemRes.correctAnswer != null) {
        q.correctAnswer = itemRes.correctAnswer
      }
      // 同步回列表引用
      const targetInList = detail.value?.objectiveQuestions.find(
        (item) => item.practiceQuestionId === pqId,
      )
      if (targetInList && targetInList !== q) {
        targetInList.userAnswer = q.userAnswer
        targetInList.userScore = q.userScore
        targetInList.isCorrect = q.isCorrect
        targetInList.answered = q.answered
        targetInList.correctAnswer = q.correctAnswer
      }
    }
    if (detail.value) {
      if (res.myTotalScore !== undefined) detail.value.myScore = res.myTotalScore
      if (res.mySolvedCount !== undefined) detail.value.mySolvedCount = res.mySolvedCount
    }
  } catch (e) {
    toastError(e instanceof Error ? e.message : '客观题答案保存失败')
  } finally {
    savingObjective.value = false
  }
}

function selectSingleOption(optIdx: number) {
  if (isAnswerReadonly.value) return
  const letter = String.fromCharCode(65 + optIdx)
  persistObjectiveAnswer(letter)
}

function selectJudgeOption(val: '对' | '错') {
  if (isAnswerReadonly.value) return
  persistObjectiveAnswer(val)
}

function submitMultipleAnswer() {
  if (isAnswerReadonly.value) return
  if (multiDraft.value.length === 0) {
    toastError('请至少勾选一个选项后再保存')
    return
  }
  const joined = [...multiDraft.value].sort().join(',')
  persistObjectiveAnswer(joined)
}

function submitFillAnswer() {
  if (isAnswerReadonly.value) return
  const val = fillDraft.value.trim()
  if (!val) {
    toastError('请填写答案后再保存')
    return
  }
  persistObjectiveAnswer(val)
}

// 跳转主站做题工作台（已交卷/已截止且存在提交记录时优先直达评测详情页）
function goToCoding(q: PracticePracticalQuestion) {
  const pqId = q.practiceQuestionId
  const query = { practiceCode: code.value, pqId: String(pqId) }
  if (isPracticeLocked.value && q.submissionId) {
    const resultPath =
      q.questionType === 'DB'
        ? `/database/${q.questionId}/result/${q.submissionId}`
        : `/questions/${q.questionId}/result/${q.submissionId}`
    router.push({ path: resultPath, query })
    return
  }
  if (q.questionType === 'DB') {
    router.push({ path: `/database/${q.questionId}`, query })
  } else {
    router.push({ path: `/questions/${q.questionId}`, query })
  }
}

// 正式交卷
async function handleSubmitPractice() {
  const ok = await confirm({
    title: '确认提交整场课练',
    message: '提交后本次课练作答将锁定归档，不能再次更改答案，且将解锁客观题参考答案。确定要正式交卷吗？',
    confirmText: '确认交卷',
    cancelText: '继续作答',
  })
  if (!ok) return

  submittingPractice.value = true
  try {
    await practiceApi.submit(code.value)
    success('已成功交卷！')
    await fetchDetail()
  } catch (e) {
    toastError(e instanceof Error ? e.message : '交卷失败')
  } finally {
    submittingPractice.value = false
  }
}

function formatScoreboardStudent(row: { userId: number; username: string }): string {
  const isMe = row.userId === userStore.userInfo?.id
  if (isMe) return `${row.username} (我)`
  if (!isAnonymous.value || !row.username) return row.username
  if (row.username.length <= 2) return `${row.username.charAt(0)}***`
  return `${row.username.slice(0, 2)}${'*'.repeat(Math.min(5, row.username.length - 2))}`
}

onMounted(() => {
  fetchDetail()
  startCountdown()
})

onBeforeUnmount(() => {
  if (countdownTimer) clearInterval(countdownTimer)
  if (pendingJudgePollTimer) clearInterval(pendingJudgePollTimer)
  stopScoreboardPolling()
})
</script>

<template>
  <div class="practice-detail-page container">
    <!-- 极简面包屑 -->
    <nav class="nav-trail">
      <router-link to="/">首页</router-link>
      <span class="sep">/</span>
      <router-link to="/practice">随堂课练</router-link>
      <span class="sep">/</span>
      <span class="current">{{ detail?.title || '加载中...' }}</span>
    </nav>

    <!-- 顶层整合行：状态元数据 + 页面切换 Switcher -->
    <div class="top-meta-row">
      <div class="top-meta-left">
        <span class="status-indicator">
          <span class="status-dot" :class="{ ended: detail?.status === 'ENDED' }" />
          {{ detail?.status === 'ENDED' ? '已结束' : '进行中' }}
        </span>
        <button class="room-code-tag mono" title="点击复制口令" @click="copyRoomCode">
          #{{ code }} ⧉
        </button>
        <span class="badge-tag mono">ACM 赛制</span>
        <span class="text-sep">·</span>
        <span class="teacher-info">授课教师：{{ detail?.teacherName || '任课教师' }}</span>
      </div>

      <div class="top-segmented-tabs">
        <button
          class="top-tab-btn"
          :class="{ active: activeTab === 'questions' }"
          @click="switchTab('questions')"
        >
          题目列表
          <span class="top-tab-num mono">({{ totalQuestionsCount }})</span>
        </button>
        <button
          class="top-tab-btn"
          :class="{ active: activeTab === 'scoreboard' }"
          @click="switchTab('scoreboard')"
        >
          实时成绩榜
          <span class="top-tab-num mono">({{ scoreboard?.rows.length ?? detail?.memberCount ?? 0 }})</span>
        </button>
      </div>
    </div>

    <!-- 标题与交卷/预览操作行 -->
    <div class="title-action-row">
      <div class="title-block">
        <h1 class="main-h1">{{ detail?.title }}</h1>
        <p v-if="detail?.description" class="main-desc muted">{{ detail.description }}</p>
      </div>

      <div class="header-ops">
        <div v-if="detail?.myJoined" class="countdown-widget">
          <span class="countdown-label">当前得分</span>
          <span class="countdown-num mono">{{ detail.myScore ?? 0 }} / {{ totalFullScore }}</span>
        </div>

        <div class="countdown-widget">
          <span class="countdown-label">剩余作答时间</span>
          <span class="countdown-num mono">{{ formattedRemaining }}</span>
        </div>

        <template v-if="isTeacherPreview && detail">
          <span class="status-pill-preview">教师预览视角</span>
          <router-link
            :to="`/admin/practices/${detail.id}/overview`"
            class="btn btn-primary btn-sm"
          >
            考务大盘 ↗
          </router-link>
        </template>
        <button
          v-else-if="!detail?.mySubmitted && detail?.status !== 'ENDED'"
          class="btn btn-primary"
          :disabled="submittingPractice"
          @click="handleSubmitPractice"
        >
          {{ submittingPractice ? '交卷中...' : '正式交卷' }}
        </button>
        <span v-else class="status-pill-submitted">
          {{ detail?.mySubmitted ? '✓ 已交卷 (锁定)' : '课练已截止' }}
        </span>
      </div>
    </div>

    <!-- 全局统一加载态 -->
    <div v-if="loading && !detail" class="loading-box card muted">
      <span class="loading-spin"></span>
      <span>正在载入随堂实训数据…</span>
    </div>

    <!-- ========================================================
         TAB 1: 题目按「题型」划分章节
         ======================================================== -->
    <div v-else-if="activeTab === 'questions'" :key="activeTab" class="sections-list">
      <div v-if="totalQuestionsCount === 0" class="empty-box card muted">
        本场随堂课练暂未配置题目
      </div>

      <!-- 章节：客观理论选择题 -->
      <section v-if="objectiveQuestions.length > 0" class="section-block card">
        <div class="section-head" @click="toggleSection('objective')">
          <div class="sec-head-left">
            <span class="sec-index mono">{{ objectiveSecIdx }}</span>
            <div>
              <h2 class="sec-title">客观理论题</h2>
              <p class="sec-desc">即答即判 · 支持单选、判断、多选与填空实时订正</p>
            </div>
          </div>
          <div class="sec-head-right mono">
            <span
              class="sec-stat"
              :class="{
                'stat-ac':
                  objectiveStat.passed === objectiveStat.totalCount &&
                  objectiveStat.totalCount > 0,
              }"
            >
              {{ objectiveStat.passed }} / {{ objectiveStat.totalCount }} 已通过 ·
              {{ objectiveStat.earned }}/{{ objectiveStat.total }} 分
            </span>
            <span class="sec-toggle">
              {{ collapsedSections.objective ? '+ 展开' : '− 收起' }}
            </span>
          </div>
        </div>

        <div v-if="!collapsedSections.objective" class="section-items">
          <div
            v-for="(q, idx) in objectiveQuestions"
            :key="q.practiceQuestionId"
            class="question-item card"
            :style="{ '--drop-index': idx }"
            @click="openObjectiveDrawer(q)"
          >
            <div class="q-main-layout">
              <div class="q-status-wrap">
                <span v-if="q.isCorrect" class="status-icon solved" title="已攻克 (Accepted)">
                  <svg viewBox="0 0 16 16" fill="currentColor">
                    <path
                      d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z"
                    />
                  </svg>
                </span>
                <span
                  v-else-if="q.userScore && q.userScore > 0"
                  class="status-icon partial"
                  title="部分正确"
                >
                  <svg viewBox="0 0 16 16" fill="currentColor">
                    <path
                      d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14Zm0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16Zm-.75-9.75a.75.75 0 0 1 1.5 0v3.5a.75.75 0 0 1-1.5 0v-3.5Zm.75 6.25a.875.875 0 1 1 0-1.75.875.875 0 0 1 0 1.75Z"
                    />
                  </svg>
                </span>
                <span v-else class="status-icon unsolved" :title="q.answered ? '待订正' : '未作答'">
                  <span class="unsolved-ring" />
                </span>
              </div>

              <span class="q-number mono">#{{ String(idx + 1).padStart(2, '0') }}</span>

              <div class="q-content">
                <h3 class="q-title">{{ plainStemPreview(q.content) }}</h3>
                <div class="q-tags-row">
                  <span class="meta-tag score mono">{{ q.score }} 分</span>
                  <span class="meta-tag type mono">{{ getObjectivePill(q.type).label }}</span>
                  <span class="meta-tag limits mono">内联即答即判</span>
                </div>
              </div>
            </div>

            <div class="q-right-layout">
              <span class="difficulty-pill" :class="getObjectivePill(q.type).cls">
                {{ getObjectivePill(q.type).label }}
              </span>

              <span v-if="q.isCorrect" class="status-badge ac">
                ✓ {{ q.userScore }}/{{ q.score }} AC
              </span>
              <span v-else-if="q.userScore && q.userScore > 0" class="status-badge partial">
                ! {{ q.userScore }}/{{ q.score }} PARTIAL
              </span>
              <span v-else-if="q.answered" class="status-badge wa">
                ✗ 0/{{ q.score }} WA
              </span>
              <span v-else class="status-badge none">未作答</span>

              <span class="action-cta">
                <span>{{ q.answered ? '查看/订正' : '开始作答' }}</span>
                <svg
                  class="cta-arrow"
                  viewBox="0 0 16 16"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M6 3l5 5-5 5" />
                </svg>
              </span>
            </div>
          </div>
        </div>
      </section>

      <!-- 章节：数据库 SQL 结构分析 -->
      <section v-if="dbQuestions.length > 0" class="section-block card">
        <div class="section-head" @click="toggleSection('db')">
          <div class="sec-head-left">
            <span class="sec-index mono">{{ dbSecIdx }}</span>
            <div>
              <h2 class="sec-title">数据库 SQL 结构分析</h2>
              <p class="sec-desc">沙箱隔离实例 · 考查 SQL 查询、分组聚合与数据操作</p>
            </div>
          </div>
          <div class="sec-head-right mono">
            <span
              class="sec-stat"
              :class="{ 'stat-ac': dbStat.passed === dbStat.totalCount && dbStat.totalCount > 0 }"
            >
              {{ dbStat.passed }} / {{ dbStat.totalCount }} 已通过 ·
              {{ dbStat.earned }}/{{ dbStat.total }} 分
            </span>
            <span class="sec-toggle">
              {{ collapsedSections.db ? '+ 展开' : '− 收起' }}
            </span>
          </div>
        </div>

        <div v-if="!collapsedSections.db" class="section-items">
          <div
            v-for="(q, idx) in dbQuestions"
            :key="q.practiceQuestionId"
            class="question-item card"
            :style="{ '--drop-index': idx }"
            @click="goToCoding(q)"
          >
            <div class="q-main-layout">
              <div class="q-status-wrap">
                <span
                  v-if="q.userStatus === 'ACCEPTED'"
                  class="status-icon solved"
                  title="已攻克 (Accepted)"
                >
                  <svg viewBox="0 0 16 16" fill="currentColor">
                    <path
                      d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z"
                    />
                  </svg>
                </span>
                <span v-else-if="q.userScore > 0" class="status-icon partial" title="部分分通过">
                  <svg viewBox="0 0 16 16" fill="currentColor">
                    <path
                      d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14Zm0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16Zm-.75-9.75a.75.75 0 0 1 1.5 0v3.5a.75.75 0 0 1-1.5 0v-3.5Zm.75 6.25a.875.875 0 1 1 0-1.75.875.875 0 0 1 0 1.75Z"
                    />
                  </svg>
                </span>
                <span v-else class="status-icon unsolved" title="未通过或未做">
                  <span class="unsolved-ring" />
                </span>
              </div>

              <span class="q-number mono">
                #{{ String(objectiveQuestions.length + idx + 1).padStart(2, '0') }}
              </span>

              <div class="q-content">
                <h3 class="q-title">{{ q.title }}</h3>
                <div class="q-tags-row">
                  <span class="meta-tag score mono">{{ q.score }} 分</span>
                  <span class="meta-tag dialect mono">MySQL 8.0</span>
                  <span class="meta-tag limits mono">结果集严格校验</span>
                </div>
              </div>
            </div>

            <div class="q-right-layout">
              <span class="difficulty-pill" :class="getDbPill(q).cls">
                {{ getDbPill(q).label }}
              </span>

              <span class="status-badge" :class="getPracticalBadge(q).cls">
                {{ getPracticalBadge(q).text }}
              </span>

              <span class="action-cta">
                <span>{{ getPracticalCtaText(q) }}</span>
                <svg
                  class="cta-arrow"
                  viewBox="0 0 16 16"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M6 3l5 5-5 5" />
                </svg>
              </span>
            </div>
          </div>
        </div>
      </section>

      <!-- 章节：算法与实操编程评测 -->
      <section v-if="algoQuestions.length > 0" class="section-block card">
        <div class="section-head" @click="toggleSection('algo')">
          <div class="sec-head-left">
            <span class="sec-index mono">{{ algoSecIdx }}</span>
            <div>
              <h2 class="sec-title">算法与实操编程评测</h2>
              <p class="sec-desc">标准在线沙箱评测 · 包含数据结构逻辑构建与时空复杂度约束</p>
            </div>
          </div>
          <div class="sec-head-right mono">
            <span
              class="sec-stat"
              :class="{
                'stat-ac': algoStat.passed === algoStat.totalCount && algoStat.totalCount > 0,
              }"
            >
              {{ algoStat.passed }} / {{ algoStat.totalCount }} 已通过 ·
              {{ algoStat.earned }}/{{ algoStat.total }} 分
            </span>
            <span class="sec-toggle">
              {{ collapsedSections.algo ? '+ 展开' : '− 收起' }}
            </span>
          </div>
        </div>

        <div v-if="!collapsedSections.algo" class="section-items">
          <div
            v-for="(q, idx) in algoQuestions"
            :key="q.practiceQuestionId"
            class="question-item card"
            :style="{ '--drop-index': idx }"
            @click="goToCoding(q)"
          >
            <div class="q-main-layout">
              <div class="q-status-wrap">
                <span
                  v-if="q.userStatus === 'ACCEPTED'"
                  class="status-icon solved"
                  title="已攻克 (Accepted)"
                >
                  <svg viewBox="0 0 16 16" fill="currentColor">
                    <path
                      d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z"
                    />
                  </svg>
                </span>
                <span v-else-if="q.userScore > 0" class="status-icon partial" title="部分分通过">
                  <svg viewBox="0 0 16 16" fill="currentColor">
                    <path
                      d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14Zm0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16Zm-.75-9.75a.75.75 0 0 1 1.5 0v3.5a.75.75 0 0 1-1.5 0v-3.5Zm.75 6.25a.875.875 0 1 1 0-1.75.875.875 0 0 1 0 1.75Z"
                    />
                  </svg>
                </span>
                <span v-else class="status-icon unsolved" title="未通过或未做">
                  <span class="unsolved-ring" />
                </span>
              </div>

              <span class="q-number mono">
                #{{ String(objectiveQuestions.length + dbQuestions.length + idx + 1).padStart(2, '0') }}
              </span>

              <div class="q-content">
                <h3 class="q-title">{{ q.title }}</h3>
                <div class="q-tags-row">
                  <span class="meta-tag score mono">{{ q.score }} 分</span>
                  <span class="meta-tag limits mono">
                    {{ q.timeLimitMs ? `${q.timeLimitMs}ms` : '1000ms' }} /
                    {{ q.memoryLimitKb ? `${Math.round(q.memoryLimitKb / 1024)}MB` : '256MB' }}
                  </span>
                  <span
                    v-for="langLabel in getAlgoLangLabels(q)"
                    :key="langLabel"
                    class="meta-tag lang mono"
                  >
                    {{ langLabel }}
                  </span>
                </div>
              </div>
            </div>

            <div class="q-right-layout">
              <span class="difficulty-pill" :class="getAlgoPill(q).cls">
                {{ getAlgoPill(q).label }}
              </span>

              <span class="status-badge" :class="getPracticalBadge(q).cls">
                {{ getPracticalBadge(q).text }}
              </span>

              <span class="action-cta">
                <span>{{ getPracticalCtaText(q) }}</span>
                <svg
                  class="cta-arrow"
                  viewBox="0 0 16 16"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M6 3l5 5-5 5" />
                </svg>
              </span>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- ========================================================
         TAB 2: 正统实时成绩榜 (Scoreboard) - ACM 矩阵网格
         ======================================================== -->
    <div v-else :key="activeTab" class="scoreboard-wrapper">
      <div class="scoreboard-toolbar card">
        <div class="toolbar-tip">
          ACM/ICPC 点阵单元格：绿色全过，橙色部分分，蓝色评测中，红色未通过（显示累计尝试次数）
        </div>
        <div class="toolbar-controls">
          <label class="anon-toggle-label">
            <input v-model="isAnonymous" type="checkbox" />
            <span>隐藏账号 (脱敏模式)</span>
          </label>
          <span class="auto-refresh-tag mono">自动刷新: 5s</span>
        </div>
      </div>

      <div v-if="scoreboardLoading && !scoreboard" class="loading-box card muted">
        <span class="loading-spin"></span>
        <span>正在同步实时成绩榜…</span>
      </div>

      <div v-else class="scoreboard-container card">
        <table class="scoreboard-table">
          <thead>
            <tr>
              <th style="width: 56px">排名</th>
              <th style="width: 160px">参练学生 (账号)</th>
              <th style="width: 72px">总分</th>
              <th style="width: 60px">AC</th>
              <th
                v-for="h in scoreboard?.questionHeaders"
                :key="h.practiceQuestionId"
                style="min-width: 90px"
              >
                {{
                  h.questionType === 'OBJECTIVE'
                    ? '客观'
                    : h.questionType === 'DB'
                      ? 'SQL'
                      : '算法'
                }}
                <span class="mono" style="opacity: 0.8">({{ h.score }})</span>
              </th>
              <th style="width: 120px">最后提交</th>
            </tr>
            <!-- 全班各题 AC 率基准行 -->
            <tr class="base-summary-row">
              <td colspan="2" style="text-align: right; font-weight: 600; padding-right: 14px">
                全班各题 AC 率
              </td>
              <td style="text-align: center">-</td>
              <td style="text-align: center">-</td>
              <td
                v-for="b in scoreboard?.benchmarks"
                :key="b.practiceQuestionId"
                class="mono"
                style="text-align: center"
              >
                {{ b.passUserCount }}/{{ b.submitUserCount }} ({{ b.passRate }}%)
              </td>
              <td style="text-align: center">-</td>
            </tr>
          </thead>

          <tbody>
            <tr
              v-for="row in scoreboard?.rows"
              :key="row.userId"
              :class="{ 'my-position': row.userId === userStore.userInfo?.id }"
            >
              <td class="mono" style="text-align: center; font-weight: 600">
                {{ row.rank }}
                <span v-if="row.userId === userStore.userInfo?.id" style="color: #2b8a3e">★</span>
              </td>
              <td class="mono">{{ formatScoreboardStudent(row) }}</td>
              <td class="mono" style="font-weight: 700; text-align: center">
                {{ row.totalScore }}
              </td>
              <td class="mono" style="text-align: center">{{ row.solvedCount }}</td>

              <!-- 各题状态网格 -->
              <td
                v-for="cell in row.cellStates"
                :key="cell.practiceQuestionId"
                :class="{
                  'cell-state-ac': cell.status === 'ACCEPTED',
                  'cell-state-running': isPendingStatus(cell.status),
                  'cell-state-partial': cell.status === 'PARTIAL',
                  'cell-state-wa':
                    cell.status &&
                    cell.status !== 'ACCEPTED' &&
                    cell.status !== 'PARTIAL' &&
                    cell.status !== 'UNATTEMPTED' &&
                    !isPendingStatus(cell.status),
                  'cell-state-none': cell.status === 'UNATTEMPTED' || !cell.status,
                }"
              >
                <template v-if="cell.status === 'ACCEPTED'">
                  +{{ cell.attempts || 1 }}
                  <span v-if="cell.elapsedMinutes" class="time-sub">{{ cell.elapsedMinutes }}'</span>
                </template>
                <template v-else-if="isPendingStatus(cell.status)">
                  评测中
                </template>
                <template v-else-if="cell.status === 'PARTIAL'">
                  {{ cell.score }}分
                </template>
                <template
                  v-else-if="
                    cell.status &&
                    cell.status !== 'UNATTEMPTED'
                  "
                >
                  -{{ cell.attempts || 1 }}
                </template>
                <template v-else>-</template>
              </td>

              <td class="mono" style="font-size: 11px; color: var(--ink-secondary); text-align: center">
                {{ row.lastSubmitTime ? row.lastSubmitTime.replace('T', ' ').substring(11, 19) : '-' }}
              </td>
            </tr>
            <tr v-if="!scoreboard?.rows?.length">
              <td
                :colspan="5 + (scoreboard?.questionHeaders?.length ?? 0)"
                style="text-align: center; padding: 28px; color: var(--ink-tertiary)"
              >
                暂无参练学生记录
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 客观题全题型作答抽屉（支持单选 / 判断 / 多选 / 填空 + 方案 B 即时反馈） -->
    <div
      v-if="objectiveDrawerVisible && currentObjectiveQuestion"
      class="drawer-backdrop"
      :class="{ active: objectiveDrawerVisible }"
      @click.self="closeObjectiveDrawer"
    >
      <div class="drawer-content">
        <div class="drawer-header">
          <div class="drawer-title-wrap">
            <div class="drawer-meta-bar">
              <span
                class="difficulty-pill"
                :class="getObjectivePill(currentObjectiveQuestion.type).cls"
              >
                {{ getObjectivePill(currentObjectiveQuestion.type).label }}
              </span>
              <span class="mono drawer-score-tag">满分 {{ currentObjectiveQuestion.score }} 分</span>
              <span v-if="isTeacherPreview" class="mono drawer-readonly-tag">教师预览只读</span>
              <span v-else-if="isPracticeLocked" class="mono drawer-readonly-tag">已封卷只读</span>
            </div>
          </div>
          <button class="btn btn-ghost btn-sm" @click="closeObjectiveDrawer">关闭 ✕</button>
        </div>

        <div class="drawer-body">
          <!-- eslint-disable-next-line vue/no-v-html -- 内容已由 renderMarkdown 清洗 -->
          <div class="drawer-stem prose" v-html="currentObjectiveStemHtml"></div>

          <!-- 1. 判断题标准二选一卡片 -->
          <div
            v-if="(currentObjectiveQuestion.type || '').toUpperCase() === 'JUDGE'"
            class="judge-grid"
          >
            <button
              type="button"
              class="judge-card"
              :class="{ selected: isJudgeSelected('对'), disabled: isAnswerReadonly }"
              :disabled="isAnswerReadonly || savingObjective"
              @click="selectJudgeOption('对')"
            >
              <span class="judge-symbol mono">✓</span>
              <span class="judge-text">对（正确）</span>
            </button>
            <button
              type="button"
              class="judge-card"
              :class="{ selected: isJudgeSelected('错'), disabled: isAnswerReadonly }"
              :disabled="isAnswerReadonly || savingObjective"
              @click="selectJudgeOption('错')"
            >
              <span class="judge-symbol mono">✕</span>
              <span class="judge-text">错（错误）</span>
            </button>
          </div>

          <!-- 2. 多选题复选卡片组 + 保存按钮 -->
          <div
            v-else-if="(currentObjectiveQuestion.type || '').toUpperCase() === 'MULTIPLE'"
            class="q-options-container"
          >
            <div class="multi-hint muted">
              多选题：请勾选所有正确选项（漏选可得部分分，选错得 0 分），勾选完毕后点击「保存本题答案」。
            </div>
            <div
              v-for="(opt, optIdx) in currentObjectiveQuestion.options"
              :key="optIdx"
              class="option-item"
              :class="{
                selected: isMultiOptionChecked(optIdx),
                disabled: isAnswerReadonly,
              }"
              @click="toggleMultiOption(optIdx)"
            >
              <span class="opt-check mono">{{ isMultiOptionChecked(optIdx) ? '✓' : '' }}</span>
              <span class="opt-label mono">{{ String.fromCharCode(65 + optIdx) }}</span>
              <span class="opt-text">{{ stripOptionPrefix(opt) }}</span>
            </div>

            <div v-if="!isAnswerReadonly" class="drawer-inline-save">
              <span class="mono muted" style="font-size: 12px">
                当前已勾选: {{ multiDraft.length ? multiDraft.join(', ') : '未勾选' }}
              </span>
              <button
                type="button"
                class="btn btn-primary btn-sm"
                :disabled="savingObjective || multiDraft.length === 0"
                @click="submitMultipleAnswer"
              >
                {{ savingObjective ? '判分保存中…' : '保存本题答案' }}
              </button>
            </div>
          </div>

          <!-- 3. 填空题输入框 + 保存按钮 -->
          <div
            v-else-if="(currentObjectiveQuestion.type || '').toUpperCase() === 'FILL'"
            class="fill-editor-box"
          >
            <label class="fill-input-label">请在下方填写答案（支持回车直接提交判分）：</label>
            <div class="fill-input-row">
              <input
                v-model="fillDraft"
                type="text"
                class="input fill-input mono"
                :disabled="isAnswerReadonly || savingObjective"
                placeholder="请输入本题答案…"
                @keydown.enter.prevent="submitFillAnswer"
              />
              <button
                v-if="!isAnswerReadonly"
                type="button"
                class="btn btn-primary"
                :disabled="savingObjective || !fillDraft.trim()"
                @click="submitFillAnswer"
              >
                {{ savingObjective ? '判分中…' : '保存答案' }}
              </button>
            </div>
          </div>

          <!-- 4. 单选题选项列表（点击即传选项字母 A/B/C/D 判分） -->
          <div v-else class="q-options-container">
            <div
              v-for="(opt, optIdx) in currentObjectiveQuestion.options"
              :key="optIdx"
              class="option-item"
              :class="{
                selected: isSingleSelected(optIdx),
                disabled: isAnswerReadonly,
              }"
              @click="selectSingleOption(optIdx)"
            >
              <span class="opt-label mono">{{ String.fromCharCode(65 + optIdx) }}</span>
              <span class="opt-text">{{ stripOptionPrefix(opt) }}</span>
            </div>
          </div>

          <!-- 方案 B：即时判分反馈 + 交卷/截止/教师视角标准答案展示 -->
          <div
            v-if="currentObjectiveQuestion.answered || currentObjectiveQuestion.correctAnswer"
            class="answer-feedback-box"
          >
            <div v-if="currentObjectiveQuestion.answered" class="feedback-head">
              <span class="mono" style="font-weight: 600">判定状态：</span>
              <span
                v-if="currentObjectiveQuestion.isCorrect"
                style="color: #2b8a3e; font-weight: 700"
              >
                ✓ 完全正确 (+{{ currentObjectiveQuestion.userScore }} 分)
              </span>
              <span
                v-else-if="(currentObjectiveQuestion.userScore ?? 0) > 0"
                style="color: #e67700; font-weight: 700"
              >
                ! 部分正确 (+{{ currentObjectiveQuestion.userScore }} / {{ currentObjectiveQuestion.score }} 分)
              </span>
              <span v-else style="color: #c92a2a; font-weight: 700">
                ✗ 回答错误 (0 分{{ isAnswerReadonly ? '' : ' · 可直接修改重答' }})
              </span>
            </div>

            <div
              v-if="currentObjectiveQuestion.correctAnswer != null"
              class="feedback-std mono"
            >
              <span>标准参考答案：</span>
              <strong>{{ currentObjectiveQuestion.correctAnswer }}</strong>
            </div>
          </div>
        </div>

        <div class="drawer-footer">
          <span class="save-status-indicator muted">
            {{
              savingObjective
                ? '正在即时同步判分…'
                : isAnswerReadonly
                  ? '当前处于只读查阅模式'
                  : '作答结果已实时计入随堂总分'
            }}
          </span>
          <button class="btn btn-primary btn-sm" @click="closeObjectiveDrawer">完成并关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.practice-detail-page {
  padding-top: 24px;
  padding-bottom: 72px;
}

.mono {
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-feature-settings: 'tnum' 1;
}

/* 导航面包屑 */
.nav-trail {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  font-size: 12.5px;
  color: var(--ink-tertiary);
}
.nav-trail a {
  color: var(--ink-secondary);
  text-decoration: none;
}
.nav-trail a:hover {
  color: var(--ink);
}
.nav-trail .sep {
  color: var(--line);
}
.nav-trail .current {
  color: var(--ink);
  font-weight: 600;
}

/* 顶层整合行：状态元数据 + 置顶切换 */
.top-meta-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 16px;
  flex-wrap: wrap;
}

.top-meta-left {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.status-indicator {
  display: inline-flex;
  align-items: center;
  font-weight: 600;
  color: var(--ink);
}
.status-dot {
  width: 7px;
  height: 7px;
  background: #2b8a3e;
  display: inline-block;
  margin-right: 6px;
}
.status-dot.ended {
  background: var(--ink-tertiary);
}

.room-code-tag {
  font-size: 12px;
  font-weight: 700;
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 2px 9px;
  color: var(--ink);
  cursor: pointer;
  border-radius: 0;
  transition: all 0.15s ease;
}
.room-code-tag:hover {
  border-color: var(--ink);
  background: var(--bg);
}

.badge-tag {
  font-size: 11px;
  background: var(--ink);
  color: #ffffff;
  padding: 2px 7px;
  font-weight: 700;
}

.text-sep {
  color: var(--line);
}

.teacher-info {
  color: var(--ink-secondary);
}

/* 置顶切换 Switcher */
.top-segmented-tabs {
  display: inline-flex;
  border: 1px solid var(--line);
  background: var(--surface);
  padding: 2px;
}
.top-tab-btn {
  padding: 5px 14px;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  border-radius: 0;
  transition: all 0.15s ease;
}
.top-tab-btn:hover {
  color: var(--ink);
}
.top-tab-btn.active {
  background: var(--ink);
  color: #ffffff;
  font-weight: 600;
}
.top-tab-num {
  font-size: 11px;
  opacity: 0.85;
}

/* 标题与动作行 */
.title-action-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding-bottom: 18px;
  margin-bottom: 22px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}
.title-block {
  min-width: 0;
}
.main-h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.4px;
}
.main-desc {
  margin: 4px 0 0;
  font-size: 13px;
}

.header-ops {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.countdown-widget {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 5px 12px;
}
.countdown-num {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}

.status-pill-submitted {
  font-size: 12.5px;
  padding: 6px 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
  font-weight: 600;
}

.status-pill-preview {
  font-size: 12px;
  padding: 6px 12px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1d4ed8;
  font-weight: 600;
}

.empty-box {
  padding: 48px 24px;
  text-align: center;
  border-radius: 0;
}

/* ============================================================
   章节卡片列表与题目条目：与 ProblemSetDetailView.vue 1:1 完全对齐
============================================================ */
.sections-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-block {
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 0;
  overflow: hidden;
}

.section-head {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 22px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  user-select: none;
  transition: background 0.15s ease;
}
.section-head:hover {
  background: var(--surface-hover);
}

.sec-head-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.sec-index {
  width: 32px;
  height: 32px;
  background: var(--ink);
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.sec-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
}

.sec-desc {
  font-size: 12.5px;
  color: var(--ink-secondary);
  margin: 2px 0 0;
}

.sec-head-right {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  color: var(--ink-secondary);
}
.sec-stat.stat-ac {
  color: #2b8a3e;
  font-weight: 700;
}
.sec-toggle {
  padding: 2px 8px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink);
}

/* 章节内题目卡片条 */
.section-items {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  background: var(--bg);
}

.question-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
  text-decoration: none;
  color: inherit;
  cursor: pointer;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  animation: cardDropIn 0.44s cubic-bezier(0.25, 1.15, 0.5, 1) backwards;
  animation-delay: calc(var(--drop-index, 0) * 45ms);
  will-change: transform, opacity;
}
.question-item:hover {
  border-color: var(--ink);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.06);
  transform: translateY(-2px) !important;
}

@keyframes cardDropIn {
  0% {
    opacity: 0;
    transform: translateY(-38px) scale(0.985);
  }
  60% {
    opacity: 1;
    transform: translateY(3px) scale(1.002);
  }
  82% {
    opacity: 1;
    transform: translateY(-1.5px) scale(0.999);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .question-item {
    animation: none !important;
  }
}

.q-main-layout {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  flex: 1;
}

.q-status-wrap {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.status-icon.solved {
  color: #2b8a3e;
  width: 20px;
  height: 20px;
  display: inline-flex;
}
.status-icon.partial {
  color: #e67700;
  width: 20px;
  height: 20px;
  display: inline-flex;
}
.status-icon.unsolved {
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.unsolved-ring {
  width: 12px;
  height: 12px;
  border: 1.5px solid #d0d0d0;
  border-radius: 50%;
}

.q-number {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-secondary);
  min-width: 36px;
  flex-shrink: 0;
}

.q-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.q-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.q-tags-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.meta-tag {
  font-size: 11.5px;
  padding: 1px 7px;
  line-height: 1.4;
  border-radius: 0;
}
.meta-tag.score {
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink);
  font-weight: 700;
}
.meta-tag.limits {
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}
.meta-tag.lang,
.meta-tag.type {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  color: #3f3f46;
  font-weight: 600;
}
.meta-tag.dialect {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
  font-weight: 600;
}

.q-right-layout {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

/* 与全局题集完全一致的题型/难度胶囊 */
.difficulty-pill {
  min-width: 58px;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  border-radius: 0;
  letter-spacing: 0.3px;
  color: #ffffff;
  white-space: nowrap;
}
.pill-easy {
  background: #2b8a3e;
  color: #ffffff;
  border: 1px solid #237032;
}
.pill-medium {
  background: #e67700;
  color: #ffffff;
  border: 1px solid #c96500;
}
.pill-hard {
  background: #c92a2a;
  color: #ffffff;
  border: 1px solid #b02525;
}
.pill-select {
  background: #1e1e1e;
  color: #ffffff;
  border: 1px solid #111111;
}
.pill-dml {
  background: #495057;
  color: #ffffff;
  border: 1px solid #343a40;
}
.pill-single {
  background: #f8f9fa;
  color: #18181b;
  border: 1px solid #d4d4d8;
}
.pill-multiple {
  background: #d4d4d8;
  color: #18181b;
  border: 1px solid #a1a1aa;
}
.pill-judge {
  background: #52525b;
  color: #ffffff;
  border: 1px solid #3f3f46;
}
.pill-fill {
  background: #18181b;
  color: #ffffff;
  border: 1px solid #09090b;
}

.status-badge {
  min-width: 82px;
  padding: 3px 8px;
  font-size: 11.5px;
  font-weight: 600;
  text-align: center;
  white-space: nowrap;
  font-family: 'JetBrains Mono', monospace;
}
.status-badge.ac {
  background: #ebfbee;
  color: #2b8a3e;
  border: 1px solid #b2f2bb;
}
.status-badge.running {
  background: #eff6ff;
  color: #1d4ed8;
  border: 1px solid #93c5fd;
}
.status-badge.partial {
  background: #fff9db;
  color: #e67700;
  border: 1px solid #ffe066;
}
.status-badge.wa {
  background: #fdebec;
  color: #c92a2a;
  border: 1px solid #ffa8a8;
}
.status-badge.none {
  background: var(--bg);
  color: var(--ink-tertiary);
  border: 1px solid var(--line);
}

.action-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  min-width: 72px;
  justify-content: flex-end;
  transition: all 0.15s ease;
}
.question-item:hover .action-cta {
  color: var(--ink);
}
.question-item:hover .cta-arrow {
  transform: translateX(3px);
}
.cta-arrow {
  width: 14px;
  height: 14px;
  transition: transform 0.15s ease;
}

/* 实时成绩榜 Scoreboard 网格 */
.scoreboard-wrapper {
  margin-bottom: 40px;
  animation: cardDropIn 0.44s cubic-bezier(0.25, 1.15, 0.5, 1) backwards;
}
.scoreboard-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 18px;
  margin-bottom: 14px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
  font-size: 12.5px;
  color: var(--ink-secondary);
  flex-wrap: wrap;
  gap: 12px;
}
.toolbar-controls {
  display: flex;
  align-items: center;
  gap: 14px;
}
.anon-toggle-label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--ink);
  font-weight: 500;
}
.auto-refresh-tag {
  font-size: 11.5px;
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 2px 8px;
  color: var(--ink-secondary);
}

.scoreboard-container {
  border: 1px solid var(--line);
  border-radius: 0;
  overflow-x: auto;
  background: var(--bg);
}

.scoreboard-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.scoreboard-table th {
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  border-right: 1px solid var(--line);
  padding: 10px 12px;
  font-weight: 600;
  color: var(--ink-secondary);
  font-size: 12px;
  text-align: center;
  white-space: nowrap;
}
.scoreboard-table th:nth-child(2) {
  text-align: left;
}
.scoreboard-table td {
  border-bottom: 1px solid var(--line);
  border-right: 1px solid var(--line);
  padding: 9px 12px;
  vertical-align: middle;
  white-space: nowrap;
}

.scoreboard-table tr.my-position td {
  background: #f8fafc !important;
  font-weight: 600;
}
.scoreboard-table tr.my-position td:first-child {
  border-left: 3px solid var(--ink);
}

.base-summary-row td {
  background: var(--surface);
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  color: var(--ink-secondary);
  border-bottom: 1px solid var(--line);
}

.cell-state-ac {
  background: #ebfbee;
  color: #2b8a3e;
  text-align: center;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  font-weight: 600;
}
.cell-state-ac .time-sub {
  display: block;
  font-size: 9.5px;
  color: #16a34a;
  font-weight: 400;
}
.cell-state-running {
  background: #eff6ff;
  color: #1d4ed8;
  text-align: center;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 600;
}
.cell-state-partial {
  background: #fff9db;
  color: #e67700;
  text-align: center;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  font-weight: 600;
}
.cell-state-wa {
  background: #fdebec;
  color: #c92a2a;
  text-align: center;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  font-weight: 600;
}
.cell-state-none {
  text-align: center;
  color: var(--ink-tertiary);
  font-family: 'JetBrains Mono', monospace;
}

/* 客观题作答抽屉 */
.drawer-backdrop {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(17, 17, 17, 0.38);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}
.drawer-content {
  width: 600px;
  max-width: 92vw;
  background: var(--bg);
  height: 100%;
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
}
.drawer-header {
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.drawer-meta-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.drawer-score-tag {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary);
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 2px 8px;
}
.drawer-readonly-tag {
  font-size: 11.5px;
  font-weight: 600;
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  padding: 2px 8px;
}

.drawer-body {
  padding: 22px;
  overflow-y: auto;
  flex: 1;
}
.drawer-stem {
  font-size: 15px;
  line-height: 1.75;
  color: var(--ink);
  padding: 16px 18px;
  background: var(--surface);
  border: 1px solid var(--line);
  margin-bottom: 18px;
}
.drawer-stem :deep(p:first-child) {
  margin-top: 0;
}
.drawer-stem :deep(p:last-child) {
  margin-bottom: 0;
}
.drawer-stem :deep(pre) {
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 12px;
  overflow-x: auto;
}
.drawer-stem :deep(code) {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}

.drawer-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--line);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--surface);
}

/* 单选 / 多选卡片 */
.q-options-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.multi-hint {
  font-size: 12.5px;
  margin-bottom: 2px;
}
.option-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 16px;
  border: 1px solid var(--line);
  background: var(--surface);
  cursor: pointer;
  transition: all 0.15s ease;
  user-select: none;
}
.option-item:hover:not(.disabled) {
  background: var(--bg);
  border-color: var(--ink);
}
.option-item.selected {
  background: var(--bg);
  border-color: var(--ink);
  border-left: 3px solid var(--ink);
  font-weight: 600;
}
.option-item.disabled {
  cursor: default;
  opacity: 0.88;
}
.opt-check {
  width: 18px;
  height: 18px;
  border: 1.5px solid var(--line);
  background: #ffffff;
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.option-item.selected .opt-check {
  background: var(--ink);
  border-color: var(--ink);
}
.opt-label {
  width: 24px;
  height: 24px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.option-item.selected .opt-label {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
}
.opt-text {
  flex: 1;
  line-height: 1.5;
}

.drawer-inline-save {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}

/* 判断题二选一卡片 */
.judge-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.judge-card {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 18px 16px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  border-radius: 0;
  transition: all 0.15s ease;
}
.judge-card:hover:not(:disabled) {
  border-color: var(--ink);
  background: var(--bg);
}
.judge-card.selected {
  border-color: var(--ink);
  background: var(--ink);
  color: #ffffff;
}
.judge-card:disabled {
  cursor: default;
  opacity: 0.85;
}
.judge-symbol {
  font-size: 17px;
  font-weight: 700;
}

/* 填空题编辑区 */
.fill-editor-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fill-input-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-secondary);
}
.fill-input-row {
  display: flex;
  gap: 10px;
}
.fill-input {
  flex: 1;
  height: 38px;
  font-size: 14px;
  border-radius: 0;
}

.answer-feedback-box {
  margin-top: 20px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  background: var(--surface);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.feedback-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
}
.feedback-std {
  font-size: 12.5px;
  color: var(--ink-secondary);
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}
.feedback-std strong {
  color: var(--ink);
}
</style>
