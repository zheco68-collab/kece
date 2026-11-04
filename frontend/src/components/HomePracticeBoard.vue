<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { questionApi } from '@/api/question'
import type { Question } from '@/types'
import ScrambleText from './ScrambleText.vue'

const keyword = ref('')
const searchFocused = ref(false)
const page = ref(1)
const size = 4
const total = ref<number | null>(null)
const questions = ref<Question[]>([])
const loading = ref(true)
const error = ref('')
let requestNumber = 0
let searchTimer: ReturnType<typeof setTimeout> | null = null

function resetResults() {
  loading.value = true
  error.value = ''
  total.value = null
  questions.value = []
}

async function loadQuestions() {
  if (searchTimer) clearTimeout(searchTimer)
  const request = ++requestNumber
  resetResults()
  try {
    const result = await questionApi.list({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size,
    })
    if (request !== requestNumber) return
    questions.value = result.list
    total.value = result.total
  } catch {
    if (request !== requestNumber) return
    error.value = '题目暂时无法加载，请稍后重试。'
  } finally {
    if (request === requestNumber) loading.value = false
  }
}

watch(keyword, () => {
  requestNumber++
  page.value = 1
  resetResults()
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(loadQuestions, 300)
})

function goPage(next: number) {
  if (loading.value || total.value === null || next < 1 || next > Math.ceil(total.value / size)) return
  page.value = next
  loadQuestions()
}

onMounted(loadQuestions)
onBeforeUnmount(() => {
  requestNumber++
  if (searchTimer) clearTimeout(searchTimer)
})

function difficultyName(value: string) {
  if (value === 'EASY') return '简单'
  if (value === 'HARD') return '困难'
  return '中等'
}

</script>

<template>
  <section class="practice-board container" aria-labelledby="practice-board-title">
    <div class="practice-intro">
      <div class="intro-copy">
        <h2 id="practice-board-title"><ScrambleText text="今天，解一道题" /></h2>
        <p class="intro-description">
          <ScrambleText text="读题、构思、提交。" :delay="40" />
          <ScrambleText text="把想法变成可运行的答案。" :delay="80" />
        </p>
      </div>
      <div class="intro-footer">
        <div class="practice-total">
          <template v-if="total !== null">
            <strong><ScrambleText :text="String(total)" :delay="200" /></strong>
            <span><ScrambleText :text="`道${keyword.trim() ? '匹配的' : '公开的'}编程题`" :delay="220" /></span>
          </template>
          <span v-else><ScrambleText :text="error ? '题库数据暂不可用' : '正在读取题库…'" /></span>
        </div>
        <RouterLink to="/questions" class="all-questions">
          <ScrambleText text="完整题库" :delay="240" />
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 15 15 5m-7 0h7v7" /></svg>
        </RouterLink>
      </div>
    </div>

    <div class="practice-list-panel">
      <div class="panel-top">
        <h3><ScrambleText text="选择下一道练习" /></h3>
        <span class="live-label"><ScrambleText text="公开题库" :delay="40" /></span>
      </div>
      <label class="search-label" for="practice-search"><ScrambleText text="按题目名称搜索" :delay="60" /></label>
      <div class="search-field">
        <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="9" cy="9" r="5.5" /><path d="m13 13 4 4" /></svg>
        <span v-if="!keyword && !searchFocused" class="search-placeholder" aria-hidden="true"><ScrambleText text="输入算法或题目关键词" :delay="80" /></span>
        <input id="practice-search" v-model="keyword" type="search" aria-label="输入算法或题目关键词" @focus="searchFocused = true" @blur="searchFocused = false" />
      </div>
      <div class="results-region" :aria-busy="loading">
        <div v-if="loading" class="list-state" role="status"><ScrambleText text="正在读取题库…" /></div>
        <div v-else-if="error" class="list-state" role="alert"><ScrambleText :text="error" /><button type="button" @click="loadQuestions"><ScrambleText text="重新加载" /></button></div>
        <div v-else-if="questions.length === 0" class="list-state" role="status"><strong><ScrambleText text="没有找到匹配的题目" /></strong><span><ScrambleText text="换个关键词，或清空搜索。" :delay="40" /></span></div>
        <div v-else class="question-rows">
          <RouterLink v-for="(question, index) in questions" :key="question.id" :to="`/questions/${question.id}`" class="question-row">
            <span class="question-number mono"><ScrambleText :text="`#${question.id}`" :delay="index * 35" /></span>
            <span class="question-title"><ScrambleText :text="question.title" :delay="30 + index * 35" /></span>
            <span class="difficulty"><ScrambleText :text="difficultyName(question.difficulty)" :delay="60 + index * 35" /></span>
            <svg class="row-arrow" viewBox="0 0 20 20" aria-hidden="true"><path d="M5 15 15 5m-7 0h7v7" /></svg>
          </RouterLink>
        </div>
      </div>
      <div class="list-footer">
        <span v-if="total !== null && total > 0"><ScrambleText :text="`${page} / ${Math.ceil(total / size)} 页`" :delay="220" /></span>
        <span v-else><ScrambleText :text="loading ? '正在更新…' : error ? '等待重新加载' : '共 0 道题目'" /></span>
        <div class="page-actions">
          <button type="button" :disabled="page <= 1 || loading || total === null" @click="goPage(page - 1)"><ScrambleText text="上一页" :delay="240" /></button>
          <button type="button" :disabled="total === null || page >= Math.ceil((total ?? 0) / size) || loading" @click="goPage(page + 1)"><ScrambleText text="下一页" :delay="260" /></button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.practice-board { padding-bottom: 44px; font-size: 16px; line-height: 1.6; }
