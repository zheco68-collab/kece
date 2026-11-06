<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { examApi, type ExamSessionQuestion, type SessionEnter } from '@/api/exam'
import { renderMarkdown } from '@/utils/markdown'
import { useToast } from '@/composables/useToast'
import { useCountdown } from '@/shared/composables/useCountdown'
import {
  isObjectiveOptionSelected,
  objectiveOptionLetter,
  objectiveOptionText,
  updateObjectiveAnswer,
} from '@/features/questions/lib/objectiveAnswer'

const route = useRoute()
const router = useRouter()
const { success, error: toastError } = useToast()

const examId = computed(() => Number(route.params.id))
const enter = ref<SessionEnter | null>(null)
const loading = ref(true)
const saving = ref(false)
const saveSuccess = ref(false)

// 用户的作答映射: key = examQuestionId, value = 答案字符串
const userAnswers = ref<Record<number, string>>({})

let autoSaveTimer: number | null = null
const { remainingSec, isUrgent, start: startCountdown } = useCountdown({
  onExpire: () => {
    if (enter.value) enter.value.status = 'ENDED'
  },
})

const formattedRemaining = computed(() => {
  if (remainingSec.value === null) return '--:--'
  if (remainingSec.value <= 0) return '00:00 (已截止)'
  const h = Math.floor(remainingSec.value / 3600)
  const m = Math.floor((remainingSec.value % 3600) / 60)
  const s = remainingSec.value % 60
  if (h > 0) {
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  }
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})

const activeTypeFilter = ref<string>('ALL')

interface ObjectiveSection {
  type: 'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL'
  title: string
  questions: ExamSessionQuestion[]
  fullScore: number
  answeredCount: number
}

const objectiveQuestions = computed<ExamSessionQuestion[]>(() => {
  if (!enter.value) return []
  return enter.value.questions.filter((q) => q.questionType === 'OBJECTIVE')
})

const sections = computed<ObjectiveSection[]>(() => {
  const groups: Record<'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL', ExamSessionQuestion[]> = {
    SINGLE: [],
    JUDGE: [],
    MULTIPLE: [],
    FILL: [],
  }
  for (const q of objectiveQuestions.value) {
    if (q.objectiveType === 'JUDGE') groups.JUDGE.push(q)
    else if (q.objectiveType === 'MULTIPLE') groups.MULTIPLE.push(q)
    else if (q.objectiveType === 'FILL') groups.FILL.push(q)
    else groups.SINGLE.push(q)
  }

  const result: ObjectiveSection[] = []
  const configs: Array<{ type: 'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL'; title: string }> = [
    { type: 'SINGLE', title: '单项选择题' },
    { type: 'JUDGE', title: '判断题' },
    { type: 'MULTIPLE', title: '多项选择题' },
    { type: 'FILL', title: '填空题' },
  ]

  const chineseNums = ['一', '二', '三', '四']
  let idx = 0
  for (const c of configs) {
    const list = groups[c.type]
    if (list.length > 0) {
      const full = list.reduce((sum, item) => sum + (item.score || 0), 0)
      const answered = list.filter((item) => userAnswers.value[item.id]?.trim().length > 0).length
      result.push({
        type: c.type,
        title: `${chineseNums[idx] || idx + 1}、${c.title}`,
        questions: list,
        fullScore: full,
        answeredCount: answered,
      })
      idx++
    }
  }
  return result
})

const filteredSections = computed(() => {
  if (activeTypeFilter.value === 'ALL') return sections.value
  return sections.value.filter((s) => s.type === activeTypeFilter.value)
})

const totalFullScore = computed(() =>
  objectiveQuestions.value.reduce((sum, q) => sum + (q.score || 0), 0),
)

const answeredCount = computed(() => {
  return Object.values(userAnswers.value).filter((v) => v && v.trim().length > 0).length
})

