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
const viewMode = ref<'list' | 'grid'>((localStorage.getItem('kece_ps_view_mode') as 'list' | 'grid') || 'list')

watch(viewMode, (v) => {
  localStorage.setItem('kece_ps_view_mode', v)
})

const categoryTabs = [
  { value: '', label: '全部题集' },
  { value: 'ALGO', label: '算法编程' },
  { value: 'DB', label: '数据库 SQL' },
  { value: 'OBJECTIVE', label: '标准客观' },
]

const difficultyTabs = [
  { value: '', label: '全部难度' },
  { value: 'EASY', label: '简单' },
  { value: 'MEDIUM', label: '中等' },
  { value: 'HARD', label: '困难' },
]

const categoryLabels: Record<string, string> = {
  ALGO: '算法编程',
  DB: '数据库 SQL',
  OBJECTIVE: '标准客观',
  MIXED: '综合题集'
}

const difficultyLabels: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
  MIXED: '综合'
}

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
    <!-- 顶部标题与副标题 -->
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

          <!-- 视图切换：列表 / 网格 -->
          <div class="view-switch">
            <button
              type="button"
              class="view-btn"
              :class="{ active: viewMode === 'list' }"
              title="流式列表视图"
              @click="viewMode = 'list'"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14">
                <path d="M2.5 12a.5.5 0 0 1 .5-.5h10a.5.5 0 0 1 0 1H3a.5.5 0 0 1-.5-.5zm0-4a.5.5 0 0 1 .5-.5h10a.5.5 0 0 1 0 1H3a.5.5 0 0 1-.5-.5zm0-4a.5.5 0 0 1 .5-.5h10a.5.5 0 0 1 0 1H3a.5.5 0 0 1-.5-.5z"/>
              </svg>
            </button>
            <button
              type="button"
              class="view-btn"
              :class="{ active: viewMode === 'grid' }"
              title="卡片网格视图"
              @click="viewMode = 'grid'"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14">
                <path d="M1 2.5A1.5 1.5 0 0 1 2.5 1h3A1.5 1.5 0 0 1 7 2.5v3A1.5 1.5 0 0 1 5.5 7h-3A1.5 1.5 0 0 1 1 5.5v-3zm8 0A1.5 1.5 0 0 1 10.5 1h3A1.5 1.5 0 0 1 15 2.5v3A1.5 1.5 0 0 1 13.5 7h-3A1.5 1.5 0 0 1 9 5.5v-3zm-8 8A1.5 1.5 0 0 1 2.5 9h3A1.5 1.5 0 0 1 7 10.5v3A1.5 1.5 0 0 1 5.5 15h-3A1.5 1.5 0 0 1 1 13.5v-3zm8 0A1.5 1.5 0 0 1 10.5 9h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 9 13.5v-3z"/>
              </svg>
            </button>
          </div>
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

    <!-- 方案一：高信息密度流式列表（默认，彻底消除臃肿大方块感） -->
    <div v-else-if="viewMode === 'list'" class="sets-list">
      <RouterLink
        v-for="ps in list"
        :key="ps.id"
        :to="`/problem-sets/${ps.id}`"
        class="set-row card"
      >
        <div class="row-main">
          <div class="row-top">
            <span v-if="ps.isOfficial === 1" class="badge-official mono">OFFICIAL</span>
            <span class="badge-cat mono">{{ categoryLabels[ps.category] || ps.category }}</span>
            <h2 class="row-title">{{ ps.title }}</h2>
            <span
              class="badge diff-badge"
              :class="`difficulty-${(ps.difficulty || 'MEDIUM').toLowerCase()}`"
            >
              {{ difficultyLabels[ps.difficulty] || ps.difficulty }}
            </span>
          </div>

          <p class="row-desc">
            {{ ps.description || ps.subtitle || '精选结构化训练题集，支持章节闯关与实时进度追踪。' }}
          </p>

          <div class="row-meta mono">
            <span class="meta-item"><strong class="highlight-count">{{ ps.itemCount }}</strong> 题</span>
            <span class="meta-sep">·</span>
            <span class="meta-item">{{ ps.participantCount || 0 }} 人参与</span>
            <span class="meta-sep">·</span>
            <span class="meta-author">{{ ps.authorName || '官方教研组' }}</span>
            <div v-if="ps.tags && ps.tags.length > 0" class="row-tags">
              <span v-for="tag in ps.tags.slice(0, 3)" :key="tag" class="tag-chip">
                #{{ tag }}
              </span>
            </div>
          </div>
        </div>

        <div class="row-aside">
          <!-- 攻克进度条 -->
          <div class="row-progress">
            <div class="progress-labels mono">
              <span>已刷 {{ ps.solvedCount || 0 }} / {{ ps.itemCount }}</span>
              <span class="progress-pct">{{ progressPercent(ps) }}%</span>
            </div>
            <div class="progress-track">
              <div
                class="progress-fill"
                :style="{ width: `${progressPercent(ps)}%` }"
              ></div>
            </div>
          </div>

          <!-- 收藏与进入按钮 -->
          <div class="row-actions">
            <button
              type="button"
              class="btn-fav-icon"
              :class="{ 'is-fav': ps.favorited }"
              :title="ps.favorited ? '已收藏（点击取消）' : '收藏题集'"
              @click="onToggleFavorite(ps, $event)"
            >
              <span>{{ ps.favorited ? '★' : '☆' }}</span>
            </button>

            <span class="btn-enter">
              {{ (ps.solvedCount || 0) > 0 ? '继续闯关 →' : '开始训练 →' }}
            </span>
          </div>
        </div>
      </RouterLink>
    </div>

    <!-- 方案二：紧凑卡片网格模式（备用，大幅精简内边距与冗余方框） -->
    <div v-else class="sets-grid">
      <RouterLink
        v-for="ps in list"
        :key="ps.id"
        :to="`/problem-sets/${ps.id}`"
        class="set-card card"
      >
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
            class="btn-fav-icon-sm"
            :class="{ 'is-fav': ps.favorited }"
            :title="ps.favorited ? '取消收藏' : '收藏题集'"
            @click="onToggleFavorite(ps, $event)"
          >
            <span>{{ ps.favorited ? '★' : '☆' }}</span>
          </button>
        </div>

        <h2 class="set-title">{{ ps.title }}</h2>
        <p class="set-desc">
          {{ ps.description || ps.subtitle || '精选结构化训练题集，支持章节闯关与实时进度追踪。' }}
        </p>

        <div class="set-meta-line mono">
          <span><strong>{{ ps.itemCount }}</strong> 题</span>
          <span class="meta-sep">·</span>
          <span>{{ ps.participantCount || 0 }} 人学习</span>
          <span class="meta-sep">·</span>
          <span>{{ ps.authorName || '官方' }}</span>
        </div>

        <div class="set-foot">
          <div class="progress-wrap">
            <div class="progress-meta mono">
              <span>进度 {{ ps.solvedCount || 0 }}/{{ ps.itemCount }}</span>
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
            {{ (ps.solvedCount || 0) > 0 ? '继续 →' : '训练 →' }}
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

