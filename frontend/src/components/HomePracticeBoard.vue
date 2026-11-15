<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getActivePinia } from 'pinia'
import { RouterLink } from 'vue-router'
import { questionApi } from '@/api/question'
import { useUserStore } from '@/stores/user'
import type { Question } from '@/types'

const ROW_DROP_DURATION_MS = 460
const ROW_DROP_STAGGER_MS = 60
const ROW_DROP_BUFFER_MS = 180
const RANDOM_BATCH_SIZE = 5
const PROBE_PAGE_SIZE = 5
const FULL_FETCH_THRESHOLD = 50

const mode = ref<'browse' | 'random5'>('browse')
const keyword = ref('')
const page = ref(1)
const size = 4
const total = ref<number | null>(null)
const questions = ref<Question[]>([])
const loading = ref(true)
const error = ref('')
const isRowDropping = ref(false)
const unsolvedPickedCount = ref(0)

const userStore = getActivePinia() ? useUserStore() : null

const skeletonCount = computed(() => (mode.value === 'random5' ? RANDOM_BATCH_SIZE : size))
const isUnsolvedPriorityBatch = computed(
  () => mode.value === 'random5' && !!userStore?.isLoggedIn && unsolvedPickedCount.value > 0,
)
const footerTelemetry = computed(() => {
  if (error.value) return 'SYNC.ERR'
  if (loading.value) return mode.value === 'random5' ? 'SAMPLING' : 'SYNCING'
  if (mode.value === 'random5') return isUnsolvedPriorityBatch.value ? 'UNSOLVED.X5' : 'RANDOM.X5'
  return 'BANK.READY'
})

let requestNumber = 0
let searchTimer: ReturnType<typeof setTimeout> | null = null
let isUnmounted = false
let dropRafId: number | null = null
let dropRestartTimerId: ReturnType<typeof setTimeout> | null = null
let dropFallbackTimerId: ReturnType<typeof setTimeout> | null = null

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function clearDropTimers() {
  if (
    dropRafId !== null &&
    typeof window !== 'undefined' &&
    typeof window.cancelAnimationFrame === 'function'
  ) {
    window.cancelAnimationFrame(dropRafId)
  }
  dropRafId = null
  if (dropRestartTimerId !== null) {
    clearTimeout(dropRestartTimerId)
    dropRestartTimerId = null
  }
  if (dropFallbackTimerId !== null) {
    clearTimeout(dropFallbackTimerId)
    dropFallbackTimerId = null
  }
}

function scheduleRowDropFallback() {
  if (dropFallbackTimerId !== null) {
    clearTimeout(dropFallbackTimerId)
  }
  const totalMs =
    Math.max(questions.value.length - 1, 0) * ROW_DROP_STAGGER_MS +
    ROW_DROP_DURATION_MS +
    ROW_DROP_BUFFER_MS
  dropFallbackTimerId = setTimeout(() => {
    dropFallbackTimerId = null
    if (!isUnmounted) {
      isRowDropping.value = false
    }
  }, totalMs)
}

function activateRowDrop() {
  if (isUnmounted || prefersReducedMotion() || questions.value.length === 0) {
    isRowDropping.value = false
    return
  }
  isRowDropping.value = true
  scheduleRowDropFallback()
}

function triggerRowDrop() {
  clearDropTimers()
  if (isUnmounted || prefersReducedMotion() || questions.value.length === 0) {
    isRowDropping.value = false
    return
  }

  if (!isRowDropping.value) {
    activateRowDrop()
    return
  }

  isRowDropping.value = false
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    dropRafId = window.requestAnimationFrame(() => {
      dropRafId = null
      activateRowDrop()
    })
  } else {
    dropRestartTimerId = setTimeout(() => {
      dropRestartTimerId = null
      activateRowDrop()
    }, 16)
  }
}

function rowDropStyle(index: number) {
  if (!isRowDropping.value) return undefined
  return { '--row-delay': `${index * ROW_DROP_STAGGER_MS}ms` }
}

function handleRowDropEnd(index: number, event: AnimationEvent) {
  if (event.target !== event.currentTarget) return
  if (index < questions.value.length - 1) return
  clearDropTimers()
  isRowDropping.value = false
}

