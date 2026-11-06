<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { examApi, type ExamSessionQuestion, type SessionEnter } from '@/api/exam'
import { databaseApi } from '@/api/database'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import CodeEditor from '@/components/CodeEditor.vue'
import DbSchemaViewer from '@/components/DbSchemaViewer.vue'
import { useCodeDraft } from '@/features/questions/composables/useCodeDraft'
import { useUserStore } from '@/stores/user'
import type { DbQuestion, DbQuestionType } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { info, success, error: toastError } = useToast()
const { confirm } = useConfirm()

const examId = computed(() => Number(route.params.id))
const examQuestionId = computed(() => Number(route.params.examQuestionId))

const enter = ref<SessionEnter | null>(null)
const examQuestion = ref<ExamSessionQuestion | null>(null)
const question = ref<DbQuestion | null>(null)
const loading = ref(true)
const error = ref('')

const typeLabels: Record<DbQuestionType, string> = {
  SELECT: 'SELECT 查询',
  DML: 'DML 数据操作',
}

const DEFAULT_SQL = '-- 在此编写你的 SQL（单条语句）\n'
const sql = ref(DEFAULT_SQL)
const showSchema = ref(true)
const submitting = ref(false)
const submitError = ref('')

function getLegacyDraftKey() {
  return `kece:exam_sql:${examId.value}_${examQuestionId.value}`
}

function draftStore() {
  const owner = userStore.userInfo?.id ?? 'anonymous'
  return useCodeDraft({
    targetKey: `kece:draft:${owner}:exam-sql:${examId.value}:${examQuestionId.value}`,
    legacyKeys: [getLegacyDraftKey()],
  })
}

function restoreDraft() {
  const saved = draftStore().getDraft()
  if (saved) {
    sql.value = saved
  }

  const mySaved = enter.value?.myAnswers?.[examQuestionId.value]
  if (mySaved && mySaved.answerContent) {
    sql.value = mySaved.answerContent
  }
}

watch(sql, (newVal) => {
  if (newVal) {
    draftStore().saveDraft(newVal)
  }
})

async function resetTemplate() {
  const ok = await confirm({
    title: '重置 SQL',
    message: '确定要将当前 SQL 代码重置为初始模板吗？当前修改将会丢失。',
    confirmText: '确认重置',
    cancelText: '取消',
    danger: true,
  })
  if (ok) {
    sql.value = DEFAULT_SQL
    draftStore().clearDraft()
    info('已重置为初始 SQL 模板')
  }
}

