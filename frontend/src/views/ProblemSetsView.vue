<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { problemSetApi, type ProblemSetSummary } from '@/api/problemSet';
import Pagination from '@/components/Pagination.vue';
import { useToast } from '@/composables/useToast';
import { useUserStore } from '@/stores/user';
import type { Difficulty } from '@/types';
import { DIFFICULTY_TONE_MAP, type PillTone } from '@/utils/questionMeta';

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const toast = useToast();

const keyword = ref('');
const category = ref((route.query.category as string) || '');
const difficulty = ref('');
const favoritedOnly = ref(false);
const page = ref(1);
const size = 15;

const list = ref<ProblemSetSummary[]>([]);
const total = ref(0);
const loading = ref(true);
const error = ref('');

let searchTimer: ReturnType<typeof setTimeout> | null = null;
let loadRequestId = 0;
let isUnmounted = false;

// 页面主标题与副标题动态计算：随侧边栏当前分类联动
const pageTitleInfo = computed(() => {
  if (category.value === 'ALGO') {
    return {
      title: '算法编程题集',
      sub: '精选算法专题题单 · 结构化编排与断点续刷',
    };
  }
  if (category.value === 'DB') {
    return {
      title: '数据库 SQL 题集',
      sub: '关系数据库专项实战题单 · SQL 综合能力提升',
    };
  }
  if (category.value === 'OBJECTIVE') {
    return {
      title: '标准客观题集',
      sub: '计算机理论与工程技术选择题单 · 体系化考点通关',
    };
  }
  return {
    title: '专题题集',
    sub: '体系化专项题单 · 结构化编排与断点续刷',
  };
});

const difficultyLevels = ['', 'EASY', 'MEDIUM', 'HARD'];

const difficultyLabels: Record<string, string> = {
  '': '全部难度',
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
  MIXED: '综合',
};

const categoryLabels: Record<string, string> = {
  ALGO: '算法编程',
  DB: '数据库 SQL',
  OBJECTIVE: '标准客观',
  MIXED: '综合题集',
};

const hasActiveFilters = computed(() =>
  Boolean(keyword.value.trim() || difficulty.value || favoritedOnly.value),
);

// 解决数量与统计（用于右上角通关进度卡片）
const solvedSetsCount = computed(() => {
  return list.value.filter(
    (ps) => (ps.solvedCount || 0) >= (ps.itemCount || 1) && (ps.itemCount || 0) > 0,
  ).length;
});

const progressPercentage = computed(() => {
  if (!total.value) return 0;
  return Math.min(100, Math.round((solvedSetsCount.value / total.value) * 100));
});

function getDifficultyTone(diff?: string): PillTone {
  const key = (diff || '').toUpperCase() as Difficulty;
  return DIFFICULTY_TONE_MAP[key] ?? 'neutral';
}

async function load() {
  const reqId = ++loadRequestId;
  loading.value = true;
  error.value = '';
  try {
    const res = await problemSetApi.listPublic({
      category: category.value || undefined,
      difficulty: difficulty.value || undefined,
      keyword: keyword.value.trim() || undefined,
      favoritedOnly: favoritedOnly.value || undefined,
      page: page.value,
      size,
    });
    if (isUnmounted || reqId !== loadRequestId) return;
    list.value = res.list || res.items || [];
    total.value = res.total || 0;
  } catch (e) {
    if (!isUnmounted && reqId === loadRequestId) {
      error.value = e instanceof Error ? e.message : '加载题集列表失败';
    }
  } finally {
    if (!isUnmounted && reqId === loadRequestId) {
      loading.value = false;
    }
  }
}

function reloadFromFirstPage() {
  if (page.value !== 1) {
    page.value = 1;
  } else {
    void load();
  }
}

function resetFilters() {
  if (searchTimer) {
    clearTimeout(searchTimer);
    searchTimer = null;
  }
  keyword.value = '';
  difficulty.value = '';
  favoritedOnly.value = false;
  page.value = 1;
  void load();
}

