<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { questionApi } from '@/api/question'
import { submissionApi } from '@/api/submission'
import { useUserStore } from '@/stores/user'
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
const userStore = useUserStore()
const { info, error: toastError } = useToast()
const { confirm } = useConfirm()

const id = computed(() => Number(route.params.id))

const question = ref<Question | null>(null)
const loading = ref(true)
const error = ref('')


const languages = computed(() =>
  (question.value?.languages ?? []).filter((l) => SUPPORTED_LANGUAGES.includes(l)),
)

const language = ref('cpp')
const codes = ref<Record<string, string>>({ ...TEMPLATES })
const submitting = ref(false)
const submitError = ref('')

function getDraftKey(lang: string) {
  return `kece:code_draft:q_${id.value}_${lang}`
}

function restoreDrafts() {
  for (const lang of SUPPORTED_LANGUAGES) {
    const saved = localStorage.getItem(getDraftKey(lang))
    if (saved) {
      codes.value[lang] = saved
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
  if (!userStore.isLoggedIn) {
    submitError.value = '请先登录后再提交'
    router.push('/login')
    return
  }
  const code = codes.value[language.value] ?? ''
  if (!code.trim()) {
    submitError.value = '代码内容不能为空'
    return
  }
  submitting.value = true
  submitError.value = ''
  try {
    const s = await submissionApi.submit({
      questionId: id.value,
      language: language.value,
      codeContent: { [FILENAMES[language.value]]: code },
    })
    // 提交成功后立即跳转至独立的结果页面
    router.push(`/questions/${id.value}/result/${s.submissionId}`)
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
    question.value = await questionApi.detail(id.value)
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
      <button class="btn btn-outline" @click="router.push(`/questions/${id}`)">返回题目描述</button>
    </div>

    <!-- 提交主工作区 -->
    <template v-else-if="question">
      <!-- 顶栏导航卡片 -->
      <header class="submit-header card">
        <div class="header-left">
          <button class="btn-back" @click="router.push(`/questions/${id}`)">
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
            <h1 class="qname">{{ question.title }}</h1>
          </div>
          <span class="badge diff-pill" :class="`difficulty-${question.difficulty.toLowerCase()}`">
            {{ difficultyLabels[question.difficulty] }}
          </span>
        </div>

        <div class="header-right">
          <div class="meta-chip">
            <span class="chip-k">时限</span>
            <span class="chip-v">{{ question.timeLimit ?? '-' }} ms</span>
          </div>
          <div class="meta-chip">
            <span class="chip-k">空间</span>
            <span class="chip-v">{{ question.memoryLimit ?? '-' }} MB</span>
          </div>
          <div class="shortcut-chip">
            <kbd>Ctrl</kbd>+<kbd>Enter</kbd> 提交
          </div>
        </div>
      </header>

      <!-- 编辑器操作容器 -->
      <main class="editor-container card">
        <!-- 工具栏：语言切换与重置 -->
        <div class="editor-toolbar">
          <div class="lang-selector">
            <button
              v-for="lang in languages"
              :key="lang"
              class="lang-btn"
              :class="{ active: lang === language }"
              @click="language = lang"
            >
              {{ LANGUAGE_LABELS[lang] ?? lang }}
            </button>
          </div>

          <div class="toolbar-actions">
            <button class="btn-tool" title="重置当前语言为初始代码模板" @click="resetTemplate">
              <svg class="tool-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="1 4 1 10 7 10" />
                <polyline points="23 20 23 14 17 14" />
                <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15" />
              </svg>
              <span>重置模板</span>
            </button>
          </div>
        </div>

        <!-- 高度充足的 Monaco 编辑器 -->
        <div class="monaco-wrapper">
          <CodeEditor v-model="codes[language]" :language="language" height="560px" />
        </div>

        <!-- 错误提示 -->
        <div v-if="submitError" class="submit-error">
          {{ submitError }}
        </div>

        <!-- 底部提交栏 -->
        <div class="editor-footer">
          <div class="footer-tip">
            <span class="tip-dot"></span>
            <span>提交后将自动转跳至独立测评结果页面，展示各个测试点的详细状态。</span>
          </div>

          <div class="footer-btns">
            <button class="btn btn-outline" @click="router.push(`/questions/${id}`)">
              取消
            </button>
            <button
              class="btn btn-primary btn-submit-now"
              :disabled="submitting"
              @click="submit"
            >
              <svg v-if="submitting" class="spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10" />
                <path d="M12 2a10 10 0 0 1 10 10" />
              </svg>
              <svg v-else class="play-icon" viewBox="0 0 24 24" fill="currentColor">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              <span>{{ submitting ? '正在分发任务…' : '提交评测 (Ctrl+Enter)' }}</span>
            </button>
          </div>
        </div>
      </main>
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

/* 顶栏信息 */
.submit-header {
  padding: 16px 24px;
  border-radius: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  background: var(--surface);
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
  font-weight: 700;
  color: var(--ink-tertiary);
}
.qname {
  margin: 0;
  font-size: 18px;
  font-weight: 800;
  color: var(--ink);
}
.diff-pill {
  border-radius: 0 !important;
  font-size: 12px;
  padding: 3px 8px;
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
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
}
.lang-selector {
  display: flex;
  gap: 6px;
}
.lang-btn {
  padding: 6px 16px;
  border: 1px solid var(--line);
  background: var(--surface);
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
  background: var(--surface);
  color: var(--ink-secondary);
  font-size: 12px;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-tool:hover {
  background: var(--bg);
  color: var(--ink);
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
  background: var(--bg);
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
  animation: spin 0.8s linear infinite;
}
</style>

