<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { databaseApi } from '@/api/database'
import Pagination from '@/components/Pagination.vue'
import { useBankUrlSync, type BankStatusFilter } from '@/shared/composables/useBankUrlSync'
import { pickRandomWithUnsolvedPriority } from '@/shared/lib/randomSample'
import { useUserStore } from '@/stores/user'
import '@/styles/question-bank.css'
import type { DbQuestion, DbQuestionType } from '@/types'
import { DB_TYPE_TONE_MAP, getDialectLabel } from '@/utils/questionMeta'

const router = useRouter()
const userStore = useUserStore()

const typeLabels: Record<DbQuestionType, string> = {
  SELECT: 'SELECT 查询',
  DML: 'DML 操作',
}
const typeLevels: Array<DbQuestionType | ''> = ['', 'SELECT', 'DML']
const statusOptions: ReadonlyArray<{ value: BankStatusFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'UNSOLVED', label: '未解答' },
  { value: 'SOLVED', label: '已通过' },
]

const {
  keyword,
  type,
  status: statusFilter,
  page,
  reset: resetUrlState,
} = useBankUrlSync<string, DbQuestionType>({
  fields: ['keyword', 'type', 'status', 'page'],
  validTypes: ['SELECT', 'DML'],
})

const size = 10
const list = ref<DbQuestion[]>([])
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
  Boolean(keyword.value.trim() || type.value || statusFilter.value !== 'ALL'),
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

function markDropping(items: readonly DbQuestion[]) {
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

function selectType(nextType: DbQuestionType | '') {
  if (type.value === nextType) return
  page.value = 1
  type.value = nextType
}

function selectStatus(nextStatus: BankStatusFilter) {
  if (statusFilter.value === nextStatus) return
  page.value = 1
  statusFilter.value = nextStatus
}

function resetFilters() {
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
    const probe = await databaseApi.list({ page: 1, size: 1 })
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
  const activeType = type.value || undefined
  const activeUserStatus = statusFilter.value === 'ALL' ? undefined : statusFilter.value
  const isUnfiltered = !activeKeyword && !activeType && !activeUserStatus

  try {
    const res = await databaseApi.list({
      keyword: activeKeyword,
      type: activeType,
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

async function loadSolved() {
  if (!userStore.isLoggedIn) return
  try {
    const ids = await databaseApi.solved()
    if (!isUnmounted) {
      solved.value = new Set(ids)
    }
  } catch {
    /* 未登录或失败时忽略 */
  }
}

// 跨全库分页均匀随机挑选一题（已登录且在「全部」视图下优先从全库未解答题中抽取）
async function pickRandomQuestion() {
  if (randomLoading.value || total.value <= 0) return
  const reqId = ++randomRequestId
  const stale = () => isUnmounted || reqId !== randomRequestId
  randomLoading.value = true
  error.value = ''

  const baseFilter = {
    keyword: keyword.value.trim() || undefined,
    type: type.value || undefined,
  }

  try {
    const picked = await pickRandomWithUnsolvedPriority({
      fetchPage: (userStatus, targetPage, pageSize) =>
        databaseApi.list({
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
      router.push(`/database/${picked.item.id}`)
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

watch(
  [keyword, type, statusFilter, page],
  ([nextKeyword, nextType, nextStatus], [prevKeyword, prevType, prevStatus]) => {
    if (nextKeyword !== prevKeyword || nextType !== prevType || nextStatus !== prevStatus) {
      randomRequestId++
      randomLoading.value = false
    }
    void load()
  },
)

onMounted(() => {
  void load()
  void loadSolved()
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
        <h1 class="page-title">数据库实践</h1>
        <p class="muted head-sub">真实 MySQL 沙箱环境 · 实时 SQL 执行评测与多测试集校验</p>
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
          <label class="sr-only" for="database-search">搜索数据库题目</label>
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <path
              d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z"
            />
          </svg>
          <input
            id="database-search"
            v-model="keywordModel"
            class="input search-input"
            type="search"
            placeholder="搜索题号、SQL题目关键词…"
          />
        </div>
        <div class="type-tabs">
          <button
            v-for="t in typeLevels"
            :key="t || 'all'"
            type="button"
            class="tab-btn"
            :class="{ active: type === t }"
            :aria-pressed="type === t"
            @click="selectType(t)"
          >
            {{ t ? typeLabels[t] : '全部题型' }}
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

    <!-- 列表加载与错误状态 -->
    <div v-if="error" class="submit-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="btn btn-ghost btn-sm" @click="load">重试</button>
    </div>
    <div v-if="loading" class="loading-box muted">
      <span class="loading-spin" aria-hidden="true"></span>
      <span>正在加载数据库题目…</span>
    </div>
    <div v-else-if="displayedList.length === 0 && !error" class="empty-box">
      <p class="empty-title">未找到匹配的数据库题目</p>
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
              <RouterLink :to="`/database/${q.id}`" class="q-title-link">
                {{ q.title }}
              </RouterLink>
            </h2>
            <div class="q-tags-row">
              <span class="meta-tag dialect mono">{{ getDialectLabel(q.dialect) }}</span>
              <span class="meta-tag order-mode mono">
                {{ q.orderSensitive ? '按序比对' : '无序比对' }}
              </span>
              <span v-if="q.testCases?.length" class="meta-tag cases mono">
                {{ q.testCases.length }} 组测试集
              </span>
            </div>
          </div>
        </div>

        <!-- 右侧：题型 HUD 胶囊 + 快速答题引导 -->
        <div class="q-right-layout">
          <span class="hud-pill" :class="`tone-${DB_TYPE_TONE_MAP[q.type] ?? 'neutral'}`">
            {{ typeLabels[q.type] || q.type }}
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
.empty-reset-btn {
  margin-top: 14px;
}
</style>
