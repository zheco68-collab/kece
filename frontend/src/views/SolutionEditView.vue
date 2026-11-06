<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/request'
import { solutionApi, type SolutionDetail, type SolutionQuestionType } from '@/api/solution'
import CodeEditor from '@/components/CodeEditor.vue'
import SolutionArticle from '@/components/solutions/SolutionArticle.vue'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const editingId = computed(() => route.params.id ? Number(route.params.id) : null)
const questionType = ref<SolutionQuestionType>(route.query.type === 'DB' ? 'DB' : 'ALGO')
const questionId = ref(Number(route.query.questionId) || 0)
const title = ref('')
const contentMd = ref('')
const version = ref(0)
const loading = ref(Boolean(editingId.value))
const submitting = ref(false)
const error = ref('')
const previewOpen = ref(true)

const heading = computed(() => editingId.value ? '编辑题解' : '写题解')
const questionLabel = computed(() => questionType.value === 'DB' ? 'SQL 数据库题' : '算法编程题')

function requestId() {
  return globalThis.crypto?.randomUUID?.() ?? `solution-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function applyDetail(detail: SolutionDetail) {
  questionType.value = detail.questionType
  questionId.value = detail.questionId
  title.value = detail.title
  contentMd.value = detail.contentMd
  version.value = detail.version
}

async function load() {
  if (!editingId.value) return
  loading.value = true
  error.value = ''
  try {
    applyDetail(await solutionApi.detail(editingId.value))
  } catch (errorValue) {
    error.value = errorValue instanceof ApiError ? errorValue.message : '题解加载失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  const normalizedTitle = title.value.trim()
  if (!questionId.value || questionId.value < 1) { error.value = '请从题目页面进入写题解。'; return }
  if (!normalizedTitle) { error.value = '请填写题解标题。'; return }
  if (!contentMd.value.trim()) { error.value = '请填写题解正文。'; return }
  submitting.value = true
  error.value = ''
  try {
    const detail = editingId.value
      ? await solutionApi.update(editingId.value, { title: normalizedTitle, contentMd: contentMd.value, version: version.value })
      : await solutionApi.create({ questionType: questionType.value, questionId: questionId.value, title: normalizedTitle, contentMd: contentMd.value, clientRequestId: requestId() })
    toast.success(editingId.value ? '题解已更新' : '题解已直接发布')
    router.replace({ name: 'solution-read', params: { id: detail.id } })
  } catch (errorValue) {
    error.value = errorValue instanceof ApiError ? errorValue.message : '发布题解失败'
  } finally {
    submitting.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <main class="solution-edit container">
    <section v-if="loading" class="state card">正在载入题解…</section>
    <section v-else class="editor-shell">
      <header class="editor-head">
        <div><span class="eyebrow">SOLUTION AUTHORING</span><h1>{{ heading }}</h1><p>{{ questionLabel }} #{{ questionId || '—' }} · 发布后立即对公开题目可见</p></div>
        <button class="btn btn-outline" type="button" @click="router.back()">取消</button>
      </header>
      <p v-if="error" class="error-banner">{{ error }}</p>
      <div class="solution-title-field">
        <label for="solution-title">题解标题 <span>最多 80 字</span></label>
        <input id="solution-title" v-model="title" class="input" maxlength="80" placeholder="例如：前缀和的线性解法" />
      </div>
      <div class="format-help">支持 Markdown、数学公式与代码块。代码块使用 <code>```java</code>、<code>```sql</code> 等语言标识，可在预览和阅读页高亮。</div>
      <div class="workbench" :class="{ 'preview-hidden': !previewOpen }">
        <section class="edit-pane"><div class="pane-head">Markdown 编辑器 <span>关键字高亮</span></div><CodeEditor v-model="contentMd" language="markdown" height="620px" /></section>
        <section v-if="previewOpen" class="preview-pane"><div class="pane-head">实时预览 <button type="button" @click="previewOpen = false">收起</button></div><SolutionArticle :content="contentMd" /></section>
      </div>
      <button v-if="!previewOpen" class="btn btn-outline preview-toggle" type="button" @click="previewOpen = true">显示实时预览</button>
      <footer class="editor-actions"><span>正文上限 64 KB · 无草稿箱，提交即发布</span><button class="btn btn-primary" type="button" :disabled="submitting" @click="submit">{{ submitting ? '正在发布…' : editingId ? '保存更新' : '直接发布' }}</button></footer>
    </section>
  </main>
</template>

<style scoped>
.solution-edit { padding-top:30px; padding-bottom:64px; }
.editor-shell { display:flex; flex-direction:column; gap:16px; }
.editor-head { display:flex; align-items:flex-start; justify-content:space-between; gap:24px; }
.eyebrow { font:12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; color:var(--ink-tertiary); letter-spacing:.06em; }
h1 { margin:8px 0 6px; font-size:28px; } .editor-head p, .editor-actions span, .format-help { margin:0; color:var(--ink-secondary); font-size:14px; }
.solution-title-field { display:flex; flex-direction:column; gap:8px; }
.solution-title-field label { display:flex; align-items:center; justify-content:space-between; color:var(--ink); font-size:14px; font-weight:700; }
.solution-title-field label span { color:var(--ink-tertiary); font-size:12px; font-weight:400; }
.solution-title-field .input { box-sizing:border-box; width:100%; height:46px; padding:0 14px; border:1px solid var(--line); border-radius:0; background:var(--surface); color:var(--ink); font-size:16px; }
.solution-title-field .input:focus { outline:2px solid var(--ink); outline-offset:-2px; }
.format-help { padding:12px 14px; border-left:3px solid var(--ink); background:var(--bg); } .format-help code { font-family:ui-monospace, monospace; }
.workbench { display:grid; grid-template-columns:minmax(0, 1fr) minmax(340px, .9fr); border:1px solid var(--line); background:var(--surface); }
.workbench.preview-hidden { grid-template-columns:minmax(0, 1fr); }
.edit-pane, .preview-pane { min-width:0; } .preview-pane { padding:0 24px 28px; border-left:1px solid var(--line); overflow:auto; max-height:684px; }
.pane-head { display:flex; align-items:center; justify-content:space-between; min-height:48px; padding:0 16px; border-bottom:1px solid var(--line); color:var(--ink); font-size:13px; font-weight:700; } .pane-head span { color:var(--ink-tertiary); font-weight:400; } .pane-head button { border:0; background:transparent; color:var(--ink-secondary); cursor:pointer; }
.preview-toggle { align-self:flex-start; } .editor-actions { display:flex; align-items:center; justify-content:space-between; gap:20px; padding-top:4px; }
.state { padding:48px; text-align:center; color:var(--ink-secondary); } .error-banner { margin:0; padding:12px 14px; border:1px solid var(--danger); color:var(--danger); background:var(--danger-bg); }
@media(max-width: 980px) { .workbench { grid-template-columns:1fr; } .preview-pane { border-left:0; border-top:1px solid var(--line); max-height:none; } }
@media(max-width: 640px) { .solution-edit { padding-top:18px; } .editor-head, .editor-actions { flex-direction:column; align-items:stretch; } }
</style>