async function submit() {
  if (submitting.value) return
  if (enter.value?.status === 'ENDED') {
    toastError('当前考试已结束，无法继续提交')
    return
  }
  if (!sql.value.trim()) {
    submitError.value = 'SQL 内容不能为空'
    return
  }
  submitting.value = true
  submitError.value = ''
  try {
    // 1. 保存作答
    await examApi.saveAnswer(examId.value, {
      examQuestionId: examQuestionId.value,
      answer: sql.value,
    })

    // 2. 如果是 ACM 赛制，即时入队评测并跳转到结果页面
    if (enter.value?.mode === 'ACM') {
      const res = await examApi.submitQuestion(examId.value, examQuestionId.value)
      success('已提交 SQL 评测！正在为您跳转到测评报告…')
      router.push(
        `/exams/${examId.value}/db-question/${examQuestionId.value}/result/${res.submissionId}`,
      )
    } else {
      // OI 赛制
      success('SQL 作答已暂存！OI 赛制将在统一交卷后评测')
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
      error.value = '未找到该数据库题目'
      return
    }
    examQuestion.value = eq

    question.value = await databaseApi.detail(eq.questionId)
    restoreDraft()
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
  <div class="db-submit-page container">
    <!-- 加载与异常状态 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在准备 SQL 编辑器与沙箱环境…</p>
    </div>

    <div v-else-if="error" class="state-card card">
      <div class="error-title">加载失败</div>
      <p class="error-msg">{{ error }}</p>
      <button
        class="btn btn-outline"
        @click="router.push(`/exams/${examId}/db-question/${examQuestionId}`)"
      >
        返回题目描述
      </button>
    </div>

    <!-- 提交主工作区 (直接照抄 PracticeDbQuestionSubmitView 标准架构与视觉样式) -->
    <template v-else-if="question && examQuestion">
      <!-- 顶栏导航卡片 -->
      <header class="submit-header card">
        <div class="header-left">
          <button
            class="btn-back"
            @click="router.push(`/exams/${examId}/db-question/${examQuestionId}`)"
          >
            <svg class="back-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            <span>返回题目描述</span>
          </button>
          <span class="sep">/</span>
          <div class="title-group">
            <span class="action-tag">提交 SQL</span>
            <span class="qid">#{{ question.id }}</span>
            <h1 class="qname">{{ examQuestion.title }}</h1>
          </div>
          <span class="badge type-pill">
            {{ typeLabels[question.type] }}
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
          <div v-if="question.orderSensitive" class="meta-chip order-chip">
            行序敏感 (ORDER BY)
          </div>
          <div class="shortcut-chip">
            <kbd>Ctrl</kbd>+<kbd>Enter</kbd> 提交
          </div>
        </div>
      </header>

      <!-- 编辑器容器卡片 -->
      <main class="editor-container card">
        <!-- 工具栏：方言与表结构参考 -->
        <div class="editor-toolbar">
          <div class="toolbar-left">
            <span class="lang-tag">SQL (MySQL 8.0)</span>
            <button
              class="btn-tool schema-toggle-btn"
              :class="{ active: showSchema }"
              @click="showSchema = !showSchema"
            >
              <svg class="tool-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <ellipse cx="12" cy="5" rx="9" ry="3" />
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
              </svg>
              <span>{{ showSchema ? '收起表结构参考' : '展开表结构参考' }}</span>
            </button>
          </div>

          <div class="toolbar-right">
            <button class="btn-tool" title="重置当前 SQL 为初始模板" @click="resetTemplate">
              <svg class="tool-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="1 4 1 10 7 10" />
                <polyline points="23 20 23 14 17 14" />
                <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15" />
              </svg>
              <span>重置模板</span>
            </button>
          </div>
        </div>

        <!-- 可折叠表结构参考面板 -->
        <div v-if="showSchema && question.schemaSql" class="schema-drawer">
          <DbSchemaViewer compact :schema-sql="question.schemaSql" />
        </div>

        <!-- Monaco 代码编辑器 -->
        <div class="monaco-wrapper">
          <CodeEditor v-model="sql" language="mysql" height="520px" />
        </div>

        <!-- 错误提示 -->
        <div v-if="submitError" class="submit-error">
          {{ submitError }}
        </div>

        <!-- 底部提交操作栏 -->
        <div class="editor-footer">
          <div class="footer-tip">
            <span class="tip-dot"></span>
            <span>代码自动保存在本地草稿，{{ enter?.mode === 'ACM' ? 'ACM 赛制点击提交即可即时评测并展示结果' : 'OI 赛制将保存本题作答并于交卷后统一测评' }}</span>
          </div>

          <div class="footer-btns">
            <button
              class="btn btn-outline"
              @click="router.push(`/exams/${examId}/db-question/${examQuestionId}`)"
            >
              取消
            </button>
            <button
              class="btn btn-primary btn-submit-now"
              :disabled="submitting || enter?.status === 'ENDED'"
              @click="submit"
            >
              <div v-if="submitting" class="spinner"></div>
              <svg v-else class="play-icon" viewBox="0 0 24 24" fill="currentColor">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              <span>{{ submitting ? '正在分发任务…' : (enter?.mode === 'ACM' ? '提交评测 (Ctrl+Enter)' : '暂存作答 (Ctrl+Enter)') }}</span>
            </button>
          </div>
        </div>
      </main>
    </template>
  </div>
</template>

<style scoped>
.db-submit-page {
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

/* 顶栏卡片 */
.submit-header {
  padding: 16px 24px;
  border-radius: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  background: var(--surface);
  border: 1px solid var(--line);
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
  font-weight: 700;
  color: var(--ink-tertiary);
}
.qname {
  margin: 0;
  font-size: 18px;
  font-weight: 800;
  color: var(--ink);
}
.type-pill {
  border-radius: 0 !important;
  font-size: 12px;
  padding: 3px 8px;
  font-weight: 700;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink);
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
.order-chip {
  background: #fff8e1;
  border-color: #f57f17;
  color: #f57f17;
  font-weight: 700;
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

/* 编辑器容器 */
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
  padding: 10px 18px;
  background: #e9ecef;
  border-bottom: 1px solid var(--line);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.lang-tag {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-weight: 800;
  font-size: 12px;
  color: var(--ink);
}

.schema-toggle-btn {
  font-size: 12px;
  font-weight: 600;
  background: #ffffff;
}
.schema-toggle-btn.active {
  background: var(--ink);
  color: #fff;
  border-color: var(--ink);
}

.toolbar-right {
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

.schema-drawer {
  padding: 16px 20px;
  background: #f8f9fa;
  border-bottom: 1px solid var(--line);
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

/* 底部操作条 */
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