async function onToggleFavorite(ps: ProblemSetSummary, e: Event) {
  e.preventDefault();
  e.stopPropagation();
  if (!userStore.isLoggedIn) {
    toast.info('请先登录后再收藏题集');
    router.push('/login');
    return;
  }
  try {
    const res = await problemSetApi.toggleFavorite(ps.id);
    ps.favorited = res.favorited;
    ps.participantCount = res.participantCount;
    toast.success(res.favorited ? '已加入收藏' : '已取消收藏');
    if (favoritedOnly.value && !res.favorited) {
      await load();
    }
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败');
  }
}

watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    searchTimer = null;
    reloadFromFirstPage();
  }, 260);
});

watch(
  () => route.query.category,
  (newCat) => {
    category.value = (newCat as string) || '';
  },
);

watch([category, difficulty, favoritedOnly], () => {
  if (searchTimer) {
    clearTimeout(searchTimer);
    searchTimer = null;
  }
  reloadFromFirstPage();
});

watch(page, () => {
  void load();
});

onMounted(() => {
  void load();
});

onBeforeUnmount(() => {
  isUnmounted = true;
  loadRequestId++;
  if (searchTimer) {
    clearTimeout(searchTimer);
    searchTimer = null;
  }
});
</script>

<template>
  <div class="problem-sets container tabular-nums">
    <!-- 顶部标题与攻克进度概览 -->
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
          <span class="progress-fraction mono tabular-nums">
            <strong>{{ solvedSetsCount }}</strong> / {{ total }} 套
          </span>
        </div>
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: `${progressPercentage}%` }"></div>
        </div>
        <span class="progress-percent mono tabular-nums">{{ progressPercentage }}% 完成</span>
      </div>
    </div>

    <!-- 综合筛选工具栏 -->
    <div class="toolbar card">
      <div class="toolbar-left">
        <div class="search-wrap">
          <label for="ps-search" class="sr-only">搜索题集名称或关键词</label>
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <path
              d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z"
            />
          </svg>
          <input
            id="ps-search"
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
            type="button"
            class="tab-btn"
            :class="[{ active: difficulty === lv }, lv ? `tab-${lv.toLowerCase()}` : 'tab-all']"
            :aria-pressed="difficulty === lv"
            @click="difficulty = lv"
          >
            {{ difficultyLabels[lv] }}
          </button>
        </div>
      </div>

      <div class="toolbar-right">
        <div v-if="userStore.isLoggedIn" class="status-tabs">
          <button
            type="button"
            class="status-tab"
            :class="{ active: !favoritedOnly }"
            :aria-pressed="!favoritedOnly"
            @click="favoritedOnly = false"
          >
            全部
          </button>
          <button
            type="button"
            class="status-tab"
            :class="{ active: favoritedOnly }"
            :aria-pressed="favoritedOnly"
            @click="favoritedOnly = true"
          >
            已收藏
          </button>
        </div>
      </div>
    </div>

    <!-- 列表加载与错误状态 -->
    <div v-if="error" class="state-banner is-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="btn-inline-retry" @click="load">重试</button>
    </div>

    <div v-if="loading" class="loading-box card muted" role="status">
      <span class="loading-spin" aria-hidden="true"></span>
      <span>正在加载专题题集…</span>
    </div>

    <div v-else-if="list.length === 0 && !error" class="empty-box card">
      <p class="empty-title">未找到匹配的题集</p>
      <p class="muted empty-desc">尝试调整搜索关键词或重置筛选条件</p>
      <button
        v-if="hasActiveFilters"
        type="button"
        class="btn-inline-retry empty-reset-btn"
        @click="resetFilters"
      >
        清空筛选
      </button>
    </div>

    <!-- 富信息单列题集卡片列表（stretched-link 模式，零交互嵌套） -->
    <div
      v-else-if="list.length > 0"
      :key="`${page}-${category}-${difficulty}-${favoritedOnly}`"
      class="question-list"
    >
      <article
        v-for="(ps, index) in list"
        :key="ps.id"
        class="question-item card"
        :style="{ '--drop-index': Math.min(index, 9) }"
      >
        <!-- 左侧：通关状态图标 + 编号 + 标题与次级信息 -->
        <div class="q-main-layout">
          <div class="q-status-wrap">
            <span
              v-if="(ps.solvedCount || 0) >= ps.itemCount && ps.itemCount > 0"
              class="status-icon solved"
              title="已全部通关 (Completed)"
              aria-label="已全部通关"
            >
              <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                <path
                  d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z"
                />
              </svg>
            </span>
            <span
              v-else
              class="status-icon unsolved"
              title="尚未完全通关"
              aria-label="尚未完全通关"
            >
              <span class="unsolved-ring"></span>
            </span>
          </div>

          <!-- 编号 (Mono) -->
          <span class="q-number mono tabular-nums">#{{ ps.id }}</span>

          <!-- 标题与次级信息 -->
          <div class="q-content">
            <h2 class="q-title">
              <RouterLink :to="`/problem-sets/${ps.id}`" class="q-title-link">
                {{ ps.title }}
              </RouterLink>
            </h2>
            <div class="q-tags-row">
              <span class="meta-tag type mono">
                {{ categoryLabels[ps.category] || ps.category }}
              </span>
              <span class="meta-tag limits mono tabular-nums">{{ ps.itemCount }} 题</span>
              <span class="meta-tag limits mono tabular-nums">
                {{ ps.participantCount || 0 }} 人学习
              </span>
              <span v-if="(ps.solvedCount || 0) > 0" class="meta-tag progress mono tabular-nums">
                已刷 {{ ps.solvedCount }}/{{ ps.itemCount }}
              </span>
            </div>
          </div>
        </div>

        <!-- 右侧：收藏 + 难度徽章 + 快速答题引导 -->
        <div class="q-right-layout">
          <button
            v-if="userStore.isLoggedIn"
            type="button"
            class="star-fav-btn"
            :class="{ 'is-fav': ps.favorited }"
            :title="ps.favorited ? '取消收藏' : '收藏题集'"
            :aria-label="ps.favorited ? `取消收藏 ${ps.title}` : `收藏题集 ${ps.title}`"
            :aria-pressed="Boolean(ps.favorited)"
            @click="onToggleFavorite(ps, $event)"
          >
            <span aria-hidden="true">{{ ps.favorited ? '★' : '☆' }}</span>
          </button>

          <span class="hud-pill" :class="`tone-${getDifficultyTone(ps.difficulty)}`">
            {{ difficultyLabels[ps.difficulty] || ps.difficulty }}
          </span>

          <span class="action-cta" aria-hidden="true">
            <span class="cta-text">{{ (ps.solvedCount || 0) > 0 ? '继续训练' : '开始做题' }}</span>
            <svg
              class="cta-arrow"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path d="M6 3l5 5-5 5" />
            </svg>
          </span>
        </div>
      </article>
    </div>

    <!-- 分页 -->
    <Pagination
      v-if="total > size"
      v-model:page="page"
      :size="size"
      :total="total"
      :disabled="loading"
    />
  </div>
