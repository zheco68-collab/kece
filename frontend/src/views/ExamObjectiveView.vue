<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { examApi, type ExamSessionQuestion, type SessionEnter } from '@/api/exam';
import { renderMarkdown } from '@/utils/markdown';
import { OBJECTIVE_TYPE_LABELS, OBJECTIVE_TYPE_TONE_MAP } from '@/utils/questionMeta';
import { useToast } from '@/composables/useToast';
import { useCountdown } from '@/shared/composables/useCountdown';
import {
  isObjectiveOptionSelected,
  objectiveOptionLetter,
  objectiveOptionText,
  updateObjectiveAnswer,
} from '@/features/questions/lib/objectiveAnswer';

const route = useRoute();
const router = useRouter();
const { success, error: toastError } = useToast();

const examId = computed(() => Number(route.params.id));
const enter = ref<SessionEnter | null>(null);
const loading = ref(true);
const saving = ref(false);
const saveSuccess = ref(false);

// 用户的作答映射: key = examQuestionId, value = 答案字符串
const userAnswers = ref<Record<number, string>>({});

// 按 examQuestionId 索引的分题防抖定时器，防止快速切题时互相覆盖丢答案
const autoSaveTimers = new Map<number, number>();
const pendingAnswers = new Map<number, string>();
let activeSaveRequests = 0;
let saveSuccessTimer: number | null = null;

const {
  remainingSec,
  isUrgent,
  start: startCountdown,
} = useCountdown({
  onExpire: () => {
    if (enter.value) enter.value.status = 'ENDED';
  },
});

