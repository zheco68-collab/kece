<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { problemSetApi, type ProblemSetSummary } from '@/api/problemSet'
import { useUserStore } from '@/stores/user'
import { useToast } from '@/composables/useToast'
import Pagination from '@/components/Pagination.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const toast = useToast()

const keyword = ref('')
const category = ref((route.query.category as string) || '')
const difficulty = ref('')
const favoritedOnly = ref(false)
const page = ref(1)
const size = 15

const list = ref<ProblemSetSummary[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref('')

// 页面主标题与副标题动态计算：随侧边栏当前分类联动
const pageTitleInfo = computed(() => {
  if (category.value === 'ALGO') {
    return {
      title: '算法编程题集',
      sub: '精选算法专题题单 · 结构化编排与断点续刷',
    }
  }
  if (category.value === 'DB') {
    return {
      title: '数据库 SQL 题集',
      sub: '关系数据库专项实战题单 · SQL 综合能力提升',
    }
  }
  if (category.value === 'OBJECTIVE') {
    return {
      title: '标准客观题集',
      sub: '计算机理论与工程技术选择题单 · 体系化考点通关',
    }
  }
  return {
    title: '专题题集',
    sub: '体系化专项题单 · 结构化编排与断点续刷',
  }
})

const difficultyLevels = ['', 'EASY', 'MEDIUM', 'HARD']

const difficultyLabels: Record<string, string> = {
  '': '全部难度',
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
  MIXED: '综合'
}

const categoryLabels: Record<string, string> = {
  ALGO: '算法编程',
  DB: '数据库 SQL',
  OBJECTIVE: '标准客观',
  MIXED: '综合题集'
}

// 解决数量与统计（用于右上角通关进度卡片）
const solvedSetsCount = computed(() => {
  return list.value.filter(
    (ps) => (ps.solvedCount || 0) >= (ps.itemCount || 1) && (ps.itemCount || 0) > 0
  ).length
})

const progressPercentage = computed(() => {
  if (!total.value) return 0
  return Math.min(100, Math.round((solvedSetsCount.value / total.value) * 100))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await problemSetApi.listPublic({
      category: category.value || undefined,
      difficulty: difficulty.value || undefined,
      keyword: keyword.value.trim() || undefined,
      favoritedOnly: favoritedOnly.value || undefined,
      page: page.value,
      size,
    })
    list.value = res.list || res.items || []
    total.value = res.total || 0
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载题集列表失败'
  } finally {
    loading.value = false
  }
}

async function onToggleFavorite(ps: ProblemSetSummary, e: Event) {
  e.preventDefault()
  e.stopPropagation()
  if (!userStore.isLoggedIn) {
    toast.info('请先登录后再收藏题集')
    router.push('/login')
    return
  }
  try {
    const res = await problemSetApi.toggleFavorite(ps.id)
    ps.favorited = res.favorited
    ps.participantCount = res.participantCount
    toast.success(res.favorited ? '已加入收藏' : '已取消收藏')
    if (favoritedOnly.value && !res.favorited) {
      await load()
    }
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败')
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    load()
  }, 260)
})

watch(
  () => route.query.category,
  (newCat) => {
    category.value = (newCat as string) || ''
  }
)

watch([category, difficulty, favoritedOnly], () => {
  page.value = 1
  load()
})

watch(page, () => {
  load()
})

onMounted(load)
</script>