function selectOption(qId: number, letter: string, type: string | null) {
  if (enter.value?.status === 'ENDED') return

  userAnswers.value[qId] = updateObjectiveAnswer(userAnswers.value[qId], letter, type)

  triggerAutoSave(qId, userAnswers.value[qId])
}

function onFillInput(qId: number) {
  triggerAutoSave(qId, userAnswers.value[qId] || '')
}

function triggerAutoSave(qId: number, answerVal: string) {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = window.setTimeout(async () => {
    try {
      saving.value = true
      await examApi.saveAnswer(examId.value, {
        examQuestionId: qId,
        answer: answerVal,
      })
      saveSuccess.value = true
      setTimeout(() => {
        saveSuccess.value = false
      }, 1500)
    } catch {
      // 容错静默，下次保存会再触发
    } finally {
      saving.value = false
    }
  }, 500)
}

async function loadData() {
  loading.value = true
  try {
    const res = await examApi.enter(examId.value)
    enter.value = res
    startCountdown(res.status === 'ENDED' ? null : res.remainingSeconds)

    // 恢复用户历史填写的答案
    const initialAnswers: Record<number, string> = {}
    if (res.myAnswers) {
      for (const q of res.questions) {
        if (q.questionType === 'OBJECTIVE') {
          const ans = res.myAnswers[q.id]?.answerContent
          if (ans) {
            initialAnswers[q.id] = ans
          }
        }
      }
    }
    userAnswers.value = initialAnswers
  } catch (e) {
    toastError(e instanceof Error ? e.message : '加载客观题失败')
  } finally {
    loading.value = false
  }
}

