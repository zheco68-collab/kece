<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { questionApi } from '@/api/question'
import type { Question } from '@/types'
import ScrambleText from './ScrambleText.vue'

const title = ref<InstanceType<typeof ScrambleText> | null>(null)
const keyword = ref('')
const page = ref(1)
const size = 5
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
      <div class="intro-topline"><span class="eyebrow mono">// PRACTICE DESK</span><span class="intro-index mono">01</span></div>
      <h2 id="practice-board-title"><ScrambleText ref="title" text="从一道题开始" /></h2>
      <p class="intro-description">把思路写成代码。<br />选一道题，开始今天的练习。</p>
      <button class="decode-button mono" type="button" aria-label="重新播放标题解码动画" @click="title?.replay()"><span aria-hidden="true">↻</span> 重新解码</button>
      <ol class="practice-steps">
        <li><span class="mono">01</span><div><strong>读懂题意</strong><p>从输入、输出与约束出发。</p></div></li>
        <li><span class="mono">02</span><div><strong>写下解法</strong><p>让每一步思路都有据可循。</p></div></li>
        <li><span class="mono">03</span><div><strong>提交验证</strong><p>用评测结果检验你的答案。</p></div></li>
      </ol>
      <div class="practice-total">
        <template v-if="total !== null"><strong>{{ total }}</strong><span>道{{ keyword.trim() ? '匹配的' : '公开的' }}编程题</span></template>
        <span v-else>{{ error ? '题库数据暂不可用' : '正在读取题库…' }}</span>
      </div>
      <RouterLink to="/questions" class="all-questions">浏览完整题库 <span aria-hidden="true">↗</span></RouterLink>
    </div>

    <div class="practice-list-panel">
      <div class="panel-top">
        <div><span class="mono panel-index">PROBLEM LIST / 题目列表</span><h3>找到下一道练习</h3></div>
        <span class="live-label">公开题库</span>
      </div>
      <label class="search-label" for="practice-search">按标题搜索</label>
      <div class="search-field"><span aria-hidden="true">⌕</span><input id="practice-search" v-model="keyword" type="search" placeholder="输入算法或题目关键词…" /></div>
      <div class="results-region" :aria-busy="loading">
        <div v-if="loading" class="list-state" role="status"><span class="state-symbol mono" aria-hidden="true">[ … ]</span>正在加载题目…</div>
        <div v-else-if="error" class="list-state" role="alert"><span>{{ error }}</span><button type="button" @click="loadQuestions">重新加载</button></div>
        <div v-else-if="questions.length === 0" class="list-state" role="status"><strong>没有找到匹配的题目</strong><span>试试其他关键词，或清空搜索。</span></div>
        <div v-else class="question-rows">
          <RouterLink v-for="question in questions" :key="question.id" :to="`/questions/${question.id}`" class="question-row">
            <span class="question-number mono">#{{ question.id }}</span>
            <span class="question-title">{{ question.title }}</span>
            <span class="difficulty">{{ difficultyName(question.difficulty) }}</span>
            <span class="row-arrow" aria-hidden="true">↗</span>
          </RouterLink>
        </div>
      </div>
      <div class="list-footer">
        <span v-if="total !== null && total > 0">第 {{ page }} / {{ Math.ceil(total / size) }} 页</span>
        <span v-else>{{ loading ? '正在更新…' : error ? '等待重新加载' : '共 0 道题目' }}</span>
        <div class="page-actions">
          <button type="button" :disabled="page <= 1 || loading || total === null" @click="goPage(page - 1)">上一页</button>
          <button type="button" :disabled="total === null || page >= Math.ceil((total ?? 0) / size) || loading" @click="goPage(page + 1)">下一页</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.practice-board { max-width: 1360px; display: grid; grid-template-columns: minmax(320px, .78fr) minmax(0, 1.4fr); gap: 24px; padding-top: 28px; padding-bottom: 72px; font-size: 16px; line-height: 1.6; }
