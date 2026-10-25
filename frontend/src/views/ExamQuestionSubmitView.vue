<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { examApi, type ExamSessionQuestion, type SessionEnter } from '@/api/exam'
import { questionApi } from '@/api/question'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import CodeEditor from '@/components/CodeEditor.vue'
import type { Question } from '@/types'
import {
  DIFFICULTY_LABELS as difficultyLabels,
  FILENAMES,
  LANGUAGE_LABELS,
  SUPPORTED_LANGUAGES,
  TEMPLATES,
} from '@/utils/submission'

const route = useRoute()
const router = useRouter()
const { info, success, error: toastError } = useToast()
const { confirm } = useConfirm()

const examId = computed(() => Number(route.params.id))
const examQuestionId = computed(() => Number(route.params.examQuestionId))

const enter = ref<SessionEnter | null>(null)
const examQuestion = ref<ExamSessionQuestion | null>(null)
const question = ref<Question | null>(null)
const loading = ref(true)
const error = ref('')



const languages = computed(() =>
  (question.value?.languages ?? []).filter((l) => SUPPORTED_LANGUAGES.includes(l)).length
    ? (question.value?.languages ?? []).filter((l) => SUPPORTED_LANGUAGES.includes(l))
    : SUPPORTED_LANGUAGES,
)

const language = ref('cpp')
const codes = ref<Record<string, string>>({ ...TEMPLATES })
const submitting = ref(false)
const submitError = ref('')

function getDraftKey(lang: string) {
  return `kece:exam_code:${examId.value}_${examQuestionId.value}_${lang}`
}

function restoreDrafts() {
  for (const lang of SUPPORTED_LANGUAGES) {
    const saved = localStorage.getItem(getDraftKey(lang))
    if (saved) {
      codes.value[lang] = saved
    }
  }

  // 若后端已有暂存代码
  const mySaved = enter.value?.myAnswers?.[examQuestionId.value]
  if (mySaved && mySaved.answerContent) {
    try {
      const parsed = JSON.parse(mySaved.answerContent)
      if (typeof parsed === 'object' && parsed !== null) {
        const langKey = mySaved.language || 'cpp'
        const filename = FILENAMES[langKey] || Object.keys(parsed)[0]
        if (filename && parsed[filename]) {
          codes.value[langKey] = parsed[filename]
          language.value = langKey
        }
      }
    } catch {
      // ignore
    }
  }
}

watch(
  () => [codes.value[language.value], language.value],
  ([newCode, currLang]) => {
    if (newCode && currLang) {
      localStorage.setItem(getDraftKey(currLang), newCode)
    }
  },
)

async function resetTemplate() {
  const ok = await confirm({
    title: '重置代码',
    message: `确定要将当前 ${LANGUAGE_LABELS[language.value] ?? language.value} 代码重置为初始模板吗？当前编辑的内容将会丢失。`,
    confirmText: '确认重置',
    cancelText: '取消',
    danger: true,
  })
  if (ok) {
    codes.value[language.value] = TEMPLATES[language.value] ?? ''
    localStorage.removeItem(getDraftKey(language.value))
    info('已重置为初始模板')
  }
}