</template>

<style scoped>
.problem-sets {
  padding-top: 32px;
  padding-bottom: 72px;
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
  color: var(--ink-secondary);
}

.head-progress-card {
  padding: 12px 16px;
  border: 1px solid var(--kece-border);
  border-top: 2px solid var(--kece-accent);
  background: var(--surface-white);
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
  font-weight: 600;
}

.progress-fraction {
  font-size: 13px;
  color: var(--ink);
}

.progress-fraction strong {
  color: var(--kece-accent-ink);
}

.progress-bar-track {
  width: 100%;
  height: 6px;
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  overflow: hidden;
  border-radius: 0;
}

.progress-bar-fill {
  height: 100%;
  background: var(--kece-accent);
  transition: width 0.25s ease;
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
  border: 1px solid var(--kece-border);
  background: var(--kece-surface-muted);
  box-shadow: none;
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
  height: 36px;
  padding-left: 32px;
  font-size: 13.5px;
  border-radius: 0;
  background: var(--surface-white);
}

.difficulty-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tab-btn {
  min-height: 36px;
  padding: 5px 12px;
  font-size: 13px;
  font-weight: 500;
  border-radius: 0;
  border: 1px solid var(--kece-border);
  background: var(--surface-white);
  color: var(--ink-secondary);
  cursor: pointer;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.tab-btn:hover {
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink);
  background: var(--kece-accent-soft);
}