.practice-intro { display:flex; align-items:center; justify-content:space-between; gap:24px; padding:28px 32px; background:#17191c; color:white; }
.intro-copy { min-width:0; }
.practice-intro h2 { margin:0 0 8px; font-size:30px; line-height:1.3; letter-spacing:-.03em; }
.intro-description { margin:0; color:#d5d8dc; font-size:14px; display:flex; flex-wrap:wrap; gap:0 4px; }
.intro-footer { display:flex; flex-direction:column; align-items:flex-end; gap:8px; flex-shrink:0; }
.practice-total { display:flex; align-items:baseline; gap:8px; flex-wrap:wrap; font-size:13px; color:#d5d8dc; }
.practice-total strong { color:white; font-size:24px; }
.all-questions { display:flex; align-items:center; gap:8px; color:white; min-height:36px; text-decoration:none; font-size:14px; }
.all-questions:hover { text-decoration:underline; text-underline-offset:4px; }
.all-questions svg,.row-arrow,.search-field svg { width:18px; height:18px; fill:none; stroke:currentColor; stroke-width:1.7; flex-shrink:0; }
.practice-list-panel { padding:24px 32px; border:1px solid var(--line); border-top:0; background:var(--bg); min-width:0; display:grid; grid-template-columns:1fr minmax(240px,360px); column-gap:24px; }
.panel-top { display:flex; align-items:center; gap:16px; grid-column:1; grid-row:1; }
.panel-top h3 { margin:0; font-size:20px; }
.live-label { font-size:12px; color:var(--ink-secondary); white-space:nowrap; }
.search-label { position:absolute; width:1px; height:1px; overflow:hidden; clip-path:inset(50%); }
.search-field { position:relative; display:flex; align-items:center; border:1px solid var(--line); min-height:44px; grid-column:2; grid-row:1; }
.search-field svg { margin-left:12px; }
.search-field input { min-width:0; width:100%; min-height:44px; padding:8px 12px; border:0; border-radius:0; background:transparent; color:var(--ink); font:inherit; outline:none; }
.search-field:focus-within { outline:2px solid var(--ink); outline-offset:2px; }
.search-placeholder { position:absolute; left:42px; font-size:14px; color:var(--ink-secondary); pointer-events:none; }
.results-region { grid-column:1 / -1; margin-top:20px; }
.question-rows { border-top:1px solid var(--line); }
.question-row { display:grid; grid-template-columns:60px minmax(0,1fr) 48px 20px; gap:16px; align-items:center; min-height:58px; padding:10px 12px; border-bottom:1px solid var(--line); text-decoration:none; color:var(--ink); }
.question-row:hover { background:var(--surface); }
.question-number,.difficulty { font-size:14px; color:var(--ink-secondary); }
.question-number { overflow-wrap:anywhere; }
.question-title { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-weight:600; }
.list-state { min-height:232px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:12px; color:var(--ink-secondary); }
.list-footer { grid-column:1 / -1; display:flex; align-items:center; justify-content:space-between; gap:16px; padding-top:16px; font-size:14px; color:var(--ink-secondary); }
.page-actions { display:flex; gap:8px; }
.page-actions button,.list-state button { min-height:40px; padding:8px 16px; border:1px solid var(--line); border-radius:0; background:var(--bg); color:var(--ink); font:inherit; cursor:pointer; }
button:disabled { opacity:.45; cursor:default; } button:not(:disabled):hover { background:var(--surface); }
a:focus-visible,button:focus-visible { outline:2px solid currentColor; outline-offset:3px; }
@media(max-width:1200px) {
  .practice-intro { align-items:flex-start; padding:24px; }
  .intro-description { display:grid; }
  .practice-list-panel { padding:20px 24px; }
  .live-label { display:none; }
}
@media(max-width:780px) {
  .practice-intro { flex-direction:column; gap:16px; }
  .intro-footer { flex-direction:row; justify-content:space-between; align-items:center; width:100%; }
  .practice-intro h2 { font-size:26px; }
  .practice-list-panel { grid-template-columns:1fr; gap:12px; }
  .search-field { grid-row:2; grid-column:1; }
  .results-region { margin-top:0; }
  .question-row { grid-template-columns:44px minmax(0,1fr) 32px 18px; gap:10px; padding:10px 0; }
  .question-number,.difficulty { font-size:12px; }
}
@media(max-width:480px) {
  .practice-intro,.practice-list-panel { padding:20px 16px; }
  .intro-description { font-size:13px; }
  .intro-footer { flex-wrap:wrap; }
  .list-footer { flex-wrap:wrap; }
}
</style>
