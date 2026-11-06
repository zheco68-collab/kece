<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/request'
import { solutionApi, type SolutionListItem, type SolutionQuestionType } from '@/api/solution'
import Pagination from '@/components/Pagination.vue'

const props = defineProps<{ questionType: SolutionQuestionType; questionId: number }>()
const router = useRouter()
const rows = ref<SolutionListItem[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const size = 10

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await solutionApi.list({ questionType: props.questionType, questionId: props.questionId, page: page.value, size })
    rows.value = result.list
    total.value = result.total
  } catch (errorValue) {
    error.value = errorValue instanceof ApiError ? errorValue.message : '题解列表加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => [props.questionType, props.questionId], () => { page.value = 1; void load() })
watch(page, () => void load())
onMounted(() => void load())
</script>

<template>
  <section class="solutions-panel card">
    <header class="panel-head">
      <div><span class="eyebrow">SOLUTIONS / 题解</span><h2>公开题解</h2></div>
      <button class="btn btn-primary" type="button" @click="router.push({ name: 'solution-new', query: { type: questionType, questionId } })">写题解</button>
    </header>
    <p v-if="loading" class="state">正在载入题解…</p>
    <p v-else-if="error" class="state error">{{ error }}</p>
    <p v-else-if="rows.length === 0" class="state">还没有公开题解。你可以写下第一篇思路。</p>
    <div v-else class="solution-list">
      <button v-for="(row, index) in rows" :key="row.id" type="button" class="solution-row" :style="{ '--entry-index': index }" @click="router.push({ name: 'solution-read', params: { id: row.id } })">
        <span class="row-index">{{ String(index + 1).padStart(2, '0') }}</span>
        <span class="solution-main"><strong>{{ row.title }}</strong><span>作者 {{ row.authorName }} · {{ formatDate(row.updatedAt) }}</span></span>
        <span class="read-link">阅读题解 <b>→</b></span>
      </button>
    </div>
    <Pagination v-model:page="page" :size="size" :total="total" />
  </section>
</template>

<style scoped>
.solutions-panel { overflow: hidden; }
.panel-head { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:20px 24px; border-bottom:1px solid var(--line); }
.eyebrow { color:var(--ink-tertiary); font:12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; letter-spacing:.04em; }
h2 { margin:7px 0 0; font-size:20px; }
.state { margin:0; padding:36px 24px; color:var(--ink-secondary); text-align:center; }
.error { color:var(--danger); }
.solution-list { display:flex; flex-direction:column; padding:12px; gap:8px; }
.solution-row { display:flex; width:100%; align-items:center; justify-content:space-between; gap:16px; min-height:70px; padding:12px 16px; border:1px solid var(--line); border-left:4px solid var(--ink); background:var(--surface); color:var(--ink); text-align:left; cursor:pointer; animation:solution-row-drop .42s cubic-bezier(.2,.82,.32,1) both; animation-delay:calc(var(--entry-index) * 65ms); }
.solution-row:hover { transform:translateX(4px); border-color:var(--ink); background:var(--bg); }
.row-index { flex:0 0 auto; width:32px; color:var(--ink-tertiary); font:12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; }
.solution-main { display:flex; min-width:0; flex-direction:column; gap:5px; }
.solution-main strong { font-size:15px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.solution-main span, .read-link { color:var(--ink-secondary); font-size:13px; }
.read-link { display:flex; align-items:center; justify-content:space-between; gap:10px; min-width:94px; padding:8px 10px; border:1px solid var(--line); background:#171717; color:#fff; white-space:nowrap; font-weight:700; }
.read-link b { font-size:17px; line-height:1; }
@keyframes solution-row-drop { from { opacity:0; transform:translateY(-18px); } 60% { opacity:1; transform:translateY(3px); } to { opacity:1; transform:translateY(0); } }
@media (prefers-reduced-motion: reduce) { .solution-row { animation:none; } }
@media (max-width: 640px) { .panel-head, .solution-row { padding-left:16px; padding-right:16px; } }
</style>