async function handleSaveAll() {
  if (saving.value) return
  if (enter.value?.status === 'ENDED') {
    toastError('考试已结束，无法继续保存')
    return
  }

  saving.value = true
  try {
    for (const [qIdStr, ans] of Object.entries(userAnswers.value)) {
      if (ans) {
        await examApi.saveAnswer(examId.value, {
          examQuestionId: Number(qIdStr),
          answer: ans,
        })
      }
    }
    success('所有客观题答案已保存成功！')
  } catch (e) {
    toastError(e instanceof Error ? e.message : '保存客观题失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadData()
})

onBeforeUnmount(() => {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
})
</script>

<template>
  <div class="practice-objective-page container">
    <!-- 头部横幅 -->
    <header class="obj-header card">
      <div class="header-top">
        <button class="btn-back" @click="router.push(`/exams/${examId}`)">
          ← 返回考试题目列表
        </button>
        <div class="header-right">
          <span class="code-pill">赛制: {{ enter?.mode }}</span>
          <div v-if="enter?.durationMinutes" class="cd-box" :class="{ urgent: isUrgent }">
            <span class="cd-time">⏱ {{ formattedRemaining }}</span>
          </div>
        </div>
      </div>

      <div class="title-row">
        <div class="title-left">
          <span class="header-tag">OBJECTIVE SHEET</span>
          <h1 class="page-title">{{ enter?.examTitle }} · 客观题集中答卷</h1>
        </div>
        <div class="progress-pill">
          已答: <strong>{{ answeredCount }}</strong> / {{ objectiveQuestions.length }} 题 ·
          总分 <strong>{{ totalFullScore }}</strong> 分
        </div>
      </div>
    </header>

    <!-- 题型分类过滤导航 -->
    <div v-if="sections.length > 1" class="section-nav-pills card">
      <button
        class="sec-nav-btn"
        :class="{ active: activeTypeFilter === 'ALL' }"
        type="button"
        @click="activeTypeFilter = 'ALL'"
      >
        全部题型 ({{ objectiveQuestions.length }})
      </button>
      <button
        v-for="s in sections"
        :key="s.type"
        class="sec-nav-btn"
        :class="{ active: activeTypeFilter === s.type }"
        type="button"
        @click="activeTypeFilter = s.type"
      >
        {{ s.title.split('、')[1] }} ({{ s.answeredCount }}/{{ s.questions.length }})
      </button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state card">
      <div class="loading-spinner"></div>
      <p class="muted">正在加载客观题试卷…</p>
    </div>

    <!-- 题目列表按大题类型分组排布 -->
    <div v-else class="questions-sheet">
      <div v-for="sec in filteredSections" :key="sec.type" class="section-block">
        <div class="sec-banner card">
          <div class="sec-left">
            <h2 class="sec-title">{{ sec.title }}</h2>
            <span class="sec-meta">共 {{ sec.questions.length }} 题 · 小计 {{ sec.fullScore }} 分</span>
          </div>
          <span class="sec-progress">
            完成进度: <strong>{{ sec.answeredCount }}</strong> / {{ sec.questions.length }} 题
          </span>
        </div>

        <div
          v-for="(q, idx) in sec.questions"
          :key="q.id"
          class="question-card card"
          :class="{
            answered: userAnswers[q.id]?.trim().length > 0
          }"
        >
          <div class="q-header-row">
            <div class="q-left-tags">
              <span class="q-index-box">#{{ idx + 1 }}</span>
              <span class="q-type-tag" :class="q.objectiveType ? q.objectiveType.toLowerCase() : 'single'">
                {{ q.objectiveType === 'SINGLE' ? '单选题' : q.objectiveType === 'MULTIPLE' ? '多选题' : q.objectiveType === 'JUDGE' ? '判断题' : '填空题' }}
              </span>
              <span class="q-score-tag">{{ q.score }} 分</span>
            </div>

            <div v-if="userAnswers[q.id]?.trim().length > 0" class="grade-badge pass">
              <span>✓ 已作答</span>
            </div>
          </div>

          <!-- 题干 Markdown 渲染 -->
          <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown sanitizes question Markdown with DOMPurify. -->
          <div class="q-content markdown-body" v-html="renderMarkdown(q.content || '')"></div>

          <!-- 判断题作答区域 (仅 对 / 错) -->
          <div v-if="q.objectiveType === 'JUDGE'" class="judge-container">
            <button
              type="button"
              class="judge-btn"
              :class="{ active: userAnswers[q.id] === '对' }"
              :disabled="enter?.status === 'ENDED'"
              @click="selectOption(q.id, '对', 'JUDGE')"
            >
              <span class="judge-symbol">✓</span>
              <span class="judge-text">对（正确）</span>
            </button>
            <button
              type="button"
              class="judge-btn"
              :class="{ active: userAnswers[q.id] === '错' }"
              :disabled="enter?.status === 'ENDED'"
              @click="selectOption(q.id, '错', 'JUDGE')"
            >
              <span class="judge-symbol">✕</span>
              <span class="judge-text">错（错误）</span>
            </button>
          </div>

          <!-- 选项作答区域 (单选 / 多选) -->
          <div v-else-if="q.objectiveType === 'SINGLE' || q.objectiveType === 'MULTIPLE'" class="options-container">
            <div
              v-for="(opt, optIdx) in (q.options || [])"
              :key="optIdx"
              class="option-row"
              :class="{ active: isObjectiveOptionSelected(userAnswers[q.id], objectiveOptionLetter(optIdx), q.objectiveType) }"
              @click="selectOption(q.id, objectiveOptionLetter(optIdx), q.objectiveType)"
            >
              <div class="letter-box">
                {{ objectiveOptionLetter(optIdx) }}
              </div>
              <div class="option-text">
                {{ objectiveOptionText(opt) }}
              </div>
            </div>
          </div>

          <!-- 填空题作答区域 -->
          <div v-else-if="q.objectiveType === 'FILL'" class="fill-container">
            <input
              v-model="userAnswers[q.id]"
              type="text"
              class="fill-input"
              placeholder="在此键入填空答案…"
              :disabled="enter?.status === 'ENDED'"
              @input="onFillInput(q.id)"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 底部悬浮操作栏 -->
    <div class="bottom-action-bar">
      <div class="bar-left">
        <span>当前作答进度：<strong>{{ answeredCount }} / {{ objectiveQuestions.length }}</strong> 题</span>
        <span v-if="saving" class="bar-score">正在保存答案…</span>
        <span v-else-if="saveSuccess" class="bar-score">✓ 答案已保存</span>
      </div>
      <div class="bar-right">
        <button class="btn btn-back-outline" @click="router.push(`/exams/${examId}`)">
          返回题目清单
        </button>
        <button
          class="btn btn-submit-objective"
          :disabled="saving || enter?.status === 'ENDED'"
          @click="handleSaveAll"
        >
          {{
            saving
              ? '正在保存…'
              : enter?.status === 'ENDED'
                ? '考试已截止归档（只读）'
                : '保存全部客观题答案 ✓'
          }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.practice-objective-page {
  padding-top: 24px;
  padding-bottom: 90px;
  max-width: 960px;
  margin: 0 auto;
}

.obj-header {
  background: var(--surface);
  border: 1px solid #1e1e1e;
  border-radius: 0;
  padding: 20px 24px;
  margin-bottom: 24px;
  box-shadow: 4px 4px 0 #1e1e1e;
}

.header-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.btn-back {
  background: transparent;
  border: none;
  font-size: 13px;
  font-weight: 700;
  color: #495057;
  cursor: pointer;
  padding: 0;
}

.btn-back:hover {
  color: #1e1e1e;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.code-pill {
  background: #f1f3f5;
  border: 1px solid #1e1e1e;
  padding: 2px 8px;
  font-family: monospace;
  font-size: 12px;
  font-weight: 700;
}

.cd-box {
  background: #1e1e1e;
  color: #fff;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 800;
  font-family: monospace;
}

.cd-box.urgent {
  background: #c92a2a;
}

.title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
}