/* 筛选面板 */
.filter-panel {
  padding: 16px 20px;
  margin-bottom: 20px;
  background: var(--surface);
  display: flex;
  flex-direction: column;
  gap: 12px;
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

/* 视图切换按钮组 */
.view-switch {
  display: inline-flex;
  border: 1px solid var(--line);
  background: #ffffff;
}

.view-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--ink-tertiary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.view-btn:hover {
  color: var(--ink);
}

.view-btn.active {
  background: var(--ink);
  color: #ffffff;
}

/* ============================================================
   流式列表模式（sets-list）：紧凑、水平铺展、高信息密度
============================================================ */
.sets-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.set-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 22px;
  background: #ffffff;
  border: 1px solid var(--line);
  text-decoration: none;
  color: inherit;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.set-row:hover {
  border-color: var(--ink);
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.05);
}

.set-row:hover .btn-enter {
  background: var(--button);
  border-color: var(--button);
  color: #ffffff;
}

.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.row-top {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.row-title {
  font-size: 16.5px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
  line-height: 1.3;
}

.row-desc {
  font-size: 13px;
  color: var(--ink-secondary);
  line-height: 1.5;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 700px;
}

.row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-tertiary);
  margin-top: 2px;
}

.highlight-count {
  color: var(--ink);
}

