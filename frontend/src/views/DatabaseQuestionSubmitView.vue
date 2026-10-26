<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { databaseApi } from '@/api/database'
import { useUserStore } from '@/stores/user'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import CodeEditor from '@/components/CodeEditor.vue'
import DbSchemaViewer from '@/components/DbSchemaViewer.vue'
import type { DbQuestion, DbQuestionType } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { info, error: toastError } = useToast()
const { confirm } = useConfirm()

const id = computed(() => Number(route.params.id))

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

function getDraftKey() {
  return `kece:sql_draft:q_${id.value}`
}

function restoreDraft() {
  const saved = localStorage.getItem(getDraftKey())
  if (saved) {
    sql.value = saved
  }
}

watch(sql, (newVal) => {
  if (newVal) {
    localStorage.setItem(getDraftKey(), newVal)
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
    localStorage.removeItem(getDraftKey())
    info('已重置为初始 SQL 模板')
  }
}

async function submit() {
  if (submitting.value) return
  if (!userStore.isLoggedIn) {
    submitError.value = '请先登录后再提交'
    router.push('/login')
    return
  }
  if (!sql.value.trim()) {
    submitError.value = 'SQL 内容不能为空'
    return
  }
  submitting.value = true
  submitError.value = ''
  try {
    const s = await databaseApi.submit(id.value, sql.value)
    // 提交成功后转跳至独立的评测结果页
    router.push(`/database/${id.value}/result/${s.submissionId}`)
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
    question.value = await databaseApi.detail(id.value)
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
      <button class="btn btn-outline" @click="router.push(`/database/${id}`)">返回题目描述</button>
    </div>

    <!-- 提交主工作区 -->
    <template v-else-if="question">
      <!-- 顶栏导航卡片 -->
      <header class="submit-header card">
        <div class="header-left">
          <button class="btn-back" @click="router.push(`/database/${id}`)">
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
            <h1 class="qname">{{ question.title }}</h1>
          </div>
          <span class="badge type-pill" :class="`type-${question.type.toLowerCase()}`">
            {{ typeLabels[question.type] }}
          </span>
        </div>

        <div class="header-right">
          <div class="meta-chip">
            <span class="chip-k">方言</span>
            <span class="chip-v">MySQL 8.0</span>
          </div>
          <div v-if="question.orderSensitive" class="meta-chip order-chip">
            严格行序
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
            <span>提交后将在沙箱库中执行，并在独立结果页面中展示各测试点比对反馈。</span>
          </div>

          <div class="footer-btns">
            <button class="btn btn-outline" @click="router.push(`/database/${id}`)">
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
              <span>{{ submitting ? '正在分发评测…' : '提交评测 (Ctrl+Enter)' }}</span>
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
.type-pill {
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
.order-chip {
  border-color: #ffb74d;
  color: #e65100;
  background: #fff8e1;
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
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.lang-tag {
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: var(--ink);
  padding: 5px 12px;
  border-radius: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}
.schema-toggle-btn {
  background: var(--surface);
}
.schema-toggle-btn.active {
  border-color: var(--ink);
  color: var(--ink);
  font-weight: 600;
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

/* 表结构参考抽屉 */
.schema-drawer {
  background: #fdfdfd;
  border-bottom: 1px solid var(--line);
  padding: 12px 16px;
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

