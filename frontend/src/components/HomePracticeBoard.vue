<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { questionApi } from '@/api/question'
import type { Question } from '@/types'

const keyword = ref('')
const page = ref(1)
const size = 5
const total = ref(0)
const questions = ref<Question[]>([])
const loading = ref(false)
const error = ref('')
let requestNumber = 0
let searchTimer: ReturnType<typeof setTimeout> | null = null

async function loadQuestions() {
  const request = ++requestNumber
  loading.value = true
  error.value = ''
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
    questions.value = []
  } finally {
    if (request === requestNumber) loading.value = false
  }
}

watch(keyword, () => {
  requestNumber++
  page.value = 1
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(loadQuestions, 300)
})

function goPage(next: number) {
  if (next < 1 || next > Math.ceil(total.value / size)) return
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
      <div class="eyebrow mono">// PRACTICE DESK · REAL PROBLEMS</div>
      <h2 id="practice-board-title">从一道题开始</h2>
      <p>挑选一道真实题目，阅读题意、编写代码并提交评测。每次尝试都从这里开始。</p>
      <div class="practice-total">
        <strong>{{ total }}</strong>
        <span>道{{ keyword ? '匹配的' : '公开的' }}编程题</span>
      </div>
      <RouterLink to="/questions" class="all-questions">浏览完整题库 <span aria-hidden="true">↗</span></RouterLink>
    </div>

    <div class="practice-list-panel">
      <div class="panel-top">
        <div>
          <span class="mono panel-index">01 / 选择题目</span>
          <h3>找到下一道练习</h3>
        </div>
        <span class="live-label"><span class="live-dot"></span>公开题库</span>
      </div>
      <label class="search-label" for="practice-search">按标题搜索</label>
      <input id="practice-search" v-model="keyword" type="search" placeholder="输入算法或题目关键词…" />

      <div v-if="loading" class="list-state" role="status">正在加载题目…</div>
      <div v-else-if="error" class="list-state" role="alert">
        {{ error }} <button type="button" @click="loadQuestions">重试</button>
      </div>
      <div v-else-if="questions.length === 0" class="list-state">没有找到匹配的题目。</div>
      <div v-else class="question-rows">
        <RouterLink v-for="question in questions" :key="question.id" :to="`/questions/${question.id}`" class="question-row">
          <span class="question-number mono">#{{ question.id }}</span>
          <span class="question-title">{{ question.title }}</span>
          <span class="difficulty" :class="question.difficulty?.toLowerCase() || 'medium'">{{ difficultyName(question.difficulty) }}</span>
          <span class="row-arrow" aria-hidden="true">↗</span>
        </RouterLink>
      </div>

      <div class="list-footer">
        <span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
        <div class="page-actions">
          <button type="button" :disabled="page <= 1 || loading" @click="goPage(page - 1)">上一页</button>
          <button type="button" :disabled="page >= Math.ceil(total / size) || loading" @click="goPage(page + 1)">下一页</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.practice-board { display: grid; grid-template-columns: minmax(260px, .8fr) minmax(0, 1.2fr); gap: 28px; padding-top: 28px; padding-bottom: 72px; }
.practice-intro { background: #17191c; color: #fff; padding: 42px; display: flex; flex-direction: column; min-height: 440px; }
.eyebrow { color: #9da5ad; font-size: 11px; letter-spacing: .8px; font-weight: 700; }
.practice-intro h2 { font-size: clamp(30px, 3vw, 42px); letter-spacing: -.04em; margin: 28px 0 12px; }
.practice-intro p { color: #c7cbd0; line-height: 1.8; font-size: 14px; max-width: 320px; margin: 0; }
.practice-total { margin-top: auto; padding-top: 28px; display: flex; align-items: baseline; gap: 12px; }
.practice-total strong { font-size: 48px; letter-spacing: -.06em; }
.practice-total span { color: #b4bac1; font-size: 13px; }
.all-questions { display: inline-flex; align-items: center; justify-content: space-between; padding: 13px 0 0; margin-top: 20px; border-top: 1px solid #44494f; color: #fff; font-weight: 700; text-decoration: none; }
.all-questions:hover { color: #b9d5ff; }
.practice-list-panel { background: var(--surface); border: 1px solid var(--line); padding: 28px; min-width: 0; }
.panel-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 26px; }
.panel-index { color: var(--ink-tertiary); font-size: 11px; font-weight: 700; }
.panel-top h3 { font-size: 21px; margin: 8px 0 0; }
.live-label { font-size: 11px; color: #178347; white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.live-dot { width: 6px; height: 6px; background: #20b56d; }
.search-label { display: block; font-size: 12px; color: var(--ink-secondary); margin-bottom: 8px; }
input { width: 100%; border: 1px solid var(--line); border-radius: 0; background: #f8f9fa; color: var(--ink); padding: 12px 14px; font: inherit; outline: none; }
input:focus { border-color: var(--ink); background: #fff; }
.question-rows { margin-top: 18px; border-top: 1px solid var(--line); }
.question-row { display: grid; grid-template-columns: 52px minmax(0, 1fr) 42px 16px; align-items: center; gap: 12px; min-height: 55px; border-bottom: 1px solid var(--line); color: var(--ink); text-decoration: none; }
.question-row:hover { background: #f5f7fa; }
.question-number { color: var(--ink-tertiary); font-size: 12px; }
.question-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 13px; font-weight: 600; }
.difficulty { font-size: 11px; color: #936b1c; }
.difficulty.easy { color: #178347; }
.difficulty.hard { color: #bd3745; }
.row-arrow { font-size: 15px; }
.list-state { min-height: 276px; display: flex; align-items: center; justify-content: center; gap: 10px; color: var(--ink-secondary); font-size: 13px; text-align: center; }
.list-state button { border: 1px solid var(--line); background: #fff; padding: 7px 12px; cursor: pointer; }
.list-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; color: var(--ink-secondary); font-size: 12px; padding-top: 18px; }
.page-actions { display: flex; gap: 8px; }
.page-actions button { background: #fff; border: 1px solid var(--line); padding: 7px 10px; font: inherit; cursor: pointer; }
.page-actions button:disabled { opacity: .45; cursor: default; }
@media (max-width: 780px) { .practice-board { grid-template-columns: 1fr; } .practice-intro { min-height: 260px; padding: 28px; } .practice-intro h2 { margin-top: 16px; } }
@media (max-width: 480px) { .practice-list-panel { padding: 20px; } .question-row { grid-template-columns: 42px minmax(0, 1fr) 34px 12px; gap: 6px; } }
</style>