<template>
  <div class="problem-sets container">
    <!-- 顶部标题与攻克进度概览（与算法编程页完全对齐） -->
    <div class="page-head">
      <div class="page-head-left">
        <h1 class="page-title">{{ pageTitleInfo.title }}</h1>
        <p class="muted head-sub">
          {{ pageTitleInfo.sub }}
        </p>
      </div>

      <div v-if="userStore.isLoggedIn && total > 0" class="head-progress-card">
        <div class="progress-info">
          <span class="progress-title">通关进度</span>
          <span class="progress-fraction mono">
            <strong>{{ solvedSetsCount }}</strong> / {{ total }} 套
          </span>
        </div>
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: `${progressPercentage}%` }"></div>
        </div>
        <span class="progress-percent mono">{{ progressPercentage }}% 完成</span>
      </div>
    </div>

    <!-- 综合筛选工具栏（单行扁平设计，与算法编程页一致） -->
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
            placeholder="搜索题集名称、关键词…"
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
            {{ difficultyLabels[lv] }}
          </button>
        </div>
      </div>

      <div class="toolbar-right">

        <div v-if="userStore.isLoggedIn" class="status-tabs">
          <button
            class="status-tab"
            :class="{ active: !favoritedOnly }"
            @click="favoritedOnly = false"
          >
            全部
          </button>
          <button
            class="status-tab"
            :class="{ active: favoritedOnly }"
            @click="favoritedOnly = true"
          >
            已收藏
          </button>
        </div>
      </div>
    </div>

    <!-- 列表加载与错误状态 -->
    <p v-if="error" class="submit-error">{{ error }}</p>
    <div v-if="loading" class="loading-box card muted">
      <span class="loading-spin"></span>
      <span>正在加载专题题集…</span>
    </div>
    <div v-else-if="list.length === 0" class="empty-box card">
      <p class="empty-title">未找到匹配的题集</p>
      <p class="muted empty-desc">尝试调整搜索关键词或重置筛选条件</p>
    </div>

    <!-- 富信息单列题集卡片列表（风格、结构与算法编程完全统一） -->
    <div v-else :key="`${page}-${category}-${difficulty}-${favoritedOnly}`" class="question-list">
      <RouterLink
        v-for="(ps, index) in list"
        :key="ps.id"
        :to="`/problem-sets/${ps.id}`"
        class="question-item card"
        :style="{ '--drop-index': index }"
      >
        <!-- 左侧：通关状态图标 + 编号 + 标题与次级信息 -->
        <div class="q-main-layout">
          <!-- 解题状态图标 -->
          <div class="q-status-wrap">
            <span
              v-if="(ps.solvedCount || 0) >= ps.itemCount && ps.itemCount > 0"
              class="status-icon solved"
              title="已全部通关 (Completed)"
            >
              <svg viewBox="0 0 16 16" fill="currentColor">
                <path d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z" />
              </svg>
            </span>
            <span v-else class="status-icon unsolved" title="尚未完全通关">
              <span class="unsolved-ring"></span>
            </span>
          </div>

          <!-- 编号 (Mono) -->
          <span class="q-number mono">#{{ ps.id }}</span>

          <!-- 标题与次级信息 -->
          <div class="q-content">
            <h3 class="q-title">{{ ps.title }}</h3>
            <div class="q-tags-row">
              <span class="meta-tag type mono">
                {{ categoryLabels[ps.category] || ps.category }}
              </span>
              <span class="meta-tag limits mono">
                {{ ps.itemCount }} 题
              </span>
              <span class="meta-tag limits mono">
                {{ ps.participantCount || 0 }} 人学习
              </span>
              <span v-if="(ps.solvedCount || 0) > 0" class="meta-tag progress mono">
                已刷 {{ ps.solvedCount }}/{{ ps.itemCount }}
              </span>
            </div>
          </div>
        </div>

        <!-- 右侧：收藏 + 难度徽章 + 快速答题按钮 -->
        <div class="q-right-layout">
          <button
            v-if="userStore.isLoggedIn"
            type="button"
            class="star-fav-btn"
            :class="{ 'is-fav': ps.favorited }"
            :title="ps.favorited ? '取消收藏' : '收藏题集'"
            @click="onToggleFavorite(ps, $event)"
          >
            <span>{{ ps.favorited ? '★' : '☆' }}</span>
          </button>

          <span
            class="difficulty-pill"
            :class="`pill-${(ps.difficulty || 'MEDIUM').toLowerCase()}`"
          >
            {{ difficultyLabels[ps.difficulty] || ps.difficulty }}
          </span>

          <span class="action-cta">
            <span class="cta-text">{{ (ps.solvedCount || 0) > 0 ? '继续训练' : '开始做题' }}</span>
            <svg class="cta-arrow" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M6 3l5 5-5 5" />
            </svg>
          </span>
        </div>
      </RouterLink>
    </div>

    <!-- 分页 -->
    <Pagination
      v-if="total > size"
      v-model:page="page"
      :size="size"
      :total="total"
    />
  </div>
</template>

<style scoped>
.problem-sets {
  padding-top: 32px;
  padding-bottom: 72px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

/* 顶部标题与做题进度概览 */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
}
.page-head-left {
  flex: 1;
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
  width: 230px;
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
.category-tabs,
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
.tab-btn.active {
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

/* 题目/题集卡片列表 */
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
    opacity: 1;
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
  min-width: 36px;
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
.meta-tag.type {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  color: #3f3f46;
  font-weight: 600;
}
.meta-tag.progress {
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #059669;
  font-weight: 600;
}

/* 右侧：难度徽标与操作入口 */
.q-right-layout {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}
.star-fav-btn {
  background: transparent;
  border: none;
  font-size: 16px;
  color: var(--ink-tertiary);
  cursor: pointer;
  padding: 4px;
  line-height: 1;
  transition: all 0.15s ease;
}
.star-fav-btn:hover {
  color: #f59e0b;
  transform: scale(1.15);
}
.star-fav-btn.is-fav {
  color: #f59e0b;
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
.question-item:hover .action-cta {
  color: var(--ink);
}
.question-item:hover .cta-arrow {
  transform: translateX(3px);
}
.cta-arrow {
  width: 14px;
  height: 14px;
  transition: transform 0.15s ease;
}

/* 加载与空状态 */
.loading-box,
.empty-box {
  padding: 48px 24px;
  text-align: center;
  border-radius: 0;
}
.empty-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
}
.empty-desc {
  margin: 0;
  font-size: 13px;
}
.submit-error {
  color: var(--danger);
  margin-bottom: 16px;
}

/* 响应式调整 */
@media (max-width: 860px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }
  .head-progress-card {
    width: 100%;
  }
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .toolbar-left,
  .toolbar-right {
    flex-direction: column;
    align-items: stretch;
  }
  .search-wrap {
    width: 100%;
  }
}
</style>
