<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { questionApi } from '@/api/question'
import { submissionApi } from '@/api/submission'
import Pagination from '@/components/Pagination.vue'
import { useBankUrlSync, type BankStatusFilter } from '@/shared/composables/useBankUrlSync'
import { pickRandomWithUnsolvedPriority } from '@/shared/lib/randomSample'
import { useUserStore } from '@/stores/user'
import '@/styles/question-bank.css'
import type { Difficulty, Question } from '@/types'
import { DIFFICULTY_TONE_MAP, formatLanguageLabel, sortLanguages } from '@/utils/questionMeta'
import { DIFFICULTY_LABELS as difficultyLabels } from '@/utils/submission'

const router = useRouter()
const userStore = useUserStore()

const difficultyLevels: Array<Difficulty | ''> = ['', 'EASY', 'MEDIUM', 'HARD']
const statusOptions: ReadonlyArray<{ value: BankStatusFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'UNSOLVED', label: '未解答' },
  { value: 'SOLVED', label: '已通过' },
]

const {
  keyword,
  difficulty,
  tag: selectedTag,
  status: statusFilter,
  page,
  reset: resetUrlState,
} = useBankUrlSync<Difficulty>({
  fields: ['keyword', 'difficulty', 'tag', 'status', 'page'],
  validDifficulties: ['EASY', 'MEDIUM', 'HARD'],
})

const allTags = ref<string[]>([])
const showTags = ref(Boolean(selectedTag.value))
const size = 10

const list = ref<Question[]>([])
const total = ref(0)
const globalTotal = ref(0)
const loading = ref(true)
const randomLoading = ref(false)
const error = ref('')

const solved = ref<Set<number>>(new Set())
const droppingIds = ref<Set<number>>(new Set())
let listRequestId = 0
let randomRequestId = 0
let globalProbeInFlight = false
let isUnmounted = false

const keywordModel = computed({
  get: () => keyword.value,
  set: (value: string) => {
    if (value === keyword.value) return
    page.value = 1
    keyword.value = value
  },
})

const hasActiveFilters = computed(() =>
  Boolean(
    keyword.value.trim() || difficulty.value || selectedTag.value || statusFilter.value !== 'ALL',
  ),
)

const progressTotal = computed(() => globalTotal.value || total.value)
const solvedCount = computed(() => solved.value.size)
const progressPercentage = computed(() => {
  if (!progressTotal.value) return 0
  return Math.min(100, Math.round((solvedCount.value / progressTotal.value) * 100))
})

const displayedList = computed(() => list.value)

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

function markDropping(items: readonly Question[]) {
  if (prefersReducedMotion()) {
    droppingIds.value = new Set()
    return
  }
  droppingIds.value = new Set(items.map((item) => item.id))
}

function handleCardAnimationEnd(event: AnimationEvent, id: number) {
  if (event.target === event.currentTarget) {
    droppingIds.value.delete(id)
  }
}

function getQuestionLanguages(languages?: string[] | null): string[] {
  const source = languages && languages.length > 0 ? languages : ['cpp', 'java', 'python']
  return sortLanguages(source)
}

function selectDifficulty(level: Difficulty | '') {
  if (difficulty.value === level) return
  page.value = 1
  difficulty.value = level
}

function selectStatus(nextStatus: BankStatusFilter) {
  if (statusFilter.value === nextStatus) return
  page.value = 1
  statusFilter.value = nextStatus
}

function selectTag(nextTag: string, openDrawer = false) {
  if (openDrawer && nextTag) {
    showTags.value = true
  }
  if (selectedTag.value === nextTag) return
  page.value = 1
  selectedTag.value = nextTag
}

function toggleTag(tagName: string) {
  const next = selectedTag.value === tagName ? '' : tagName
  selectTag(next, Boolean(next))
}

function resetFilters() {
  showTags.value = false
  resetUrlState()
}

function changePage(nextPage: number) {
  if (nextPage === page.value) return
  page.value = nextPage
}

async function probeGlobalTotal() {
  if (globalTotal.value > 0 || globalProbeInFlight) return
  globalProbeInFlight = true
  try {
    const probe = await questionApi.list({ page: 1, size: 1 })
    if (!isUnmounted && probe.total > 0) {
      globalTotal.value = probe.total
    }
  } catch {
    /* 全库总量探测失败时降级使用当前 total */
  } finally {
    globalProbeInFlight = false
  }
}

