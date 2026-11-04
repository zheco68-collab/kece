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
          <ScrambleText text="读题、构思、提交。用一次完整评测" :delay="40" />
          <ScrambleText text="把今天的想法变成可运行的答案。" :delay="80" />
        </p>
      </div>
      <ol class="practice-steps">
        <li><span class="step-number mono"><ScrambleText text="01" :delay="80" /></span><strong><ScrambleText text="读懂题意" :delay="100" /></strong></li>
        <li><span class="step-number mono"><ScrambleText text="02" :delay="120" /></span><strong><ScrambleText text="形成思路" :delay="140" /></strong></li>
        <li><span class="step-number mono"><ScrambleText text="03" :delay="160" /></span><strong><ScrambleText text="提交验证" :delay="180" /></strong></li>
      </ol>
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
.practice-board {
  display: grid;
  grid-template-columns: minmax(280px, 0.7fr) minmax(0, 1.5fr);
  gap: 20px;
  padding-top: 0;
  padding-bottom: 44px;
  font-size: 15px;
  line-height: 1.55;
}

.practice-intro {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 28px;
  background: #17191c;
  color: #fff;
}

.intro-copy {
  max-width: 34ch;
}

.practice-intro h2 {
  margin: 0 0 12px;
  font-size: clamp(28px, 2.5vw, 36px);
  line-height: 1.24;
  letter-spacing: -0.04em;
}

.intro-description {
  display: grid;
  gap: 2px;
  margin: 0;
  color: #d5d8dc;
  font-size: 15px;
  line-height: 1.75;
}

.all-questions svg,
.row-arrow,
.search-field svg {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: square;
  stroke-linejoin: miter;
}

.all-questions svg {
  width: 18px;
  height: 18px;
}

.practice-steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  list-style: none;
  margin: 24px 0 0;
  padding: 16px 0;
  border-top: 1px solid #484c52;
  border-bottom: 1px solid #484c52;
}

.practice-steps li {
  display: grid;
  gap: 5px;
  padding: 0 12px;
  border-right: 1px solid #484c52;
}

.practice-steps li:first-child {
  padding-left: 0;
}

.practice-steps li:last-child {
  padding-right: 0;
  border-right: 0;
}

.step-number {
  color: #9ba2aa;
  font-size: 12px;
}

.practice-steps strong {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}

.intro-footer {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-top: auto;
  padding-top: 20px;
}

.practice-total {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.practice-total strong {
  font-size: 30px;
  line-height: 1;
  letter-spacing: -0.04em;
}

.practice-total span {
  color: #c3c8ce;
  font-size: 13px;
}

.all-questions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}

.all-questions:hover {
  color: #fff;
  text-decoration: underline;
  text-underline-offset: 5px;
}

.practice-list-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 28px;
  border: 1px solid var(--line);
  background: var(--surface);
}

.panel-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.panel-top h3 {
  margin: 0;
  font-size: 24px;
  line-height: 1.35;
  letter-spacing: -0.03em;
}

.live-label {
  padding: 5px 9px;
  border: 1px solid var(--line);
  color: var(--ink-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.search-label {
  display: block;
  margin-bottom: 7px;
  color: var(--ink-secondary);
  font-size: 14px;
}

.search-field {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 44px;
  border: 1px solid var(--line);
  background: #f7f7f7;
}

.search-field svg {
  z-index: 1;
  width: 18px;
  height: 18px;
  margin-left: 13px;
  flex: 0 0 auto;
}

.search-placeholder {
  position: absolute;
  left: 43px;
  color: var(--ink-tertiary);
  font-size: 14px;
  pointer-events: none;
}

.search-field:focus-within {
  outline: 2px solid var(--ink);
  outline-offset: 2px;
}

.search-field input {
  width: 100%;
  min-width: 0;
  min-height: 42px;
  padding: 9px 12px;
  border: 0;
  border-radius: 0;
  outline: none;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 15px;
}

.results-region {
  flex: 1;
  margin-top: 14px;
}

.question-rows {
  border-top: 1px solid var(--line);
}

.question-row {
  display: grid;
  grid-template-columns: minmax(44px, auto) minmax(0, 1fr) 36px 18px;
  align-items: center;
  gap: 14px;
  min-height: 58px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
  text-decoration: none;
}

.question-row:hover {
  background: #f2f2f2;
}

.question-number {
  max-width: 80px;
  overflow-wrap: anywhere;
  color: var(--ink-secondary);
  font-size: 13px;
}

.question-title {
  min-width: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.difficulty {
  color: var(--ink-secondary);
  font-size: 13px;
}

.row-arrow {
  width: 18px;
  height: 18px;
}

.list-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 232px;
  color: var(--ink-secondary);
  font-size: 15px;
  text-align: center;
}

.list-state button,
.page-actions button {
  min-height: 40px;
  padding: 7px 14px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: #fff;
  color: var(--ink);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.list-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding-top: 14px;
  color: var(--ink-secondary);
  font-size: 14px;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.page-actions button:disabled {
  opacity: 0.45;
  cursor: default;
}

.page-actions button:not(:disabled):hover,
.list-state button:hover {
  background: #17191c;
  color: #fff;
}

button:focus-visible,
a:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 4px;
}

@media (max-width: 980px) {
  .practice-board {
    grid-template-columns: minmax(250px, 0.75fr) minmax(0, 1.25fr);
    gap: 16px;
  }

  .practice-intro,
  .practice-list-panel {
    padding: 22px;
  }

  .practice-steps {
    grid-template-columns: 1fr;
    gap: 9px;
  }

  .practice-steps li {
    grid-template-columns: 24px 1fr;
    gap: 8px;
    padding: 0;
    border-right: 0;
  }

  .intro-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
}

@media (max-width: 780px) {
  .practice-board {
    grid-template-columns: 1fr;
  }

  .practice-steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0;
  }

  .practice-steps li {
    display: grid;
    grid-template-columns: 1fr;
    gap: 5px;
    padding: 0 12px;
    border-right: 1px solid #484c52;
  }

  .practice-steps li:first-child {
    padding-left: 0;
  }

  .practice-steps li:last-child {
    padding-right: 0;
    border-right: 0;
  }

  .intro-footer {
    align-items: flex-end;
    flex-direction: row;
  }
}

@media (max-width: 480px) {
  .practice-board {
    padding-top: 0;
    padding-bottom: 40px;
  }

  .practice-intro,
  .practice-list-panel {
    padding: 20px 18px;
  }

  .practice-intro h2 {
    font-size: 28px;
  }

  .panel-top h3 {
    font-size: 22px;
  }

  .question-row {
    grid-template-columns: minmax(34px, auto) minmax(0, 1fr) 30px 16px;
    gap: 8px;
  }

  .question-number {
    max-width: 52px;
    font-size: 12px;
  }

  .question-title {
    font-size: 15px;
  }

  .list-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .page-actions {
    align-self: flex-end;
  }
}
</style>
