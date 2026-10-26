<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { questionApi } from '@/api/question'
import { submissionApi } from '@/api/submission'
import { useUserStore } from '@/stores/user'
import type { Difficulty, Question } from '@/types'
import { DIFFICULTY_LABELS as difficultyLabels } from '@/utils/submission'

const router = useRouter()
const userStore = useUserStore()

const keyword = ref('')
const difficulty = ref<Difficulty | ''>('')
const statusFilter = ref<'ALL' | 'UNSOLVED' | 'SOLVED'>('ALL')
const page = ref(1)
const size = 10

const list = ref<Question[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref('')

const solved = ref<Set<number>>(new Set())


const difficultyLevels: Array<Difficulty | ''> = ['', 'EASY', 'MEDIUM', 'HARD']

const LANGUAGE_MAP: Record<string, string> = {
  CPP: 'C++',
  cpp: 'C++',
  JAVA: 'Java',
  java: 'Java',
  PYTHON: 'Python',
  python: 'Python',
  JAVASCRIPT: 'JS',
  javascript: 'JS',
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

// 解决数量与统计
const solvedCount = computed(() => solved.value.size)
const progressPercentage = computed(() => {
  if (!total.value) return 0
  return Math.min(100, Math.round((solvedCount.value / total.value) * 100))
})

// 展示列表（与后端分页与状态过滤对齐）
const displayedList = computed(() => list.value)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await questionApi.list({
      keyword: keyword.value.trim() || undefined,
      difficulty: difficulty.value || undefined,
      userStatus: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
      page: page.value,
      size,
    })
    list.value = res.list
    total.value = res.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function changePage(p: number) {
  page.value = p
  load()
}

async function loadSolved() {
  if (!userStore.isLoggedIn) return
  try {
    const ids = await submissionApi.solved()
    solved.value = new Set(ids)
  } catch {
    /* 未登录或加载失败时忽略，仅不显示已通过标记 */
  }
}

// 随机做一题
function pickRandomQuestion() {
  if (!list.value.length) return
  // 优先挑未解决的题目
  const unsolvedPool = list.value.filter((q) => !solved.value.has(q.id))
  const pool = unsolvedPool.length > 0 ? unsolvedPool : list.value
  const target = pool[Math.floor(Math.random() * pool.length)]
  if (target) {
    router.push(`/questions/${target.id}`)
  }
}

watch([keyword, difficulty, statusFilter], () => {
  page.value = 1
  load()
})

onMounted(() => {
  load()
  loadSolved()
})
</script>

<template>
  <div class="questions container">
    <!-- 顶部标题与做题进度概览 -->
    <div class="page-head">
      <div class="page-head-left">
        <h1 class="page-title">算法编程</h1>
        <p class="muted head-sub">
          标准在线代码评测题库 · 全量测试用例实时判定与分析
        </p>
      </div>
      <div v-if="userStore.isLoggedIn && total > 0" class="head-progress-card">
        <div class="progress-info">
          <span class="progress-title">攻克进度</span>
          <span class="progress-fraction mono">
            <strong>{{ solvedCount }}</strong> / {{ total }} 题
          </span>
        </div>
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: `${progressPercentage}%` }"></div>
        </div>
        <span class="progress-percent mono">{{ progressPercentage }}% 完成</span>
      </div>
    </div>

    <!-- 综合筛选工具栏 (左右均衡，解决原右侧大面积空旷问题) -->
    <div class="toolbar card">
      <div class="toolbar-left">
        <div class="search-wrap">
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor">
            <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z" />
          </svg>
          <input
            v-model="keyword"
            class="input search-input"
            type="search"
            placeholder="搜索题号、题目关键词…"
          />
        </div>
        <div class="difficulty-tabs">
          <button
            v-for="lv in difficultyLevels"
            :key="lv || 'all'"
            class="tab-btn"
            :class="[
              { active: difficulty === lv },
              lv ? `tab-${lv.toLowerCase()}` : 'tab-all'
            ]"
            @click="difficulty = lv"
          >
            {{ lv ? difficultyLabels[lv] : '全部难度' }}
          </button>
        </div>
      </div>

      <div class="toolbar-right">
        <!-- 做题状态快捷过滤 -->
        <div v-if="userStore.isLoggedIn" class="status-tabs">
          <button
            class="status-tab"
            :class="{ active: statusFilter === 'ALL' }"
            @click="statusFilter = 'ALL'"
          >
            全部
          </button>
          <button
            class="status-tab"
            :class="{ active: statusFilter === 'UNSOLVED' }"
            @click="statusFilter = 'UNSOLVED'"
          >
            未解答
          </button>
          <button
            class="status-tab"
            :class="{ active: statusFilter === 'SOLVED' }"
            @click="statusFilter = 'SOLVED'"
          >
            已通过
          </button>
        </div>

        <!-- 随机抽一题按钮 -->
        <button
          type="button"
          class="btn btn-ghost btn-sm random-btn"
          title="随机挑选一题进行挑战"
          @click="pickRandomQuestion"
        >
          <span>随机刷题</span>
        </button>
      </div>
    </div>

    <!-- 列表加载与错误状态 -->
    <p v-if="error" class="submit-error">{{ error }}</p>
    <div v-if="loading" class="loading-box card muted">
      <span class="loading-spin"></span>
      <span>正在加载题目…</span>
    </div>
    <div v-else-if="displayedList.length === 0" class="empty-box card">
      <p class="empty-title">未找到匹配的题目</p>
      <p class="muted empty-desc">尝试调整搜索关键词或重置筛选条件</p>
    </div>

    <!-- 富信息单列题目卡片列表 -->
    <div v-else class="question-list" :key="`${page}-${difficulty}-${statusFilter}`">
      <RouterLink
        v-for="(q, index) in displayedList"
        :key="q.id"
        :to="`/questions/${q.id}`"
        class="question-item card"
        :style="{ '--drop-index': index }"
      >
        <!-- 左侧：通过状态图标 + 题号 + 标题与元数据 -->
        <div class="q-main-layout">
          <!-- 解题状态图标 -->
          <div class="q-status-wrap">
            <span
              v-if="solved.has(q.id)"
              class="status-icon solved"
              title="已攻克 (Accepted)"
            >
              <svg viewBox="0 0 16 16" fill="currentColor">
                <path d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z" />
              </svg>
            </span>
            <span v-else class="status-icon unsolved" title="尚未解答">
              <span class="unsolved-ring"></span>
            </span>
          </div>

          <!-- 题号 (Mono) -->
          <span class="q-number mono">#{{ q.id }}</span>

          <!-- 标题与次级信息 -->
          <div class="q-content">
            <h3 class="q-title">{{ q.title }}</h3>
            <div class="q-tags-row">
              <!-- 时空限制 -->
              <span class="meta-tag limits mono">
                {{ q.timeLimit ?? 1000 }}ms / {{ q.memoryLimit ?? 256 }}MB
              </span>
              <!-- 支持语言徽标 -->
              <span
                v-for="lang in (q.languages && q.languages.length ? q.languages : ['CPP', 'JAVA', 'PYTHON'])"
                :key="lang"
                class="meta-tag lang mono"
              >
                {{ LANGUAGE_MAP[lang] || lang }}
              </span>
            </div>
          </div>
        </div>

        <!-- 右侧：难度徽章 + 快速答题按钮 -->
        <div class="q-right-layout">
          <span class="difficulty-pill" :class="`pill-${q.difficulty.toLowerCase()}`">
            {{ difficultyLabels[q.difficulty] }}
          </span>
          <span class="action-cta">
            <span class="cta-text">开始做题</span>
            <svg class="cta-arrow" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M6 3l5 5-5 5" />
            </svg>
          </span>
        </div>
      </RouterLink>
    </div>

    <!-- 分页器 -->
    <div v-if="total > 0" class="pagination">
      <button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="changePage(page - 1)">
        上一页
      </button>
      <span class="page-info mono">第 {{ page }} / {{ totalPages }} 页 (共 {{ total }} 题)</span>
      <button
        class="btn btn-ghost btn-sm"
        :disabled="page >= totalPages"
        @click="changePage(page + 1)"
      >
        下一页
      </button>
    </div>
  </div>