.meta-sep {
  color: var(--line);
}

.row-tags {
  display: inline-flex;
  gap: 6px;
  margin-left: 6px;
}

.tag-chip {
  font-size: 11px;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 1px 6px;
}

.row-aside {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-shrink: 0;
}

.row-progress {
  width: 140px;
}

.progress-labels {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-secondary);
  margin-bottom: 4px;
}

.progress-pct {
  font-weight: 600;
}

.progress-track {
  width: 100%;
  height: 5px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #2b8a3e;
  transition: width 0.3s ease;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-fav-icon {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink-tertiary);
  font-size: 16px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-fav-icon:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.btn-fav-icon.is-fav {
  border-color: #f59e0b;
  background: #fffbeb;
  color: #d97706;
}

.btn-enter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 7px 16px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink);
  white-space: nowrap;
  transition: all 0.15s ease;
}

/* ============================================================
   网格模式（sets-grid）：3列紧凑布局、去掉多余内部灰框
============================================================ */
.sets-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.set-card {
  padding: 18px 20px 16px;
  background: #ffffff;
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  text-decoration: none;
  color: inherit;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.set-card:hover {
  border-color: var(--ink);
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(17, 17, 17, 0.06);
}

.set-card:hover .enter-cta {
  color: var(--primary, #111111);
  text-decoration: underline;
}

.set-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.set-badges {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.btn-fav-icon-sm {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink-tertiary);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-fav-icon-sm:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.btn-fav-icon-sm.is-fav {
  border-color: #f59e0b;
  background: #fffbeb;
  color: #d97706;
}

.set-title {
  font-size: 16.5px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 6px;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.set-desc {
  font-size: 13px;
  color: var(--ink-secondary);
  line-height: 1.5;
  margin: 0 0 12px;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.set-meta-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-tertiary);
  padding: 8px 0;
  border-top: 1px dashed var(--line);
  margin-bottom: 12px;
}

.set-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.progress-wrap {
  flex: 1;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-secondary);
  margin-bottom: 4px;
}

.enter-cta {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
  white-space: nowrap;
}

/* 徽章基础样式 */
.badge-official {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  background: var(--ink);
  color: #ffffff;
  letter-spacing: 0.5px;
}

.badge-cat {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.diff-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 7px;
}

.difficulty-easy {
  background: #ecfdf5;
  color: #16a34a;
}
.difficulty-medium {
  background: #fffbeb;
  color: #d97706;
}
.difficulty-hard {
  background: #fef2f2;
  color: #dc2626;
}
.difficulty-mixed {
  background: #f3f4f6;
  color: #4b5563;
}

/* 状态框 */
.state-card {
  padding: 48px 0;
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--line);
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

.submit-error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}

/* 响应式适配 */
@media (max-width: 980px) {
  .sets-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 820px) {
  .set-row {
    flex-direction: column;
    align-items: stretch;
    gap: 14px;
  }
  .row-desc {
    white-space: normal;
  }
  .row-aside {
    justify-content: space-between;
    padding-top: 10px;
    border-top: 1px solid var(--line);
  }
  .row-progress {
    width: 160px;
  }
}

@media (max-width: 640px) {
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