const formattedRemaining = computed(() => {
  if (remainingSec.value === null) return '--:--';
  if (remainingSec.value <= 0) return '00:00 (已截止)';
  const h = Math.floor(remainingSec.value / 3600);
  const m = Math.floor((remainingSec.value % 3600) / 60);
  const s = remainingSec.value % 60;
  if (h > 0) {
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
});

const activeTypeFilter = ref<string>('ALL');

interface ObjectiveSection {
  type: 'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL';
  title: string;
  questions: ExamSessionQuestion[];
  fullScore: number;
  answeredCount: number;
}

const objectiveQuestions = computed<ExamSessionQuestion[]>(() => {
  if (!enter.value) return [];
  return enter.value.questions.filter((q) => q.questionType === 'OBJECTIVE');
});

const sections = computed<ObjectiveSection[]>(() => {
  const groups: Record<'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL', ExamSessionQuestion[]> = {
    SINGLE: [],
    JUDGE: [],
    MULTIPLE: [],
    FILL: [],
  };
  for (const q of objectiveQuestions.value) {
    if (q.objectiveType === 'JUDGE') groups.JUDGE.push(q);
    else if (q.objectiveType === 'MULTIPLE') groups.MULTIPLE.push(q);
    else if (q.objectiveType === 'FILL') groups.FILL.push(q);
    else groups.SINGLE.push(q);
  }

  const result: ObjectiveSection[] = [];
  const configs: Array<{ type: 'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL'; title: string }> = [
    { type: 'SINGLE', title: '单项选择题' },
    { type: 'JUDGE', title: '判断题' },
    { type: 'MULTIPLE', title: '多项选择题' },
    { type: 'FILL', title: '填空题' },
  ];

  const chineseNums = ['一', '二', '三', '四'];
  let idx = 0;
  for (const c of configs) {
    const list = groups[c.type];
    if (list.length > 0) {
      const full = list.reduce((sum, item) => sum + (item.score || 0), 0);
      const answered = list.filter(
        (item) => (userAnswers.value[item.id]?.trim().length ?? 0) > 0,
      ).length;
      result.push({
        type: c.type,
        title: `${chineseNums[idx] || idx + 1}、${c.title}`,
        questions: list,
        fullScore: full,
        answeredCount: answered,
      });
      idx++;
    }
  }
  return result;
});

const filteredSections = computed(() => {
  if (activeTypeFilter.value === 'ALL') return sections.value;
  return sections.value.filter((s) => s.type === activeTypeFilter.value);
});

const totalFullScore = computed(() =>
  objectiveQuestions.value.reduce((sum, q) => sum + (q.score || 0), 0),
);

const answeredCount = computed(() => {
  return Object.values(userAnswers.value).filter((v) => v && v.trim().length > 0).length;
});

function selectOption(qId: number, letter: string, type: string | null) {
  if (enter.value?.status === 'ENDED') return;

  userAnswers.value[qId] = updateObjectiveAnswer(userAnswers.value[qId], letter, type);

  triggerAutoSave(qId, userAnswers.value[qId]);
}

function onFillInput(qId: number) {
  triggerAutoSave(qId, userAnswers.value[qId] || '');
}

async function persistQuestionAnswer(qId: number, answerVal: string) {
  if (enter.value?.status === 'ENDED') return;
  activeSaveRequests += 1;
  saving.value = true;
  try {
    await examApi.saveAnswer(examId.value, {
      examQuestionId: qId,
      answer: answerVal,
    });
    saveSuccess.value = true;
    if (saveSuccessTimer !== null) window.clearTimeout(saveSuccessTimer);
    saveSuccessTimer = window.setTimeout(() => {
      saveSuccess.value = false;
      saveSuccessTimer = null;
    }, 1500);
  } catch {
    // 容错静默，下次保存或手动保存会再触发
  } finally {
    activeSaveRequests = Math.max(0, activeSaveRequests - 1);
    if (activeSaveRequests === 0) {
      saving.value = false;
    }
  }
}

function triggerAutoSave(qId: number, answerVal: string) {
  if (enter.value?.status === 'ENDED') return;
  const existingTimer = autoSaveTimers.get(qId);
  if (existingTimer !== undefined) {
    window.clearTimeout(existingTimer);
  }
  pendingAnswers.set(qId, answerVal);
  const timerId = window.setTimeout(() => {
    autoSaveTimers.delete(qId);
    const latestAnswer = pendingAnswers.get(qId) ?? answerVal;
    pendingAnswers.delete(qId);
    void persistQuestionAnswer(qId, latestAnswer);
  }, 500);
  autoSaveTimers.set(qId, timerId);
}

function flushPendingAutoSaves() {
  for (const [qId, timerId] of autoSaveTimers.entries()) {
    window.clearTimeout(timerId);
    autoSaveTimers.delete(qId);
    const pendingVal = pendingAnswers.get(qId);
    pendingAnswers.delete(qId);
    if (pendingVal !== undefined && enter.value?.status !== 'ENDED') {
      void examApi
        .saveAnswer(examId.value, {
          examQuestionId: qId,
          answer: pendingVal,
        })
        .catch(() => {});
    }
  }
}

function clearPendingAutoSaves() {
  for (const timerId of autoSaveTimers.values()) {
    window.clearTimeout(timerId);
  }
  autoSaveTimers.clear();
  pendingAnswers.clear();
}

async function loadData() {
  loading.value = true;
  try {
    const res = await examApi.enter(examId.value);
    enter.value = res;
    startCountdown(res.status === 'ENDED' ? null : res.remainingSeconds);

    // 恢复用户历史填写的答案
    const initialAnswers: Record<number, string> = {};
    if (res.myAnswers) {
      for (const q of res.questions) {
        if (q.questionType === 'OBJECTIVE') {
          const ans = res.myAnswers[q.id]?.answerContent;
          if (ans) {
            initialAnswers[q.id] = ans;
          }
        }
      }
    }
    userAnswers.value = initialAnswers;
  } catch (e) {
    toastError(e instanceof Error ? e.message : '加载客观题失败');
  } finally {
    loading.value = false;
  }
}

async function handleSaveAll() {
  if (saving.value) return;
  if (enter.value?.status === 'ENDED') {
    toastError('考试已结束，无法继续保存');
    return;
  }

  clearPendingAutoSaves();
  activeSaveRequests += 1;
  saving.value = true;
  try {
    for (const [qIdStr, ans] of Object.entries(userAnswers.value)) {
      if (ans) {
        await examApi.saveAnswer(examId.value, {
          examQuestionId: Number(qIdStr),
          answer: ans,
        });
      }
    }
    success('所有客观题答案已保存成功！');
  } catch (e) {
    toastError(e instanceof Error ? e.message : '保存客观题失败');
  } finally {
    activeSaveRequests = Math.max(0, activeSaveRequests - 1);
    if (activeSaveRequests === 0) {
      saving.value = false;
    }
  }
}

onMounted(async () => {
  await loadData();
});

onBeforeUnmount(() => {
  flushPendingAutoSaves();
  if (saveSuccessTimer !== null) {
    window.clearTimeout(saveSuccessTimer);
    saveSuccessTimer = null;
  }
});
</script>

<template>
  <div class="practice-objective-page container">
    <!-- 头部横幅 -->
    <header class="obj-header card">
      <div class="header-top">
        <button type="button" class="btn-back" @click="router.push(`/exams/${examId}`)">
          ← 返回考试题目列表
        </button>
        <div class="header-right">
          <span class="code-pill mono">赛制: {{ enter?.mode }}</span>
          <div
            v-if="enter?.durationMinutes"
            class="cd-box mono tabular-nums"
            :class="{ urgent: isUrgent }"
          >
            <span class="cd-time">⏱ {{ formattedRemaining }}</span>
          </div>
        </div>
      </div>

      <div class="title-row">
        <div class="title-left">
          <span class="header-tag mono">OBJECTIVE SHEET</span>
          <h1 class="page-title">{{ enter?.examTitle }} · 客观题集中答卷</h1>
        </div>
        <div class="progress-pill tabular-nums">
          已答: <strong>{{ answeredCount }}</strong> / {{ objectiveQuestions.length }} 题 · 总分
          <strong>{{ totalFullScore }}</strong> 分
        </div>
      </div>
    </header>

    <!-- 题型分类过滤导航 -->
    <div v-if="sections.length > 1" class="section-nav-pills card">
      <button
        class="sec-nav-btn tabular-nums"
        :class="{ active: activeTypeFilter === 'ALL' }"
        type="button"
        @click="activeTypeFilter = 'ALL'"
      >
        全部题型 ({{ objectiveQuestions.length }})
      </button>
      <button
        v-for="s in sections"
        :key="s.type"
        class="sec-nav-btn tabular-nums"
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
            <span class="sec-meta tabular-nums"
              >共 {{ sec.questions.length }} 题 · 小计 {{ sec.fullScore }} 分</span
            >
          </div>
          <span class="sec-progress tabular-nums">
            完成进度: <strong>{{ sec.answeredCount }}</strong> / {{ sec.questions.length }} 题
          </span>
        </div>

        <div
          v-for="(q, idx) in sec.questions"
          :key="q.id"
          class="question-card card"
          :class="{
            answered: (userAnswers[q.id]?.trim().length ?? 0) > 0,
          }"
        >
          <div class="q-header-row">
            <div class="q-left-tags">
              <span class="q-index-box mono tabular-nums">#{{ idx + 1 }}</span>
              <span
                class="q-type-tag hud-pill"
                :class="[
                  q.objectiveType ? q.objectiveType.toLowerCase() : 'single',
                  `tone-${OBJECTIVE_TYPE_TONE_MAP[q.objectiveType ?? 'SINGLE']}`,
                ]"
              >
                {{ OBJECTIVE_TYPE_LABELS[q.objectiveType ?? 'SINGLE'] }}
              </span>
              <span class="q-score-tag tabular-nums">{{ q.score }} 分</span>
            </div>

            <div v-if="(userAnswers[q.id]?.trim().length ?? 0) > 0" class="grade-badge pass">
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
              :aria-pressed="userAnswers[q.id] === '对'"
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
              :aria-pressed="userAnswers[q.id] === '错'"
              :disabled="enter?.status === 'ENDED'"
              @click="selectOption(q.id, '错', 'JUDGE')"
            >
              <span class="judge-symbol">✕</span>
              <span class="judge-text">错（错误）</span>
            </button>
          </div>

          <!-- 选项作答区域 (单选 / 多选) -->
          <div
            v-else-if="q.objectiveType === 'SINGLE' || q.objectiveType === 'MULTIPLE'"
            class="options-container"
          >
            <button
              v-for="(opt, optIdx) in q.options || []"
              :key="optIdx"
              type="button"
              class="option-row"
              :class="{
                active: isObjectiveOptionSelected(
                  userAnswers[q.id],
                  objectiveOptionLetter(optIdx),
                  q.objectiveType,
                ),
              }"
              :aria-pressed="
                isObjectiveOptionSelected(
                  userAnswers[q.id],
                  objectiveOptionLetter(optIdx),
                  q.objectiveType,
                )
              "
              :disabled="enter?.status === 'ENDED'"
              @click="selectOption(q.id, objectiveOptionLetter(optIdx), q.objectiveType)"
            >
              <span class="letter-box mono">
                {{ objectiveOptionLetter(optIdx) }}
              </span>
              <span class="option-text">
                {{ objectiveOptionText(opt) }}
              </span>
            </button>
          </div>

          <!-- 填空题作答区域 -->
          <div v-else-if="q.objectiveType === 'FILL'" class="fill-container">
            <label :for="`exam-fill-input-${q.id}`" class="sr-only">
              第 {{ idx + 1 }} 题填空答案
            </label>
            <input
              :id="`exam-fill-input-${q.id}`"
              v-model="userAnswers[q.id]"
              type="text"
              class="fill-input"
              :aria-label="`第 ${idx + 1} 题填空答案`"
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
      <div class="bar-left tabular-nums">
        <span
          >当前作答进度：<strong>{{ answeredCount }} / {{ objectiveQuestions.length }}</strong>
          题</span
        >
        <span v-if="saving" class="bar-score">正在保存答案…</span>
        <span v-else-if="saveSuccess" class="bar-score">✓ 答案已保存</span>
      </div>
      <div class="bar-right">
        <button type="button" class="btn btn-back-outline" @click="router.push(`/exams/${examId}`)">
          返回题目清单
        </button>
        <button
          type="button"
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
  background: var(--surface-white);
  border: 1px solid var(--line-strong);
  border-radius: 0;
  padding: 20px 24px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
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
  color: var(--ink-secondary);
  cursor: pointer;
  padding: 0;
}

