<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { problemSetApi, type ProblemSetSummary } from '@/api/problemSet'
import { useUserStore } from '@/stores/user'
import { useToast } from '@/composables/useToast'
import Pagination from '@/components/Pagination.vue'

const router = useRouter()
const userStore = useUserStore()
const toast = useToast()

const list = ref<ProblemSetSummary[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const loading = ref(true)
const error = ref('')

const category = ref('')
const difficulty = ref('')
const keyword = ref('')
const favoritedOnly = ref(false)

const categoryTabs = [
  { value: '', label: '全部题集' },
  { value: 'ALGO', label: '算法编程题集' },
  { value: 'DB', label: '数据库 SQL 题集' },
  { value: 'OBJECTIVE', label: '标准客观题集' },
]

const difficultyTabs = [
  { value: '', label: '全部难度' },
  { value: 'EASY', label: '简单' },
  { value: 'MEDIUM', label: '中等' },
  { value: 'HARD', label: '困难' },
]

const categoryLabels: Record<string, string> = {
  ALGO: '算法编程题集',
  DB: '数据库 SQL 题集',
  OBJECTIVE: '标准客观题集',
}

const difficultyLabels: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}

// 题集创建与管理严格收敛于后台管理系统

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
      size: pageSize,
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
    toast.success(res.favorited ? '已加入我的收藏题集' : '已取消收藏')
    if (favoritedOnly.value && !res.favorited) {
      await load()
    }
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败')
  }
}

function progressPercent(ps: ProblemSetSummary): number {
  if (!ps.itemCount || ps.itemCount <= 0) return 0
  return Math.min(100, Math.round(((ps.solvedCount || 0) / ps.itemCount) * 100))
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    load()
  }, 260)
}

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
  <div class="problem-sets-page container">
    <!-- 顶部标题与管理快捷入口 -->
    <header class="page-head">
      <div class="head-left">
        <div class="eyebrow mono">// STRUCTURED PROBLEM SETS · 专题训练矩阵</div>
        <h1 class="page-title">精选专题题集</h1>
        <p class="page-sub">
          按知识体系与工程专题结构化编排算法、SQL 与客观题，全局实时互通 AC 进度并支持断点续刷。
        </p>
      </div>

    </header>

    <!-- 多维筛选工具栏 -->
    <section class="filter-panel card">
      <div class="filter-row">
        <div class="filter-group">
          <span class="filter-label mono">CATEGORY</span>
          <div class="pill-tabs">
            <button
              v-for="tab in categoryTabs"
              :key="tab.value"
              type="button"
              class="pill-tab"
              :class="{ active: category === tab.value }"
              @click="category = tab.value"
            >
              {{ tab.label }}
            </button>
          </div>
        </div>

        <div class="filter-group">
          <span class="filter-label mono">DIFFICULTY</span>
          <div class="pill-tabs">
            <button
              v-for="d in difficultyTabs"
              :key="d.value"
              type="button"
              class="pill-tab"
              :class="{ active: difficulty === d.value }"
              @click="difficulty = d.value"
            >
              {{ d.label }}
            </button>
          </div>
        </div>
      </div>

      <div class="toolbar-bottom">
        <div class="search-box">
          <span class="search-prefix mono">⌕</span>
          <input
            v-model="keyword"
            type="text"
            class="search-input"
            placeholder="搜索题集名称、副标题或知识点标签…"
            @input="onSearchInput"
          />
        </div>

        <div class="toolbar-right">
          <button
            v-if="userStore.isLoggedIn"
            type="button"
            class="fav-filter-btn"
            :class="{ active: favoritedOnly }"
            @click="favoritedOnly = !favoritedOnly"
          >
            <span>{{ favoritedOnly ? '★' : '☆' }}</span>
            <span>仅看我收藏的</span>
          </button>
          <span class="total-badge mono">共 {{ total }} 套题集</span>
        </div>
      </div>
    </section>

    <!-- 状态反馈 -->
    <p v-if="error" class="submit-error">{{ error }}</p>

    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在加载专题题集…</p>
    </div>

    <div v-else-if="list.length === 0" class="empty-box card">
      <p class="empty-title">暂无匹配的题集</p>
      <p class="muted empty-desc">尝试切换分类筛选条件，或前往题库自由刷题</p>
    </div>

    <!-- 题集 Bento 直角卡片网格 -->
    <div v-else class="sets-grid">
      <RouterLink
        v-for="ps in list"
        :key="ps.id"
        :to="`/problem-sets/${ps.id}`"
        class="set-card card"
      >
        <!-- 卡片顶栏：官方标记 + 分类 + 难度 + 收藏按钮 -->
        <div class="set-card-top">
          <div class="set-badges">
            <span v-if="ps.isOfficial === 1" class="badge-official mono">OFFICIAL</span>
            <span class="badge-cat mono">{{ categoryLabels[ps.category] || ps.category }}</span>
            <span
              class="badge diff-badge"
              :class="`difficulty-${(ps.difficulty || 'MEDIUM').toLowerCase()}`"
            >
              {{ difficultyLabels[ps.difficulty] || ps.difficulty }}
            </span>
          </div>

          <button
            type="button"
            class="btn-fav"
            :class="{ 'is-fav': ps.favorited }"
            :title="ps.favorited ? '取消收藏' : '收藏题集'"
            @click="onToggleFavorite(ps, $event)"
          >
            <span>{{ ps.favorited ? '★ 已收藏' : '☆ 收藏' }}</span>
          </button>
        </div>

        <!-- 标题与简介 -->
        <h2 class="set-title">{{ ps.title }}</h2>
        <div v-if="ps.subtitle" class="set-subtitle">{{ ps.subtitle }}</div>
        <p class="set-desc">
          {{ ps.description || '精选结构化训练题集，支持多题型章节闯关与实时进度追踪。' }}
        </p>

        <!-- 标签条 -->
        <div v-if="ps.tags && ps.tags.length > 0" class="set-tags">
          <span v-for="tag in ps.tags.slice(0, 4)" :key="tag" class="tag-chip">
            # {{ tag }}
          </span>
        </div>

        <!-- 题型规格栏 -->
        <div class="set-composition mono">
          <span class="comp-total">共 {{ ps.itemCount }} 题</span>
          <span class="comp-sep">·</span>
          <span class="comp-item">{{ categoryLabels[ps.category] || ps.category }}</span>
          <span class="comp-author">{{ ps.authorName }}</span>
        </div>

        <!-- 底部进度条与进入入口 -->
        <div class="set-foot">
          <div class="progress-wrap">
            <div class="progress-meta mono">
              <span>进度 {{ ps.solvedCount || 0 }} / {{ ps.itemCount }}</span>
              <span>{{ progressPercent(ps) }}%</span>
            </div>
            <div class="progress-track">
              <div
                class="progress-fill"
                :style="{ width: `${progressPercent(ps)}%` }"
              ></div>
            </div>
          </div>

          <span class="enter-cta">
            {{ (ps.solvedCount || 0) > 0 ? '继续闯关 →' : '开始训练 →' }}
          </span>
        </div>
      </RouterLink>
    </div>

    <Pagination
      v-if="total > pageSize"
      v-model:page="page"
      :total="total"
      :size="pageSize"
    />
  </div>