function resetResults(options: { preserveTotal?: boolean } = {}) {
  clearDropTimers()
  isRowDropping.value = false
  loading.value = true
  error.value = ''
  if (!options.preserveTotal) {
    total.value = null
  }
  questions.value = []
}

function shuffleItems<T>(items: T[]): T[] {
  const copy = items.slice()
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
  }
  return copy
}

function sampleDistinctPages(totalPages: number, count: number): number[] {
  if (totalPages <= count) {
    return Array.from({ length: totalPages }, (_, index) => index + 1)
  }
  const picked = new Set<number>()
  while (picked.size < count) {
    picked.add(Math.floor(Math.random() * totalPages) + 1)
  }
  return Array.from(picked)
}

async function sampleQuestionsFromPool(
  needed: number,
  userStatus: 'UNSOLVED' | undefined,
  excludeIds: Set<number>,
  isStale: () => boolean,
): Promise<{ items: Question[]; poolTotal: number } | null> {
  if (needed <= 0) return { items: [], poolTotal: 0 }

  const probe = await questionApi.list({
    page: 1,
    size: PROBE_PAGE_SIZE,
    userStatus,
  })
  if (isStale()) return null

  const poolTotal = probe.total
  if (poolTotal <= 0) return { items: [], poolTotal: 0 }

  let rawCandidates: Question[]
  if (poolTotal <= PROBE_PAGE_SIZE) {
    rawCandidates = probe.list
  } else if (poolTotal <= FULL_FETCH_THRESHOLD) {
    const full = await questionApi.list({
      page: 1,
      size: poolTotal,
      userStatus,
    })
    if (isStale()) return null
    rawCandidates = full.list
  } else {
    const totalPages = Math.ceil(poolTotal / PROBE_PAGE_SIZE)
    const targetPages = sampleDistinctPages(totalPages, Math.min(needed, totalPages))
    const pageLists = await Promise.all(
      targetPages.map(async (targetPage) => {
        if (targetPage === 1) return probe.list
        const res = await questionApi.list({
          page: targetPage,
          size: PROBE_PAGE_SIZE,
          userStatus,
        })
        return res.list
      }),
    )
    if (isStale()) return null
    rawCandidates = pageLists.flat()
  }

  const seenInBatch = new Set<number>()
  const filtered = rawCandidates.filter((q) => {
    if (excludeIds.has(q.id) || seenInBatch.has(q.id)) return false
    seenInBatch.add(q.id)
    return true
  })

  return {
    items: shuffleItems(filtered).slice(0, needed),
    poolTotal,
  }
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
    if (request !== requestNumber || isUnmounted) return
    questions.value = result.list
    total.value = result.total
    if (result.list.length > 0) {
      triggerRowDrop()
    }
  } catch {
    if (request !== requestNumber || isUnmounted) return
    error.value = '题目暂时无法加载，请稍后重试。'
  } finally {
    if (request === requestNumber && !isUnmounted) {
      loading.value = false
    }
  }
}

async function drawRandomFive() {
  if (searchTimer) clearTimeout(searchTimer)
  const request = ++requestNumber
  const stale = () => request !== requestNumber || isUnmounted

  mode.value = 'random5'
  unsolvedPickedCount.value = 0
  resetResults({ preserveTotal: true })

  try {
    const seenIds = new Set<number>()
    const picked: Question[] = []
    let bankTotal: number | null = total.value

    if (userStore?.isLoggedIn) {
      const unsolved = await sampleQuestionsFromPool(
        RANDOM_BATCH_SIZE,
        'UNSOLVED',
        seenIds,
        stale,
      )
      if (stale() || !unsolved) return
      for (const question of unsolved.items) {
        seenIds.add(question.id)
        picked.push(question)
      }
      unsolvedPickedCount.value = picked.length
    }

    if (picked.length < RANDOM_BATCH_SIZE) {
      const fallback = await sampleQuestionsFromPool(
        RANDOM_BATCH_SIZE - picked.length,
        undefined,
        seenIds,
        stale,
      )
      if (stale() || !fallback) return
      bankTotal = fallback.poolTotal
      for (const question of fallback.items) {
        if (!seenIds.has(question.id)) {
          seenIds.add(question.id)
          picked.push(question)
        }
      }
    }

    if (stale()) return
    questions.value = picked
    total.value = bankTotal ?? total.value ?? picked.length
    if (picked.length > 0) {
      triggerRowDrop()
    }
  } catch {
    if (stale()) return
    error.value = '随机抽题失败，请稍后重试。'
  } finally {
    if (!stale()) {
      loading.value = false
    }
  }
}