async function load() {
  const reqId = ++listRequestId
  loading.value = true
  error.value = ''

  const activeKeyword = keyword.value.trim() || undefined
  const activeDifficulty = difficulty.value || undefined
  const activeTag = selectedTag.value || undefined
  const activeUserStatus = statusFilter.value === 'ALL' ? undefined : statusFilter.value
  const isUnfiltered = !activeKeyword && !activeDifficulty && !activeTag && !activeUserStatus

  try {
    const res = await questionApi.list({
      keyword: activeKeyword,
      difficulty: activeDifficulty,
      tag: activeTag,
      userStatus: activeUserStatus,
      page: page.value,
      size,
    })
    if (isUnmounted || reqId !== listRequestId) return
    list.value = res.list
    total.value = res.total
    markDropping(res.list)
    if (isUnfiltered) {
      globalTotal.value = res.total
    } else if (globalTotal.value === 0) {
      await probeGlobalTotal()
    }
  } catch (e) {
    if (!isUnmounted && reqId === listRequestId) {
      error.value = e instanceof Error ? e.message : '加载失败'
    }
  } finally {
    if (!isUnmounted && reqId === listRequestId) {
      loading.value = false
    }
  }
}

async function loadTags() {
  try {
    const tags = await questionApi.listTags()
    if (!isUnmounted) {
      allTags.value = tags
    }
  } catch {
    /* 忽略标签加载失败 */
  }
}

async function loadSolved() {
  if (!userStore.isLoggedIn) return
  try {
    const ids = await submissionApi.solved()
    if (!isUnmounted) {
      solved.value = new Set(ids)
    }
  } catch {
    /* 未登录或加载失败时忽略，仅不显示已通过标记 */
  }
}

// 跨全库分页均匀随机抽取一题（已登录且在「全部」视图下优先从全库未解答题中抽取）
async function pickRandomQuestion() {
  if (randomLoading.value || total.value <= 0) return
  const reqId = ++randomRequestId
  const stale = () => isUnmounted || reqId !== randomRequestId
  randomLoading.value = true
  error.value = ''

  const baseFilter = {
    keyword: keyword.value.trim() || undefined,
    difficulty: difficulty.value || undefined,
    tag: selectedTag.value || undefined,
  }

  try {
    const picked = await pickRandomWithUnsolvedPriority({
      fetchPage: (userStatus, targetPage, pageSize) =>
        questionApi.list({
          ...baseFilter,
          userStatus,
          page: targetPage,
          size: pageSize,
        }),
      pageSize: size,
      isLoggedIn: userStore.isLoggedIn,
      statusFilter: statusFilter.value,
      knownTotal: total.value,
      knownPage: page.value,
      knownList: list.value,
      isStale: stale,
    })

    if (!stale() && picked?.item) {
      router.push(`/questions/${picked.item.id}`)
    }
  } catch (e) {
    if (!stale()) {
      error.value = e instanceof Error ? e.message : '随机抽题失败，请稍后重试'
    }
  } finally {
    if (!isUnmounted && reqId === randomRequestId) {
      randomLoading.value = false
    }
  }
}

watch(selectedTag, (nextTag) => {
  if (nextTag) {
    showTags.value = true
  }
})

watch(
  [keyword, difficulty, statusFilter, selectedTag, page],
  ([nextKeyword, nextDifficulty, nextStatus, nextTag], [prevKeyword, prevDifficulty, prevStatus, prevTag]) => {
    if (
      nextKeyword !== prevKeyword ||
      nextDifficulty !== prevDifficulty ||
      nextStatus !== prevStatus ||
      nextTag !== prevTag
    ) {
      randomRequestId++
      randomLoading.value = false
    }
    void load()
  },
)

onMounted(() => {
  void load()
  void loadSolved()
  void loadTags()
})

onBeforeUnmount(() => {
  isUnmounted = true
  listRequestId++
  randomRequestId++
})
</script>