async function submit() {
  if (submitting.value) return
  if (enter.value?.status === 'ENDED') {
    toastError('当前考试已结束，无法继续提交')
    return
  }
  const codeContent = codes.value[language.value] ?? ''
  if (!codeContent.trim()) {
    submitError.value = '代码内容不能为空'
    return
  }
  submitting.value = true
  submitError.value = ''
  try {
    const codeMap = { [FILENAMES[language.value]]: codeContent }
    const codeJson = JSON.stringify(codeMap)

    // 1. 保存作答
    await examApi.saveAnswer(examId.value, {
      examQuestionId: examQuestionId.value,
      language: language.value,
      answer: codeJson,
    })

    // 2. 如果是 ACM 赛制，即时入队评测并跳转到结果页面
    if (enter.value?.mode === 'ACM') {
      const res = await examApi.submitQuestion(examId.value, examQuestionId.value)
      success('已提交评测！正在为您跳转到测评报告…')
      router.push(
        `/exams/${examId.value}/question/${examQuestionId.value}/result/${res.submissionId}`,
      )
    } else {
      // OI 赛制
      success('作答已暂存！OI 赛制将在统一交卷后评测')
      router.push(`/exams/${examId.value}`)
    }
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : '提交失败'
    toastError(submitError.value)
  } finally {
    submitting.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    submit()
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  try {
    const res = await examApi.enter(examId.value)
    enter.value = res

    const eq = res.questions.find((item) => item.id === examQuestionId.value)
    if (!eq) {
      error.value = '未找到该考试题目'
      return
    }
    examQuestion.value = eq

    question.value = await questionApi.detail(eq.questionId)
    if (question.value.languages?.length) {
      const preferred = question.value.languages.find((l) => SUPPORTED_LANGUAGES.includes(l))
      if (preferred) {
        language.value = preferred
      }
    }
    restoreDrafts()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载题目失败'
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="submit-page container">
    <!-- 加载与异常状态 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在准备代码编辑器与运行环境…</p>
    </div>

    <div v-else-if="error" class="state-card card">
      <div class="error-title">加载失败</div>
      <p class="error-msg">{{ error }}</p>
      <button
        class="btn btn-outline"
        @click="router.push(`/exams/${examId}/question/${examQuestionId}`)"
      >
        返回题目描述
      </button>
    </div>

    <!-- 提交主工作区 (直接照抄 PracticeQuestionSubmitView 标准架构与视觉样式) -->
    <template v-else-if="question && examQuestion">
      <!-- 顶栏导航卡片 -->
      <header class="submit-header card">
        <div class="header-left">
          <button
            class="btn-back"
            @click="router.push(`/exams/${examId}/question/${examQuestionId}`)"
          >
            <svg class="back-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            <span>返回题目描述</span>
          </button>
          <span class="sep">/</span>
          <div class="title-group">
            <span class="action-tag">提交代码</span>
            <span class="qid">#{{ question.id }}</span>
            <h1 class="qname">{{ examQuestion.title }}</h1>
          </div>
          <span v-if="question.difficulty" class="badge diff-pill" :class="`difficulty-${question.difficulty.toLowerCase()}`">
            {{ difficultyLabels[question.difficulty] }}
          </span>
        </div>

        <div class="header-right">
          <div class="meta-chip">
            <span class="chip-k">本题分值</span>
            <span class="chip-v">{{ examQuestion.score }} 分</span>
          </div>
          <div class="meta-chip">
            <span class="chip-k">赛制模式</span>
            <span class="chip-v">{{ enter?.mode }}</span>
          </div>
          <div class="shortcut-chip">快捷提交 <kbd>Ctrl</kbd> + <kbd>Enter</kbd></div>
        </div>
      </header>

      <!-- 编辑器卡片 -->
      <section class="editor-container card">
        <!-- 工具栏 -->
        <div class="editor-toolbar">
          <div class="lang-selector">
            <button
              v-for="l in languages"
              :key="l"
              class="lang-btn"
              :class="{ active: language === l }"
              type="button"
              @click="language = l"
            >
              {{ LANGUAGE_LABELS[l] ?? l }}
            </button>
          </div>

          <div class="toolbar-actions">
            <button class="btn-tool" type="button" @click="resetTemplate">
              <svg class="tool-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
                <path d="M21 3v5h-5" />
                <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
                <path d="M3 21v-5h5" />
              </svg>
              <span>重置模板</span>
            </button>
          </div>
        </div>

        <!-- Monaco 编辑器主体 -->
        <div class="monaco-wrapper">
          <CodeEditor
            v-model="codes[language]"
            :language="language"
            :filename="FILENAMES[language]"
            height="520px"
          />
        </div>

        <!-- 报错提示 -->
        <div v-if="submitError" class="submit-error">
          {{ submitError }}
        </div>

        <!-- 编辑器底部信息栏与提交按钮 -->
        <div class="editor-footer">
          <div class="footer-tip">
            <span class="tip-dot"></span>
            <span>代码自动保存在本地草稿，{{ enter?.mode === 'ACM' ? 'ACM 赛制点击提交即可即时评测并展示结果' : 'OI 赛制将保存本题作答并于交卷后统一测评' }}</span>
          </div>

          <div class="footer-btns">
            <button
              class="btn btn-primary btn-submit-now"
              type="button"
              :disabled="submitting || enter?.status === 'ENDED'"
              @click="submit"
            >
              <svg v-if="!submitting" class="play-icon" viewBox="0 0 24 24" fill="currentColor">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              <span v-else class="spinner"></span>
              <span>{{ submitting ? '正在提交…' : (enter?.mode === 'ACM' ? '提交评测' : '暂存作答') }}</span>
            </button>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.submit-page {
  padding-top: 24px;
  padding-bottom: 64px;
  display: flex;
  flex-direction: column;
  gap: 16px;
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

/* 顶部导航卡片 */
.submit-header {
  padding: 16px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
}
.header-left {
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
  background: #ffffff;
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 500;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-back:hover {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}
.back-icon {
  width: 14px;
  height: 14px;
}
.sep {
  color: var(--ink-tertiary);
}
.title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.action-tag {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 2px 6px;
  border-radius: 0;
}
.qid {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  color: var(--ink-tertiary);
  font-size: 14px;
}
.qname {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
}
.diff-pill {
  border-radius: 0 !important;
  font-size: 11px;
  padding: 2px 8px;
  font-weight: 700;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 3px 8px;
  border: 1px solid var(--line);
  background: var(--bg);
  border-radius: 0;
}
.chip-k {
  color: var(--ink-tertiary);
}
.chip-v {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 700;
  color: var(--ink);
}
.shortcut-chip {
  font-size: 12px;
  color: var(--ink-tertiary);
}
.shortcut-chip kbd {
  display: inline-block;
  padding: 1px 5px;
  border: 1px solid var(--line);
  background: var(--bg);
  font-size: 11px;
  border-radius: 0;
}

/* 编辑器容器卡片 */
.editor-container {
  border-radius: 0;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: #e9ecef;
  border-bottom: 1px solid var(--line);
}
.lang-selector {
  display: flex;
  gap: 6px;
}
.lang-btn {
  padding: 6px 16px;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink-secondary);
  font-size: 12px;
  font-weight: 700;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.lang-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}
.lang-btn.active {
  background: var(--ink);
  color: #fff;
  border-color: var(--ink);
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.btn-tool {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink-secondary);
  font-size: 12px;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-tool:hover {
  background: var(--ink);
  color: #fff;
  border-color: var(--ink);
}
.tool-icon {
  width: 13px;
  height: 13px;
}

.monaco-wrapper {
  border-bottom: 1px solid var(--line);
}

.submit-error {
  margin: 12px 20px 0;
  padding: 10px 14px;
  background: #ffebee;
  color: var(--danger);
  border: 1px solid var(--danger);
  font-size: 13px;
  border-radius: 0;
}

/* 底部操作区 */
.editor-footer {
  padding: 16px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #e9ecef;
  gap: 16px;
  flex-wrap: wrap;
}
.footer-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-tertiary);
}
.tip-dot {
  width: 6px;
  height: 6px;
  background: var(--ink);
  border-radius: 0;
}
.footer-btns {
  display: flex;
  align-items: center;
  gap: 12px;
}
.btn-submit-now {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  font-size: 14px;
  font-weight: 700;
  border-radius: 0;
  cursor: pointer;
}
.play-icon {
  width: 14px;
  height: 14px;
}
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
</style>