function exitRandomMode() {
  if (loading.value) return
  mode.value = 'browse'
  unsolvedPickedCount.value = 0
  loadQuestions()
}

function retryCurrentMode() {
  if (mode.value === 'random5') {
    drawRandomFive()
  } else {
    loadQuestions()
  }
}

watch(keyword, () => {
  if (mode.value !== 'browse') return
  requestNumber++
  page.value = 1
  resetResults()
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(loadQuestions, 300)
})

function goPage(next: number) {
  if (
    mode.value !== 'browse' ||
    loading.value ||
    total.value === null ||
    next < 1 ||
    next > Math.ceil(total.value / size)
  )
    return
  page.value = next
  loadQuestions()
}

onMounted(loadQuestions)
onBeforeUnmount(() => {
  isUnmounted = true
  requestNumber++
  if (searchTimer) clearTimeout(searchTimer)
  clearDropTimers()
})

function difficultyName(value: string) {
  if (value === 'EASY') return '简单'
  if (value === 'HARD') return '困难'
  return '中等'
}

function difficultyTone(value: string) {
  if (value === 'EASY') return 'is-easy'
  if (value === 'HARD') return 'is-hard'
  return 'is-medium'
}
</script>

<template>
  <section class="practice-board container" aria-labelledby="practice-board-title">
    <div class="practice-card">
      <header class="practice-intro">
        <div class="intro-copy">
          <div class="intro-eyebrow">
            <span
              class="hud-dot"
              :class="{ ready: !error && total !== null, warning: !!error }"
              aria-hidden="true"
            ></span>
            <span class="eyebrow-code mono">// DAILY PRACTICE · PUBLIC QUESTION BANK</span>
            <span class="hud-tag">PRACTICE HUD</span>
          </div>
          <h2 id="practice-board-title">今天，解一道题</h2>
          <p class="intro-description">读题、构思、提交。把想法变成可运行的答案。</p>
        </div>

        <div class="intro-footer">
          <div class="practice-total">
            <template v-if="mode === 'random5' && !error && questions.length > 0">
              <strong class="mono">{{ questions.length }}</strong>
              <span>{{ total !== null ? `/ ${total} 道随机抽取` : '道随机抽取' }}</span>
            </template>
            <template v-else-if="total !== null">
              <strong class="mono">{{ total }}</strong>
              <span>道{{ keyword.trim() ? '匹配的' : '公开的' }}编程题</span>
            </template>
            <span v-else>{{
              error
                ? '题库数据暂不可用'
                : mode === 'random5'
                  ? '正在抽取随机题目…'
                  : '正在读取题库…'
            }}</span>
          </div>
          <div class="intro-actions">
            <button
              type="button"
              class="btn-random-5"
              :disabled="loading"
              @click="drawRandomFive"
            >
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <rect x="3" y="3" width="14" height="14" />
                <circle cx="7" cy="7" r="1.15" fill="currentColor" stroke="none" />
                <circle cx="13" cy="7" r="1.15" fill="currentColor" stroke="none" />
                <circle cx="10" cy="10" r="1.15" fill="currentColor" stroke="none" />
                <circle cx="7" cy="13" r="1.15" fill="currentColor" stroke="none" />
                <circle cx="13" cy="13" r="1.15" fill="currentColor" stroke="none" />
              </svg>
              <span>{{
                loading && mode === 'random5'
                  ? '抽题中…'
                  : mode === 'random5'
                    ? '换一批 5 题'
                    : '随机刷 5 题'
              }}</span>
            </button>
            <RouterLink to="/questions" class="all-questions">
              <span>完整题库</span>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 15 15 5m-7 0h7v7" /></svg>
            </RouterLink>
          </div>
        </div>
      </header>

      <div class="practice-list-panel">
        <div class="panel-toolbar">
          <div class="panel-top">
            <span class="section-tick" aria-hidden="true"></span>
            <h3>{{ mode === 'random5' ? '随机 5 题速刷' : '选择下一道练习' }}</h3>
            <span class="live-label mono">{{
              mode === 'random5'
                ? isUnsolvedPriorityBatch
                  ? '优先未解答'
                  : '全库随机'
                : '公开题库'
            }}</span>
          </div>
          <template v-if="mode === 'browse'">
            <label class="search-label" for="practice-search">按题目名称搜索</label>
            <div class="search-field">
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <circle cx="9" cy="9" r="5.5" />
                <path d="m13 13 4 4" />
              </svg>
              <input
                id="practice-search"
                v-model="keyword"
                type="search"
                placeholder="输入算法或题目关键词"
                aria-label="输入算法或题目关键词"
              />
            </div>
          </template>
          <button
            v-else
            type="button"
            class="btn-back-browse"
            :disabled="loading"
            @click="exitRandomMode"
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <path d="M15 10H5m0 0 4-4m-4 4 4 4" />
            </svg>
            <span>返回列表</span>
          </button>
        </div>

        <div class="results-region" :aria-busy="loading">
          <div class="question-list-head" aria-hidden="true">
            <span>题号</span>
            <span>题目名称</span>
            <span>难度</span>
            <span>进入</span>
          </div>

          <div v-if="loading" class="list-state is-loading" role="status">
            <span class="sr-only">{{
              mode === 'random5' ? '正在抽取随机题目…' : '正在读取题库…'
            }}</span>
            <div v-for="index in skeletonCount" :key="index" class="skeleton-row" aria-hidden="true">
              <span class="skeleton-cell is-id"></span>
              <span class="skeleton-cell is-title"></span>
              <span class="skeleton-cell is-badge"></span>
              <span class="skeleton-cell is-arrow"></span>
            </div>
          </div>

          <div v-else-if="error" class="list-state is-message" role="alert">
            <span>{{ error }}</span>
            <button type="button" @click="retryCurrentMode">重新加载</button>
          </div>

          <div v-else-if="questions.length === 0" class="list-state is-message" role="status">
            <strong>没有找到匹配的题目</strong>
            <span>换个关键词，或清空搜索。</span>
          </div>

          <div v-else class="question-rows">
            <RouterLink
              v-for="(question, index) in questions"
              :key="question.id"
              :to="`/questions/${question.id}`"
              class="question-row"
              :class="{ 'is-dropping': isRowDropping }"
              :style="rowDropStyle(index)"
              @animationend="handleRowDropEnd(index, $event)"
            >
              <span class="question-number mono">#{{ question.id }}</span>
              <span class="question-title">{{ question.title }}</span>
              <span class="difficulty" :class="difficultyTone(question.difficulty)">
                {{ difficultyName(question.difficulty) }}
              </span>
              <span class="row-arrow-wrap" aria-hidden="true">
                <svg class="row-arrow" viewBox="0 0 20 20">
                  <path d="M5 15 15 5m-7 0h7v7" />
                </svg>
              </span>
            </RouterLink>
          </div>
        </div>

        <footer class="list-footer">
          <div class="footer-meta">
            <span v-if="mode === 'random5'" class="page-count">
              {{
                loading
                  ? '正在随机抽取 5 题…'
                  : error
                    ? '等待重试'
                    : `本轮已抽 ${questions.length} 题 · 点击任意一题开刷`
              }}
            </span>
            <span v-else-if="total !== null && total > 0" class="page-count mono">
              {{ page }} / {{ Math.ceil(total / size) }} 页
            </span>
            <span v-else class="page-count">
              {{ loading ? '正在更新…' : error ? '等待重新加载' : '共 0 道题目' }}
            </span>
            <span
              v-if="mode !== 'random5'"
              class="hud-telemetry mono"
              :class="{ ready: !error && !loading, warning: !!error }"
            >
              {{ footerTelemetry }}
            </span>
          </div>
          <span
            v-if="mode === 'random5'"
            class="hud-telemetry mono"
            :class="{ ready: !error && !loading, warning: !!error }"
          >
            {{ footerTelemetry }}
          </span>
          <div v-else class="page-actions">
            <button type="button" :disabled="page <= 1 || loading || total === null" @click="goPage(page - 1)">
              上一页
            </button>
            <button
              type="button"
              :disabled="total === null || page >= Math.ceil((total ?? 0) / size) || loading"
              @click="goPage(page + 1)"
            >
              下一页
            </button>
          </div>
        </footer>
      </div>
    </div>
  </section>