<template>
  <div class="questions container">
    <!-- 顶部标题与做题进度概览 -->
    <div class="page-head">
      <div class="page-head-left">
        <h1 class="page-title">算法编程</h1>
        <p class="muted head-sub">标准在线代码评测题库 · 全量测试用例实时判定与分析</p>
      </div>
      <div v-if="userStore.isLoggedIn && progressTotal > 0" class="head-progress-card">
        <div class="progress-info">
          <span class="progress-title">攻克进度</span>
          <span class="progress-fraction mono">
            <strong>{{ solvedCount }}</strong> / {{ progressTotal }} 题
          </span>
        </div>
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: `${progressPercentage}%` }"></div>
        </div>
        <span class="progress-percent mono">{{ progressPercentage }}% 完成</span>
      </div>
    </div>

    <!-- 综合筛选工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <div class="search-wrap">
          <label class="sr-only" for="questions-search">搜索算法题</label>
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <path
              d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z"
            />
          </svg>
          <input
            id="questions-search"
            v-model="keywordModel"
            class="input search-input"
            type="search"
            placeholder="搜索题号、题目关键词…"
          />
        </div>
        <div class="difficulty-tabs">
          <button
            v-for="lv in difficultyLevels"
            :key="lv || 'all'"
            type="button"
            class="tab-btn"
            :class="{ active: difficulty === lv }"
            :aria-pressed="difficulty === lv"
            @click="selectDifficulty(lv)"
          >
            {{ lv ? difficultyLabels[lv] : '全部难度' }}
          </button>
        </div>

        <!-- 算法标签展开/收起按钮与独立清除按钮 -->
        <div v-if="allTags.length > 0" class="tag-control-group">
          <button
            type="button"
            class="tab-btn tag-toggle-btn"
            :class="{
              active: showTags,
              'has-selected': !!selectedTag,
            }"
            :aria-expanded="showTags"
            :title="showTags ? '收起算法标签栏' : '展开算法标签筛选'"
            @click="showTags = !showTags"
          >
            <span>标签筛选</span>
            <span v-if="selectedTag" class="tag-badge-indicator">#{{ selectedTag }}</span>
            <svg
              class="toggle-arrow"
              :class="{ 'is-open': showTags }"
              viewBox="0 0 16 16"
              fill="currentColor"
              aria-hidden="true"
            >
              <path
                d="M7.247 11.14 2.451 5.658C1.885 5.013 2.345 4 3.204 4h9.592a1 1 0 0 1 .753 1.659l-4.796 5.48a1 1 0 0 1-1.506 0z"
              />
            </svg>
          </button>
          <button
            v-if="selectedTag"
            type="button"
            class="tag-clear-btn"
            aria-label="清除标签筛选"
            title="清除当前标签筛选"
            @click="selectTag('')"
          >
            ×
          </button>
        </div>
      </div>

      <div class="toolbar-right">
        <!-- 做题状态快捷过滤 -->
        <div v-if="userStore.isLoggedIn" class="status-tabs">
          <button
            v-for="opt in statusOptions"
            :key="opt.value"
            type="button"
            class="status-tab"
            :class="{ active: statusFilter === opt.value }"
            :aria-pressed="statusFilter === opt.value"
            @click="selectStatus(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>

        <!-- 随机抽一题按钮 -->
        <button
          type="button"
          class="btn btn-ghost btn-sm random-btn"
          title="全库随机挑选一题进行挑战"
          :disabled="loading || randomLoading || total === 0"
          @click="pickRandomQuestion"
        >
          <span>{{ randomLoading ? '抽题中…' : '随机刷题' }}</span>
        </button>
      </div>
    </div>

    <!-- 算法类型标签快捷过滤条 -->
    <div v-if="showTags && allTags.length > 0" class="tag-filter-bar">
      <div class="tag-filter-chips">
        <span class="tag-filter-label mono">TAG:</span>
        <button
          type="button"
          class="tag-filter-chip"
          :class="{ active: !selectedTag }"
          :aria-pressed="!selectedTag"
          @click="selectTag('')"
        >
          全部
        </button>
        <button
          v-for="t in allTags"
          :key="t"
          type="button"
          class="tag-filter-chip"
          :class="{ active: selectedTag === t }"
          :aria-pressed="selectedTag === t"
          @click="toggleTag(t)"
        >
          #{{ t }}
        </button>
      </div>
      <button
        type="button"
        class="btn-collapse-tags"
        title="收起标签筛选"
        @click="showTags = false"
      >
        收起 ▴
      </button>
    </div>

    <!-- 列表加载与错误状态 -->
    <div v-if="error" class="submit-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="btn btn-ghost btn-sm" @click="load">重试</button>
    </div>
    <div v-if="loading" class="loading-box muted">
      <span class="loading-spin" aria-hidden="true"></span>
      <span>正在加载题目…</span>
    </div>
    <div v-else-if="displayedList.length === 0 && !error" class="empty-box">
      <p class="empty-title">未找到匹配的题目</p>
      <p class="muted empty-desc">尝试调整搜索关键词或重置筛选条件</p>
      <button
        v-if="hasActiveFilters"
        type="button"
        class="reset-filters-btn empty-reset-btn"
        @click="resetFilters"
      >
        重置筛选
      </button>
    </div>

    <!-- 单列题目卡片列表 -->
    <div v-else-if="displayedList.length > 0" class="question-list">
      <article
        v-for="(q, index) in displayedList"
        :key="q.id"
        class="question-item"
        :class="{ 'is-dropping': droppingIds.has(q.id) }"
        :style="{ '--drop-index': index }"
        @animationend="handleCardAnimationEnd($event, q.id)"
      >
        <!-- 左侧：通过状态图标 + 题号 + 标题与元数据 -->
        <div class="q-main-layout">
          <div class="q-status-wrap">
            <span
              v-if="solved.has(q.id)"
              class="status-icon solved"
              title="已攻克 (Accepted)"
              aria-label="已攻克"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                <path
                  d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z"
                />
              </svg>
            </span>
            <span v-else class="status-icon unsolved" title="尚未解答" aria-label="尚未解答">
              <span class="unsolved-ring"></span>
            </span>
          </div>

          <span class="q-number mono">#{{ q.id }}</span>

          <div class="q-content">
            <h2 class="q-title">
              <RouterLink :to="`/questions/${q.id}`" class="q-title-link">
                {{ q.title }}
              </RouterLink>
            </h2>
            <div class="q-tags-row">
              <span class="meta-tag limits mono">
                {{ q.timeLimit ?? 1000 }}ms / {{ q.memoryLimit ?? 256 }}MB
              </span>
              <span
                v-for="lang in getQuestionLanguages(q.languages)"
                :key="lang"
                class="meta-tag lang mono"
              >
                {{ formatLanguageLabel(lang) }}
              </span>
              <button
                v-for="t in q.tags ?? []"
                :key="t"
                type="button"
                class="meta-tag question-tag mono"
                :class="{ 'tag-active': selectedTag === t }"
                :aria-pressed="selectedTag === t"
                :title="`分类标签：${t}，点击按此标签筛选`"
                @click="toggleTag(t)"
              >
                #{{ t }}
              </button>
            </div>
          </div>
        </div>

        <!-- 右侧：难度胶囊 + 快速答题引导 -->
        <div class="q-right-layout">
          <span class="hud-pill" :class="`tone-${DIFFICULTY_TONE_MAP[q.difficulty] ?? 'neutral'}`">
            {{ difficultyLabels[q.difficulty] }}
          </span>
          <span class="action-cta" aria-hidden="true">
            <span class="cta-text">开始做题</span>
            <svg
              class="cta-arrow"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M6 3l5 5-5 5" />
            </svg>
          </span>
        </div>
      </article>
    </div>

    <!-- 统一分页器 -->
    <Pagination
      v-if="total > size"
      :page="page"
      :size="size"
      :total="total"
      :disabled="loading"
      @update:page="changePage"
    />
  </div>
</template>

<style scoped>
.tag-control-group {
  display: inline-flex;
  align-items: stretch;
}

.tag-clear-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  min-height: 36px;
  padding: 0 8px;
  font-size: 16px;
  line-height: 1;
  border: 1px solid var(--kece-accent);
  border-left: none;
  border-radius: 0px;
  background: var(--kece-accent-soft);
  color: var(--kece-accent-ink);
  cursor: pointer;
  transition:
    background-color 0.15s ease,
    color 0.15s ease;
}

.tag-clear-btn:hover {
  background: var(--kece-accent);
  color: #ffffff;
}

.empty-reset-btn {
  margin-top: 14px;
}

@media (prefers-reduced-motion: reduce) {
  .tag-clear-btn {
    transition: none;
  }
}
</style>