</template>

<style scoped>
.problem-sets-page {
  padding-top: 32px;
  padding-bottom: 72px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}

.eyebrow {
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-tertiary);
  letter-spacing: 0.8px;
  margin-bottom: 6px;
}

.page-title {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.5px;
  margin: 0 0 6px;
  color: var(--ink);
}

.page-sub {
  font-size: 14px;
  color: var(--ink-secondary);
  margin: 0;
}

.head-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

/* 筛选面板 */
.filter-panel {
  padding: 18px 20px;
  margin-bottom: 24px;
  background: var(--surface);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: center;
  justify-content: space-between;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-tertiary);
  letter-spacing: 0.5px;
}

.pill-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pill-tab {
  padding: 5px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-secondary);
  background: #ffffff;
  border: 1px solid var(--line);
  transition: all 0.15s ease;
}

.pill-tab:hover {
  color: var(--ink);
  border-color: var(--ink);
}

.pill-tab.active {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
  font-weight: 600;
}

.toolbar-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #ffffff;
  border: 1px solid var(--line);
  padding: 0 12px;
  height: 38px;
  flex: 1;
  max-width: 440px;
}

.search-box:focus-within {
  border-color: var(--ink);
}

.search-prefix {
  color: var(--ink-tertiary);
  font-size: 14px;
}

.search-input {
  border: none;
  outline: none;
  width: 100%;
  font-size: 13.5px;
  color: var(--ink);
  background: transparent;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fav-filter-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 600;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink-secondary);
  transition: all 0.15s ease;
}

.fav-filter-btn.active {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}

.total-badge {
  font-size: 12px;
  color: var(--ink-tertiary);
}

/* 题集网格 */
.sets-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.set-card {
  padding: 24px 26px 20px;
  background: #ffffff;
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  transition:
    border-color 0.18s ease,
    transform 0.18s ease,
    box-shadow 0.18s ease;
}

.set-card:hover {
  border-color: var(--ink);
  transform: translateY(-2px);
  box-shadow: 0 10px 28px rgba(17, 17, 17, 0.07);
}

.set-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}

.set-badges {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.badge-official {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 7px;
  background: var(--ink);
  color: #ffffff;
  letter-spacing: 0.5px;
}

.badge-cat {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.diff-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
}

.difficulty-easy {
  background: #2b8a3e;
  color: #ffffff;
}
.difficulty-medium {
  background: #e67700;
  color: #ffffff;
}
.difficulty-hard {
  background: #c92a2a;
  color: #ffffff;
}

.btn-fav {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
  transition: all 0.15s ease;
}

.btn-fav:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.btn-fav.is-fav {
  background: #fff9db;
  border-color: #f08c00;
  color: #e67700;
}

.set-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 4px;
  letter-spacing: -0.3px;
}

.set-subtitle {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  margin-bottom: 10px;
}

.set-desc {
  font-size: 13.5px;
  color: var(--ink-secondary);
  line-height: 1.6;
  margin: 0 0 14px;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.set-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 14px;
}

.tag-chip {
  font-size: 11.5px;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 2px 8px;
}

.set-composition {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 7px 12px;
  margin-bottom: 16px;
}

.comp-total {
  font-weight: 700;
  color: var(--ink);
}

.comp-sep {
  color: var(--line);
}

.comp-item {
  padding: 1px 6px;
  background: #ffffff;
  border: 1px solid var(--line);
  font-size: 11px;
}

.comp-author {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-tertiary);
}

.set-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.progress-wrap {
  flex: 1;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--ink-secondary);
  margin-bottom: 5px;
}

.progress-track {
  width: 100%;
  height: 6px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #2b8a3e;
  transition: width 0.3s ease;
}

.enter-cta {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink);
  white-space: nowrap;
}

.empty-box {
  padding: 56px 24px;
  text-align: center;
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

@media (max-width: 860px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }
  .sets-grid {
    grid-template-columns: 1fr;
  }
  .toolbar-bottom {
    flex-direction: column;
    align-items: stretch;
  }
  .search-box {
    max-width: 100%;
  }
}
</style>