</template>

<style scoped>
.questions {
  max-width: 1120px;
  margin: 0 auto;
  padding: 0 24px 60px;
}
.page-head {
  padding-top: 36px;
  margin-bottom: 20px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.head-sub {
  margin: 0;
  font-size: 14px;
}
.head-progress-card {
  padding: 10px 16px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
  min-width: 220px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.progress-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}
.progress-title {
  color: var(--ink-secondary);
  font-weight: 500;
}
.progress-fraction {
  font-size: 13px;
  color: var(--ink);
}
.progress-bar-track {
  width: 100%;
  height: 6px;
  background: #f0f0f0;
  overflow: hidden;
  border-radius: 0;
}
.progress-bar-fill {
  height: 100%;
  background: #2b8a3e;
  transition: width 0.3s ease;
}
.progress-percent {
  font-size: 11px;
  color: var(--ink-tertiary);
  text-align: right;
}

/* 顶部综合工具栏 */
.toolbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  margin-bottom: 20px;
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  flex-wrap: wrap;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  flex: 1;
}
.search-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 240px;
}
.search-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: var(--ink-secondary);
  pointer-events: none;
}
.search-input {
  width: 100%;
  height: 34px;
  padding-left: 32px;
  font-size: 13.5px;
  border-radius: 0;
}
.difficulty-tabs {
  display: flex;
  gap: 6px;
}
.tab-btn {
  padding: 5px 12px;
  font-size: 13px;
  font-weight: 500;
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}
.tab-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}
.tab-btn.active.tab-all {
  background: var(--ink);
  border-color: var(--ink);
  color: #fff;
}
.tab-btn.active.tab-easy {
  background: #2b8a3e;
  border-color: #2b8a3e;
  color: #fff;
}
.tab-btn.active.tab-medium {
  background: #e67700;
  border-color: #e67700;
  color: #fff;
}
.tab-btn.active.tab-hard {
  background: #c92a2a;
  border-color: #c92a2a;
  color: #fff;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.status-tabs {
  display: inline-flex;
  border: 1px solid var(--line);
  background: var(--bg);
  padding: 2px;
}
.status-tab {
  padding: 4px 10px;
  font-size: 12px;
  border: none;
  background: transparent;
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.12s ease;
}
.status-tab.active {
  background: var(--surface);
  color: var(--ink);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}