.header-tag {
  background: #7048e8;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  font-family: monospace;
  margin-bottom: 4px;
  display: inline-block;
}

.page-title {
  font-size: 20px;
  font-weight: 800;
  color: #1e1e1e;
  margin: 0;
}

.progress-pill {
  font-size: 13px;
  color: #495057;
  background: #f8f9fa;
  border: 1px solid #dee2e6;
  padding: 6px 12px;
}

.questions-sheet {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.question-card {
  background: var(--surface);
  border: 1px solid #ced4da;
  border-radius: 0;
  padding: 24px;
  transition: all 0.15s ease;
}

.question-card:hover {
  border-color: #1e1e1e;
}

.question-card.answered {
  border-left: 4px solid #7048e8;
}

.q-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.q-left-tags {
  display: flex;
  align-items: center;
  gap: 8px;
}

.q-index-box {
  width: 26px;
  height: 26px;
  background: #1e1e1e;
  color: #fff;
  font-size: 12px;
  font-weight: 900;
  font-family: monospace;
  display: flex;
  align-items: center;
  justify-content: center;
}

.q-type-tag {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  color: #fff;
}

.q-type-tag.single { background: #1971c2; }
.q-type-tag.multiple { background: #7048e8; }
.q-type-tag.judge { background: #0ca678; }
.q-type-tag.fill { background: #e67700; }

.q-score-tag {
  background: #f1f3f5;
  border: 1px solid #ced4da;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 6px;
}

.grade-badge {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
}

.grade-badge.pass {
  background: #ebfbee;
  color: #2b8a3e;
  border: 1px solid #b2f2bb;
}

.q-content {
  font-size: 15px;
  line-height: 1.6;
  color: #212529;
  margin-bottom: 20px;
}

.options-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  border: 1px solid #dee2e6;
  background: #fafafa;
  cursor: pointer;
  transition: all 0.15s ease;
}

.option-row:hover {
  background: #f1f3f5;
  border-color: #1e1e1e;
}

.option-row.active {
  background: #ffffff;
  border-color: #1e1e1e;
  box-shadow: 2px 2px 0 #1e1e1e;
}

.letter-box {
  width: 32px;
  height: 32px;
  border: 1px solid #1e1e1e;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 900;
  font-family: monospace;
  font-size: 14px;
  background: #ffffff;
  color: #1e1e1e;
  transition: all 0.15s ease;
}

.option-row.active .letter-box {
  background: #1e1e1e;
  color: #ffffff;
}

.option-text {
  font-size: 14px;
  color: #212529;
  flex: 1;
}

.fill-container {
  margin-top: 10px;
}

.fill-input {
  width: 100%;
  max-width: 480px;
  padding: 10px 14px;
  border: 1px solid #1e1e1e;
  border-radius: 0;
  font-size: 14px;
  font-family: monospace;
  outline: none;
  box-sizing: border-box;
}

.fill-input:focus {
  border-color: #1971c2;
  box-shadow: 2px 2px 0 #1971c2;
}

.judge-container {
  display: flex;
  gap: 16px;
  margin-top: 10px;
}

.judge-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 24px;
  background: #fafafa;
  border: 1px solid #dee2e6;
  border-radius: 0;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: #212529;
  transition: all 0.15s ease;
}

.judge-btn:hover:not(:disabled) {
  background: #f1f3f5;
  border-color: #1e1e1e;
}

.judge-btn.active {
  background: #1e1e1e;
  color: #ffffff;
  border-color: #1e1e1e;
  box-shadow: 2px 2px 0 #1e1e1e;
}

.judge-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.judge-symbol {
  font-size: 16px;
  font-weight: 900;
}

.bottom-action-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: #ffffff;
  border-top: 2px solid #1e1e1e;
  padding: 14px 40px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.08);
  z-index: 100;
}

.bar-left {
  font-size: 14px;
  color: #212529;
  display: flex;
  gap: 20px;
  align-items: center;
}

.bar-score {
  color: #2b8a3e;
  font-weight: 700;
}

.bar-right {
  display: flex;
  gap: 12px;
}

.btn {
  padding: 10px 22px;
  font-size: 14px;
  font-weight: 700;
  border-radius: 0;
  cursor: pointer;
  border: 1px solid #1e1e1e;
}

.btn-back-outline {
  background: #fff;
  color: #1e1e1e;
}

.btn-submit-objective {
  background: #7048e8;
  color: #fff;
  border-color: #5f3dc4;
}

.btn-submit-objective:hover:not(:disabled) {
  background: #5f3dc4;
}

.btn-submit-objective:disabled {
  background: #ced4da;
  border-color: #adb5bd;
  cursor: not-allowed;
}

.loading-state {
  padding: 60px;
  text-align: center;
}

/* 题型分段导航与大题横幅 */
.section-nav-pills {
  display: flex;
  gap: 8px;
  padding: 10px 16px;
  margin-bottom: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow-x: auto;
}

.sec-nav-btn {
  background: transparent;
  border: 1px solid transparent;
  color: #495057;
  font-size: 13px;
  font-weight: 700;
  padding: 6px 14px;
  cursor: pointer;
  white-space: nowrap;
}

.sec-nav-btn:hover {
  background: #f1f3f5;
  color: #1e1e1e;
}

.sec-nav-btn.active {
  background: #1e1e1e;
  border-color: #1e1e1e;
  color: #fff;
}

.section-block {
  margin-bottom: 30px;
}

.sec-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #f8f9fa;
  border: 1px solid var(--line);
  border-left: 4px solid #1e1e1e;
  padding: 12px 18px;
  margin-bottom: 14px;
}

.sec-title {
  font-size: 16px;
  font-weight: 800;
  margin: 0;
  color: #1e1e1e;
}

.sec-meta {
  font-size: 12px;
  color: #868e96;
  margin-left: 10px;
}

.sec-progress {
  font-size: 13px;
  color: #495057;
}

.sec-progress strong {
  color: #2b8a3e;
}
</style>
