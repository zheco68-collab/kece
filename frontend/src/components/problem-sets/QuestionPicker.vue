<script setup lang="ts">
import { computed, onBeforeUnmount, ref, toRef, watch } from 'vue'
import Pagination from '@/components/Pagination.vue'
import { useQuestionCandidates } from '@/shared/composables/useQuestionCandidates'
import QuestionBadge from './QuestionBadge.vue'
import type { QuestionCandidate } from './types'

const props = defineProps<{ category: string; sectionNumber: number; selectedKeys: string[] }>()
const emit = defineEmits<{ select: [question: QuestionCandidate] }>()

const expanded = ref(new Set<number>())
const selected = computed(() => new Set(props.selectedKeys))
const categoryName = computed(() => ({ ALGO: '算法编程题', DB: '数据库 SQL 题', OBJECTIVE: '标准客观题' })[props.category] || '题目')

const {
  keyword,
  page,
  size,
  total,
  candidates: items,
  loading,
  error,
  load,
  cleanup,
} = useQuestionCandidates({
  category: toRef(props, 'category'),
  defaultSize: 12,
})

let timer: ReturnType<typeof setTimeout> | undefined

watch(keyword, () => {
  loading.value = true
  page.value = 1
  clearTimeout(timer)
  timer = setTimeout(() => {
    expanded.value.clear()
    void load()
  }, 300)
})

watch(() => props.category, () => {
  page.value = 1
  expanded.value.clear()
  void load()
}, { immediate: true })

function goPage(value: number) {
  expanded.value.clear()
  void load(undefined, value)
}

function togglePreview(id: number) {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
}

onBeforeUnmount(() => {
  clearTimeout(timer)
  cleanup()
})
</script>

<template>
  <section class="picker-panel" aria-labelledby="picker-heading">
    <header class="picker-header">
      <div class="picker-heading-row"><h2 id="picker-heading">题库检索</h2><span class="target">加入第 {{ sectionNumber }} 节</span></div>
      <p>{{ categoryName }} <span>· 搜索并添加到当前章节</span></p>
      <label for="picker-search">题号或关键词</label>
      <input id="picker-search" v-model="keyword" class="input picker-search" type="search" placeholder="例如 #123，或输入题目关键词" />
      <div class="picker-summary" aria-live="polite">{{ loading ? '正在检索题库…' : error ? '检索未完成' : `共 ${total} 道题 · 每页 ${size} 道` }}</div>
    </header>

    <div class="picker-results" :aria-busy="loading">
      <div v-if="loading" class="picker-state" role="status">正在加载题目…</div>
      <div v-else-if="error" class="picker-state" role="alert">{{ error }}<button class="btn btn-ghost" type="button" @click="load()">重试</button></div>
      <div v-else-if="items.length === 0" class="picker-state">没有找到题目，请换个题号或关键词。</div>
      <div v-else class="candidate-list">
        <article v-for="item in items" :key="`${item.questionType}-${item.questionId}`" class="candidate-row">
          <div class="candidate-meta"><span class="mono">#{{ item.questionId }}</span><QuestionBadge :value="item.difficulty" :question-type="item.questionType" /></div>
          <p class="candidate-title" :class="{ expanded: expanded.has(item.questionId) }">{{ item.title }}</p>
          <div class="candidate-actions">
            <button v-if="item.title.length > 90" class="preview-button" type="button" :aria-expanded="expanded.has(item.questionId)" @click="togglePreview(item.questionId)">{{ expanded.has(item.questionId) ? '收起题干' : '查看完整题干' }}</button>
            <button type="button" class="btn add-button" :class="selected.has(`${item.questionType}-${item.questionId}`) ? 'btn-ghost' : 'btn-primary'" :disabled="selected.has(`${item.questionType}-${item.questionId}`)" @click="emit('select', item)">{{ selected.has(`${item.questionType}-${item.questionId}`) ? '✓ 已加入' : '+ 加入章节' }}</button>
          </div>
        </article>
      </div>
    </div>
    <footer class="picker-footer">
      <Pagination v-if="!error && total > 0" compact :page="page" :size="size" :total="total" :disabled="loading" @update:page="goPage" />
      <span v-else>搜索结果将在这里分页显示</span>
    </footer>
  </section>
</template>

<style scoped>
.picker-panel { min-width: 0; width: 100%; display: flex; flex-direction: column; position: sticky; top: 80px; max-height: calc(100dvh - 104px); border: 1px solid var(--line); background: var(--surface); }
.picker-header { padding: 22px 24px 14px; flex-shrink: 0; border-bottom: 1px solid var(--line); }
.picker-heading-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
h2 { font-size: 21px; margin: 0; }
.target { font-size: 14px; font-weight: 600; padding: 6px 10px; background: var(--bg); border: 1px solid var(--line); }
.picker-header p { font-size: 14px; margin: 12px 0 20px; color: var(--ink-secondary); line-height: 1.6; }
.picker-header p span { color: var(--ink-secondary); }
label { display: block; margin-bottom: 8px; font-size: 15px; font-weight: 600; }
.picker-search { width: 100%; min-height: 46px; font-size: 16px; }
.picker-summary { font-size: 14px; color: var(--ink-secondary); margin-top: 12px; }
.picker-results { min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.candidate-list { padding: 0 24px; }
.candidate-row { min-width: 0; padding: 18px 0; border-bottom: 1px solid var(--line); }
.candidate-row:last-child { border-bottom: 0; }
.candidate-meta { display: flex; align-items: center; gap: 10px; font-size: 14px; color: var(--ink-secondary); }
.candidate-title { font-size: 16px; line-height: 1.7; margin: 10px 0 12px; overflow-wrap: anywhere; white-space: pre-line; }
.candidate-title:not(.expanded) { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.candidate-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.add-button { margin-left: auto; min-height: 40px; padding: 8px 16px; font-size: 14px; }
.preview-button { border: 0; background: transparent; padding: 8px 0; min-height: 40px; font-size: 14px; text-decoration: underline; text-underline-offset: 4px; cursor: pointer; color: var(--ink-secondary); }
.picker-footer { padding: 12px 20px; flex-shrink: 0; border-top: 1px solid var(--line); font-size: 14px; color: var(--ink-secondary); }
.picker-state { padding: 40px 24px; min-height: 160px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; font-size: 16px; text-align: center; }
button:focus-visible, input:focus-visible { outline: 2px solid var(--ink); outline-offset: 3px; }
@media (max-width: 1180px) { .picker-panel { position: static; max-height: 850px; } }
@media (max-width: 600px) { .picker-header { padding: 20px 16px 14px; } .candidate-list { padding: 0 16px; } .picker-footer { padding: 12px; } .picker-panel { max-height: none; } .picker-results { max-height: 540px; } }
</style>