.tab-btn.active {
  background: var(--kece-accent);
  border-color: var(--kece-accent);
  color: #ffffff;
  font-weight: 600;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.status-tabs {
  display: inline-flex;
  border: 1px solid var(--kece-border);
  background: var(--surface-white);
  padding: 2px;
  gap: 2px;
}

.status-tab {
  min-height: 30px;
  padding: 4px 10px;
  font-size: 12.5px;
  font-weight: 500;
  border: 1px solid transparent;
  background: transparent;
  color: var(--ink-secondary);
  cursor: pointer;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.status-tab:hover {
  color: var(--kece-accent-ink);
  background: var(--kece-accent-soft);
}

.status-tab.active {
  background: var(--kece-accent-soft);
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink);
  font-weight: 600;
}

/* 题集卡片列表 */
.question-list {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.question-item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border: 1px solid var(--kece-border);
  background: var(--surface-white);
  border-radius: 0;
  box-shadow: none;
  color: inherit;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease;
  animation: kece-card-drop-in 0.44s cubic-bezier(0.22, 1, 0.36, 1) backwards;
  animation-delay: calc(min(var(--drop-index, 0), 11) * 45ms);
}

.question-item:hover {
  background: var(--kece-accent-soft);
  border-color: var(--kece-accent);
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
  color: var(--kece-accent);
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
  border: 1.5px solid #94a3b8;
  border-radius: 50%;
}

.q-number {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-secondary);
  min-width: 40px;
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

.q-title-link {
  color: inherit;
  text-decoration: none;
}

.q-title-link::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
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
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  color: var(--ink-secondary);
}

.meta-tag.type {
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  color: #334155;
  font-weight: 600;
}

.meta-tag.progress {
  background: var(--kece-accent-soft);
  border: 1px solid rgba(19, 121, 105, 0.35);
  color: var(--kece-accent-ink);
  font-weight: 600;
}

/* 右侧：收藏按钮、难度徽标与操作入口 */
.q-right-layout {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.star-fav-btn {
  position: relative;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 36px;
  min-height: 36px;
  padding: 4px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  font-size: 16px;
  color: var(--ink-tertiary);
  cursor: pointer;
  line-height: 1;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.star-fav-btn:hover {
  color: var(--kece-warning);
  background: var(--kece-warning-soft);
  border-color: rgba(178, 92, 22, 0.35);
}

.star-fav-btn.is-fav {
  color: var(--kece-warning);
  background: var(--kece-warning-soft);
  border-color: rgba(178, 92, 22, 0.4);
}

.action-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  transition: color 0.15s ease;
}

.question-item:hover .action-cta {
  color: var(--kece-accent-ink);
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
  border: 1px solid var(--kece-border);
  background: var(--surface-white);
  box-shadow: none;
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

.empty-reset-btn {
  margin-top: 14px;
}

@media (prefers-reduced-motion: reduce) {
  .question-item {
    animation: none !important;
    transition: none !important;
  }

  .progress-bar-fill,
  .tab-btn,
  .status-tab,
  .star-fav-btn,
  .action-cta,
  .cta-arrow {
    transition: none !important;
  }

  .question-item:hover .cta-arrow {
    transform: none;
  }
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