.btn-back:hover {
  color: var(--ink);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.code-pill {
  background: var(--kece-surface-muted);
  border: 1px solid var(--line-strong);
  padding: 2px 8px;
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 700;
}

.cd-box {
  background: var(--line-strong);
  color: var(--surface-white);
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 800;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.cd-box.urgent {
  background: var(--kece-danger);
}

.title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
}

.header-tag {
  background: var(--kece-accent-soft);
  color: var(--kece-accent-ink);
  border: 1px solid rgba(19, 121, 105, 0.35);
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  font-family: var(--font-mono);
  margin-bottom: 4px;
  display: inline-block;
}

.page-title {
  font-size: 20px;
  font-weight: 800;
  color: var(--ink);
  margin: 0;
}

.progress-pill {
  font-size: 13px;
  color: var(--ink-secondary);
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  padding: 6px 12px;
  font-variant-numeric: tabular-nums;
}

.questions-sheet {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.question-card {
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  padding: 24px;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.question-card:hover {
  border-color: var(--line-strong);
}

.question-card.answered {
  border-color: var(--kece-accent);
  box-shadow: inset 0 2px 0 var(--kece-accent);
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
  background: var(--line-strong);
  color: var(--surface-white);
  font-size: 12px;
  font-weight: 900;
  font-family: var(--font-mono);
  display: flex;
  align-items: center;
  justify-content: center;
  font-variant-numeric: tabular-nums;
}

.q-type-tag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
}

.q-type-tag.single {
  background: var(--kece-accent-soft);
  border: 1px solid rgba(19, 121, 105, 0.35);
  color: var(--kece-accent-ink);
}
.q-type-tag.multiple {
  background: var(--kece-warning-soft);
  border: 1px solid rgba(146, 64, 14, 0.32);
  color: var(--kece-warning);
}
.q-type-tag.judge {
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  color: var(--ink-secondary);
}
.q-type-tag.fill {
  background: var(--kece-danger-soft);
  border: 1px solid rgba(180, 35, 61, 0.32);
  color: var(--kece-danger);
}

.q-score-tag {
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  font-size: 11px;
  font-weight: 700;
  padding: 2px 6px;
  font-variant-numeric: tabular-nums;
}

.grade-badge {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
}

.grade-badge.pass {
  background: var(--kece-success-soft);
  color: var(--kece-success);
  border: 1px solid rgba(22, 121, 79, 0.32);
}

.q-content {
  font-size: 15px;
  line-height: 1.6;
  color: var(--ink);
  margin-bottom: 20px;
}

.options-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.option-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  border: 1px solid var(--kece-border);
  background: var(--kece-surface-muted);
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.option-row:hover:not(:disabled) {
  background: var(--surface-hover);
  border-color: var(--line-strong);
}

.option-row.active {
  background: var(--surface-white);
  border-color: var(--kece-accent);
  box-shadow: 2px 2px 0 var(--kece-accent);
}

.option-row:disabled {
  opacity: 1;
  cursor: not-allowed;
}

.letter-box {
  width: 32px;
  height: 32px;
  border: 1px solid var(--line-strong);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 900;
  font-family: var(--font-mono);
  font-size: 14px;
  background: var(--surface-white);
  color: var(--ink);
  flex-shrink: 0;
  transition:
    background-color 0.15s ease,
    color 0.15s ease,
    border-color 0.15s ease;
}

.option-row.active .letter-box {
  background: var(--kece-accent);
  border-color: var(--kece-accent);
  color: var(--surface-white);
}

.option-text {
  font-size: 14px;
  color: var(--ink);
  flex: 1;
}

.fill-container {
  margin-top: 10px;
}

.fill-input {
  width: 100%;
  max-width: 480px;
  padding: 10px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 0;
  font-size: 14px;
  font-family: var(--font-mono);
  background: var(--surface-white);
  color: var(--ink);
  box-sizing: border-box;
}

.fill-input:focus-visible {
  border-color: var(--kece-accent);
  outline: 2px solid var(--kece-accent);
  outline-offset: 2px;
}

.fill-input:disabled {
  opacity: 1;
  background: var(--kece-surface-muted);
  color: var(--ink-tertiary);
  border-color: var(--kece-border);
  cursor: not-allowed;
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
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease,
    box-shadow 0.15s ease;
}

.judge-btn:hover:not(:disabled) {
  background: var(--surface-hover);
  border-color: var(--line-strong);
}

.judge-btn.active {
  background: var(--kece-accent);
  color: var(--surface-white);
  border-color: var(--kece-accent);
  box-shadow: 2px 2px 0 var(--line-strong);
}

.judge-btn:disabled {
  opacity: 1;
  background: var(--kece-surface-muted);
  border-color: var(--kece-border);
  color: var(--ink-tertiary);
  cursor: not-allowed;
}

.judge-btn.active:disabled {
  opacity: 1;
  background: #475569;
  border-color: #475569;
  color: #f8fafc;
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
  background: var(--surface-white);
  border-top: 2px solid var(--line-strong);
  padding: 14px 40px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.08);
  z-index: 100;
}

.bar-left {
  font-size: 14px;
  color: var(--ink);
  display: flex;
  gap: 20px;
  align-items: center;
  font-variant-numeric: tabular-nums;
}

.bar-score {
  color: var(--kece-success);
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
  border: 1px solid var(--line-strong);
}

.btn-back-outline {
  background: var(--surface-white);
  color: var(--ink);
}

.btn-submit-objective {
  background: var(--kece-accent);
  color: var(--surface-white);
  border-color: var(--kece-accent);
}

.btn-submit-objective:hover:not(:disabled) {
  background: var(--kece-accent-hover);
  border-color: var(--kece-accent-hover);
}

.btn-submit-objective:disabled {
  opacity: 1;
  background: #475569;
  border-color: #475569;
  color: #f8fafc;
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
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  overflow-x: auto;
}

.sec-nav-btn {
  background: transparent;
  border: 1px solid transparent;
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 700;
  padding: 6px 14px;
  cursor: pointer;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.sec-nav-btn:hover {
  background: var(--kece-surface-muted);
  color: var(--ink);
}

.sec-nav-btn.active {
  background: var(--kece-accent-soft);
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink);
}

.section-block {
  margin-bottom: 30px;
}

.sec-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  box-shadow: inset 0 2px 0 var(--line-strong);
  padding: 12px 18px;
  margin-bottom: 14px;
}

.sec-title {
  font-size: 16px;
  font-weight: 800;
  margin: 0;
  color: var(--ink);
}

.sec-meta {
  font-size: 12px;
  color: var(--ink-tertiary);
  margin-left: 10px;
  font-variant-numeric: tabular-nums;
}

.sec-progress {
  font-size: 13px;
  color: var(--ink-secondary);
  font-variant-numeric: tabular-nums;
}

.sec-progress strong {
  color: var(--kece-success);
}

@media (prefers-reduced-motion: reduce) {
  .question-card,
  .option-row,
  .letter-box,
  .judge-btn {
    transition: none;
  }
}
</style>