.random-btn {
  height: 32px;
  padding: 0 12px;
  gap: 6px;
  font-size: 13px;
  border-radius: 0;
}
.random-icon {
  width: 13px;
  height: 13px;
}

/* 题目卡片列表 */
.question-list {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.question-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
  text-decoration: none;
  color: inherit;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  /* 题目卡片入场从工具栏下方按序掉落效果 */
  animation: cardDropIn 0.44s cubic-bezier(0.25, 1.15, 0.5, 1) backwards;
  animation-delay: calc(var(--drop-index, 0) * 45ms);
  will-change: transform, opacity;
}
.question-item:hover {
  border-color: var(--ink);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.06);
  transform: translateY(-2px) !important;
}

@keyframes cardDropIn {
  0% {
    opacity: 0;
    transform: translateY(-38px) scale(0.985);
  }
  60% {
    opacity: 1;
    transform: translateY(3px) scale(1.002);
  }
  82% {
    transform: translateY(-1.5px) scale(0.999);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .question-item {
    animation: none !important;
  }
}
.q-main-layout {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  flex: 1;
}
.q-status-wrap {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.status-icon.solved {
  color: #2b8a3e;
  width: 20px;
  height: 20px;
  display: inline-flex;
}
.status-icon.unsolved {
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.unsolved-ring {
  width: 12px;
  height: 12px;
  border: 1.5px solid #d0d0d0;
  border-radius: 50%;
}
.q-number {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-secondary);
  min-width: 44px;
  flex-shrink: 0;
}
.q-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.q-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.q-tags-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.meta-tag {
  font-size: 11.5px;
  padding: 1px 7px;
  line-height: 1.4;
  border-radius: 0;
}
.meta-tag.limits {
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}
.meta-tag.lang {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  color: #3f3f46;
  font-weight: 600;
}

/* 右侧：难度徽标与操作入口 */
.q-right-layout {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}
.difficulty-pill {
  min-width: 52px;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  border-radius: 0;
  letter-spacing: 0.3px;
  color: #fff;
}
.pill-easy {
  background: #2b8a3e;
  color: #fff;
  border: 1px solid #237032;
}
.pill-medium {
  background: #e67700;
  color: #fff;
  border: 1px solid #c96500;
}
.pill-hard {
  background: #c92a2a;
  color: #fff;
  border: 1px solid #b02525;
}
.action-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  transition: all 0.15s ease;
}
.cta-arrow {
  width: 14px;
  height: 14px;
  transition: transform 0.18s ease;
}
.question-item:hover .action-cta {
  color: var(--ink);
}
.question-item:hover .cta-arrow {
  transform: translateX(3px);
}

/* 加载与空状态 */
.loading-box,
.empty-box {
  padding: 48px 24px;
  text-align: center;
  border-radius: 0;
}
.empty-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 6px;
}
.empty-desc {
  font-size: 13px;
  margin: 0;
}

/* 分页 */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 32px 0 20px;
}
.page-info {
  color: var(--ink-secondary);
  font-size: 13.5px;
}

@media (max-width: 768px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .toolbar-left,
  .toolbar-right {
    width: 100%;
    justify-content: space-between;
  }
  .search-wrap {
    width: 100%;
  }
  .action-cta {
    display: none;
  }
}
</style>