.practice-intro { background: #17191c; color: #fff; padding: 34px; display: flex; flex-direction: column; }
.intro-topline { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.eyebrow, .intro-index { color: #bfc4ca; font-size: 13px; letter-spacing: .06em; }
.practice-intro h2 { font-size: clamp(30px, 2.8vw, 40px); line-height: 1.35; letter-spacing: -.04em; margin: 28px 0 16px; }
.intro-description { color: #d5d8dc; font-size: 17px; line-height: 1.85; margin: 0; }
.decode-button { align-self: flex-start; border: 0; border-bottom: 1px solid #686d74; background: transparent; color: #d5d8dc; min-height: 44px; padding: 0; margin-top: 14px; font-size: 14px; cursor: pointer; }
.decode-button span { margin-right: 6px; }
.practice-steps { list-style: none; padding: 24px 0; margin: 22px 0 0; border-top: 1px solid #484c52; display: grid; gap: 19px; }
.practice-steps li { display: flex; gap: 16px; }
.practice-steps li > span { color: #a7adb5; font-size: 14px; padding-top: 3px; }
.practice-steps strong { font-size: 17px; font-weight: 600; }
.practice-steps p { color: #bfc4ca; font-size: 16px; margin: 3px 0 0; }
.practice-total { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; margin-top: 6px; min-height: 54px; }
.practice-total strong { font-size: 38px; line-height: 1.4; letter-spacing: -.04em; }
.practice-total span { color: #c3c8ce; font-size: 16px; }
.all-questions { display: flex; align-items: center; justify-content: space-between; min-height: 52px; margin-top: 14px; padding-top: 12px; border-top: 1px solid #484c52; color: #fff; font-size: 17px; font-weight: 600; text-decoration: none; }
.all-questions:hover, .decode-button:hover { color: #fff; text-decoration: underline; text-underline-offset: 5px; }
.practice-list-panel { background: var(--surface); border: 1px solid var(--line); padding: 32px; min-width: 0; display: flex; flex-direction: column; }
.panel-top { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 24px; }
.panel-index { color: var(--ink-secondary); font-size: 13px; letter-spacing: .04em; }
.panel-top h3 { font-size: 28px; line-height: 1.4; margin: 7px 0 0; letter-spacing: -.03em; }
.live-label { font-size: 14px; color: var(--ink-secondary); white-space: nowrap; border: 1px solid var(--line); padding: 5px 10px; }
.search-label { display: block; font-size: 16px; color: var(--ink-secondary); margin-bottom: 9px; }
.search-field { display: flex; align-items: center; border: 1px solid var(--line); background: #f7f7f7; min-height: 48px; }
.search-field > span { font-size: 26px; padding-left: 14px; }
.search-field:focus-within { outline: 2px solid var(--ink); outline-offset: 2px; }
.search-field input { width: 100%; min-width: 0; min-height: 48px; border: 0; border-radius: 0; background: transparent; color: var(--ink); padding: 10px 14px; font: inherit; font-size: 16px; outline: none; }
.results-region { margin-top: 22px; flex: 1; }
.question-rows { border-top: 1px solid var(--line); }
.question-row { display: grid; grid-template-columns: minmax(50px, auto) minmax(0, 1fr) 40px 20px; align-items: center; gap: 16px; min-height: 72px; padding: 12px 4px; border-bottom: 1px solid var(--line); color: var(--ink); text-decoration: none; }
.question-row:hover { background: #f2f2f2; }
.question-number { color: var(--ink-secondary); font-size: 14px; overflow-wrap: anywhere; max-width: 90px; }
.question-title { font-size: 18px; line-height: 1.5; font-weight: 600; overflow-wrap: anywhere; }
.difficulty { font-size: 14px; color: var(--ink-secondary); }
.row-arrow { font-size: 21px; }
.list-state { min-height: 360px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; color: var(--ink-secondary); font-size: 16px; text-align: center; }
.state-symbol { font-size: 24px; color: var(--ink); }
.list-state button, .page-actions button { min-height: 44px; border: 1px solid var(--line); background: #fff; color: var(--ink); padding: 8px 16px; font: inherit; font-size: 16px; cursor: pointer; border-radius: 0; }
.list-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; color: var(--ink-secondary); font-size: 16px; padding-top: 24px; }
.page-actions { display: flex; gap: 8px; }
.page-actions button:disabled { opacity: .45; cursor: default; }
.page-actions button:not(:disabled):hover, .list-state button:hover { background: #17191c; color: #fff; }
button:focus-visible, a:focus-visible { outline: 2px solid currentColor; outline-offset: 4px; }
@media (max-width: 980px) { .practice-board { grid-template-columns: minmax(280px, .8fr) minmax(0, 1.2fr); gap: 18px; } .practice-intro, .practice-list-panel { padding: 24px; } .panel-top { align-items: flex-start; flex-direction: column; gap: 10px; } .question-row { gap: 10px; } }
@media (max-width: 780px) { .practice-board { grid-template-columns: 1fr; } .practice-intro h2 { font-size: 36px; } .practice-steps { grid-template-columns: repeat(3, 1fr); gap: 18px; } .practice-steps li { flex-direction: column; gap: 4px; } .panel-top { flex-direction: row; } }
@media (max-width: 480px) { .practice-board { padding-top: 16px; padding-bottom: 40px; } .practice-intro, .practice-list-panel { padding: 22px 18px; } .practice-intro h2 { font-size: 30px; } .practice-steps { grid-template-columns: 1fr; gap: 16px; } .practice-steps li { flex-direction: row; gap: 14px; } .panel-top { flex-wrap: wrap; } .panel-top h3 { font-size: 25px; } .question-row { grid-template-columns: minmax(36px, auto) minmax(0, 1fr) 32px 14px; gap: 8px; } .question-number { max-width: 56px; font-size: 13px; } .question-title { font-size: 17px; } .list-footer { align-items: flex-start; flex-direction: column; } .page-actions { align-self: flex-end; } }
</style>