</template>

<style scoped>
.practice-board {
  --hud-accent: var(--kece-accent, #137969);
  --hud-warning: #b45309;
  --hud-danger: #b91c1c;
  --board-gutter: clamp(16px, 2.2vw, 32px);
  padding-bottom: 48px;
  font-variant-numeric: tabular-nums;
}

.practice-card {
  position: relative;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  background-color: var(--surface-white, #ffffff);
  background-image:
    linear-gradient(to right, rgba(17, 17, 17, 0.025) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(17, 17, 17, 0.025) 1px, transparent 1px);
  background-size: 20px 20px;
  border: 1px solid var(--line, #e2e8f0);
  border-top: 2px solid var(--hud-accent);
  box-shadow:
    0 12px 28px rgba(0, 0, 0, 0.07),
    0 2px 6px rgba(0, 0, 0, 0.04);
}

.practice-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 24px var(--board-gutter);
  background: linear-gradient(180deg, #f8fafc 0%, rgba(255, 255, 255, 0.94) 100%);
  border-bottom: 1px solid var(--line, #e2e8f0);
}

.intro-copy {
  min-width: 0;
}

.intro-eyebrow {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.hud-dot {
  position: relative;
  flex-shrink: 0;
  width: 7px;
  height: 7px;
  background: var(--ink-secondary, #64748b);
}

.hud-dot.ready {
  background: var(--hud-accent);
}

.hud-dot.ready::after {
  position: absolute;
  inset: -3px;
  border: 1px solid var(--hud-accent);
  content: '';
  opacity: 0;
  animation: board-hud-pulse 2.2s ease-out infinite;
}

.hud-dot.warning {
  background: var(--hud-warning);
}

.eyebrow-code {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--ink-secondary, #64748b);
}

.hud-tag {
  padding: 2px 6px;
  border: 1px solid var(--line, #e2e8f0);
  background: #ffffff;
  color: var(--ink-secondary, #64748b);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.practice-intro h2 {
  margin: 0 0 6px;
  color: var(--ink, #111111);
  font-size: clamp(24px, 2.3vw, 30px);
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.025em;
}

.intro-description {
  margin: 0;
  color: var(--ink-secondary, #52525b);
  font-size: 14px;
  line-height: 1.6;
}

.intro-footer {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  flex-shrink: 0;
}

.practice-total {
  display: inline-flex;
  align-items: baseline;
  gap: 7px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--ink-secondary, #52525b);
}

.practice-total strong {
  color: var(--hud-accent);
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.02em;
}

.intro-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-random-5 {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 13px;
  border: 1px solid var(--hud-accent);
  border-radius: 0;
  background: var(--hud-accent);
  color: #ffffff;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    transform 0.1s ease;
}

.btn-random-5:hover:not(:disabled) {
  background: #0f6356;
  border-color: #0f6356;
}

.btn-random-5:active:not(:disabled) {
  transform: translateY(1px);
}

.btn-random-5:disabled {
  opacity: 0.6;
  cursor: default;
}

.all-questions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--line, #e2e8f0);
  background: #ffffff;
  color: var(--ink, #111111);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    color 0.16s ease;
}

.all-questions:hover {
  background: #eef6f4;
  border-color: var(--hud-accent);
  color: var(--hud-accent);
}

.all-questions:hover svg {
  transform: translate(1px, -1px);
}

.btn-random-5 svg,
.all-questions svg,
.btn-back-browse svg,
.row-arrow,
.search-field svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.75;
  flex-shrink: 0;
  transition: transform 0.16s ease;
}

.practice-list-panel {
  padding: 20px var(--board-gutter) 18px;
  min-width: 0;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px 24px;
  margin-bottom: 16px;
}

.panel-top {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.section-tick {
  width: 3px;
  height: 14px;
  background: var(--hud-accent);
  flex-shrink: 0;
}

.panel-top h3 {
  margin: 0;
  color: var(--ink, #111111);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.live-label {
  padding: 2px 7px;
  border: 1px solid var(--line, #e2e8f0);
  background: #f8fafc;
  color: var(--ink-secondary, #64748b);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.search-label,
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}

.search-field {
  position: relative;
  display: flex;
  align-items: center;
  width: min(100%, 340px);
  min-height: 40px;
  border: 1px solid var(--line, #e2e8f0);
  background: #ffffff;
  color: var(--ink-secondary, #64748b);
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease;
}

.search-field svg {
  margin-left: 12px;
}

.search-field input {
  min-width: 0;
  width: 100%;
  min-height: 38px;
  padding: 6px 12px 6px 8px;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: var(--ink, #111111);
  font-size: 13.5px;
  font-family: inherit;
  outline: none;
}

.search-field input::placeholder {
  color: var(--ink-secondary, #64748b);
}

.search-field:focus-within {
  border-color: var(--hud-accent);
  box-shadow: 0 0 0 2px rgba(19, 121, 105, 0.14);
  color: var(--hud-accent);
}

.results-region {
  min-width: 0;
}

.question-list-head {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 72px 28px;
  gap: 16px;
  align-items: center;
  height: 32px;
  padding: 0 18px;
  background: #f8fafc;
  border-top: 1px solid var(--line, #e2e8f0);
  border-bottom: 1px solid var(--line, #e2e8f0);
  color: var(--ink-secondary, #64748b);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.question-list-head span:nth-child(3) {
  text-align: center;
}

.question-list-head span:last-child {
  text-align: right;
}

.question-rows {
  display: flex;
  flex-direction: column;
}

.question-row {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 72px 28px;
  gap: 16px;
  align-items: center;
  min-height: 56px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--line, #e2e8f0);
  background: rgba(255, 255, 255, 0.88);
  text-decoration: none;
  color: var(--ink, #111111);
  transition:
    background-color 0.15s ease,
    box-shadow 0.15s ease;
}

.question-row:hover {
  background: #eef6f4;
  box-shadow: inset 3px 0 0 var(--hud-accent);
}

.question-row:hover .row-arrow {
  color: var(--hud-accent);
  transform: translate(2px, -2px);
}

.question-row.is-dropping {
  will-change: transform, opacity;
  animation: practice-row-drop 460ms var(--row-delay, 0ms) linear both;
}

.question-number {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary, #64748b);
  overflow-wrap: anywhere;
}

.question-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink, #111111);
}

.difficulty {
  justify-self: center;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-width: 58px;
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  border: 1px solid var(--line, #e2e8f0);
}

.difficulty::before {
  width: 5px;
  height: 5px;
  background: currentColor;
  content: '';
}

.difficulty.is-easy {
  color: var(--hud-accent);
  background: #f0fdf9;
  border-color: rgba(19, 121, 105, 0.28);
}

.difficulty.is-medium {
  color: var(--hud-warning);
  background: #fffbeb;
  border-color: rgba(180, 83, 9, 0.28);
}

.difficulty.is-hard {
  color: var(--hud-danger);
  background: #fef2f2;
  border-color: rgba(185, 28, 28, 0.28);
}

.row-arrow-wrap {
  display: inline-flex;
  justify-content: flex-end;
  color: var(--ink-secondary, #64748b);
}

.list-state {
  min-height: 224px;
  border-bottom: 1px solid var(--line, #e2e8f0);
}

.list-state.is-loading {
  display: flex;
  flex-direction: column;
}

.skeleton-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 72px 28px;
  gap: 16px;
  align-items: center;
  min-height: 56px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--line, #e2e8f0);
}

.skeleton-row:last-child {
  border-bottom: 0;
}

.skeleton-cell {
  display: block;
  height: 12px;
  background: var(--surface-hover, #f1f5f9);
}

.skeleton-cell.is-id {
  width: 34px;
}

.skeleton-cell.is-title {
  width: min(62%, 220px);
}

.skeleton-cell.is-badge {
  justify-self: center;
  width: 52px;
  height: 22px;
}

.skeleton-cell.is-arrow {
  justify-self: end;
  width: 14px;
  height: 14px;
}

.list-state.is-message {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--ink-secondary, #64748b);
  font-size: 14px;
}

.list-state strong {
  color: var(--ink, #111111);
}

.list-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 14px;
  font-size: 13px;
  color: var(--ink-secondary, #64748b);
}

.footer-meta {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.page-count {
  font-weight: 500;
}

.hud-telemetry {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-secondary, #64748b);
}

.hud-telemetry.ready {
  color: var(--hud-accent);
}

.hud-telemetry.warning {
  color: var(--hud-warning);
}

.page-actions {
  display: flex;
  gap: 8px;
}

.btn-back-browse {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
}

.btn-back-browse:not(:disabled):hover svg {
  transform: translateX(-2px);
}

.btn-back-browse,
.page-actions button,
.list-state button {
  min-height: 36px;
  padding: 6px 14px;
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 0;
  background: #ffffff;
  color: var(--ink, #111111);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    color 0.16s ease;
}

.btn-back-browse:disabled,
.page-actions button:disabled {
  opacity: 0.45;
  cursor: default;
}

.btn-back-browse:not(:disabled):hover,
.page-actions button:not(:disabled):hover,
.list-state button:hover {
  background: #f8fafc;
  border-color: var(--ink, #111111);
}

a:focus-visible,
button:focus-visible {
  outline: 2px solid var(--hud-accent);
  outline-offset: 2px;
}

@keyframes board-hud-pulse {
  0% {
    opacity: 0.65;
    transform: scale(0.95);
  }
  100% {
    opacity: 0;
    transform: scale(1.9);
  }
}

@keyframes practice-row-drop {
  0% {
    opacity: 0;
    transform: translate3d(0, -22px, 0);
    animation-timing-function: cubic-bezier(0.55, 0.05, 0.92, 0.56);
  }
  45% {
    opacity: 1;
    transform: translate3d(0, 0, 0);
    animation-timing-function: cubic-bezier(0.2, 0.68, 0.35, 1);
  }
  66% {
    transform: translate3d(0, -4.5px, 0);
    animation-timing-function: cubic-bezier(0.58, 0.05, 0.9, 0.55);
  }
  82% {
    transform: translate3d(0, 0, 0);
    animation-timing-function: cubic-bezier(0.22, 0.66, 0.38, 1);
  }
  91% {
    transform: translate3d(0, -1.5px, 0);
    animation-timing-function: ease-in;
  }
  100% {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

@media (max-width: 780px) {
  .practice-intro {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }

  .intro-footer {
    flex-direction: row;
    justify-content: space-between;
    align-items: center;
    width: 100%;
  }

  .panel-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .search-field {
    width: 100%;
  }

  .question-list-head,
  .question-row,
  .skeleton-row {
    grid-template-columns: 52px minmax(0, 1fr) 60px 20px;
    gap: 10px;
    padding-inline: 12px;
  }

  .question-number {
    font-size: 12px;
  }

  .question-title {
    font-size: 14px;
  }

  .difficulty {
    min-width: 52px;
    padding: 2px 6px;
    font-size: 11px;
  }
}

@media (max-width: 480px) {
  .practice-intro,
  .practice-list-panel {
    padding-inline: 16px;
  }

  .hud-tag,
  .live-label {
    display: none;
  }

  .list-footer {
    flex-wrap: wrap;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hud-dot.ready::after,
  .question-row.is-dropping {
    animation: none !important;
    will-change: auto;
    opacity: 1;
    transform: none;
  }
}
</style>
