<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { problemSetApi, type ProblemSetDetail, type ProblemSetItemVO } from '@/api/problemSet';
import { useToast } from '@/composables/useToast';
import { useUserStore } from '@/stores/user';
import type { DbQuestionType, Difficulty, ObjectiveType } from '@/types';
import { renderMarkdown } from '@/utils/markdown';
import {
  DB_TYPE_TONE_MAP,
  DIFFICULTY_TONE_MAP,
  formatLanguageLabel,
  getDialectLabel,
  LANGUAGE_ORDER,
  OBJECTIVE_TYPE_LABELS,
  OBJECTIVE_TYPE_TONE_MAP,
  type PillTone,
} from '@/utils/questionMeta';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const toast = useToast();

const setId = computed(() => Number(route.params.id));
const detail = ref<ProblemSetDetail | null>(null);
const descriptionHtml = computed(() => renderMarkdown(detail.value?.description || ''));
const loading = ref(true);
const error = ref('');
const expandedSections = ref<Set<number>>(new Set());
const statusFilter = ref<'ALL' | 'UNSOLVED' | 'SOLVED'>('ALL');

let detailRequestId = 0;
let isUnmounted = false;

const categoryLabels: Record<string, string> = {
  ALGO: '算法编程',
  DB: '数据库 SQL',
  OBJECTIVE: '标准客观',
  MIXED: '综合题集',
};

const difficultyLabels: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
  MIXED: '综合',
};

function getDifficultyTone(diff?: string): PillTone {
  const key = (diff || '').toUpperCase() as Difficulty;
  return DIFFICULTY_TONE_MAP[key] ?? 'neutral';
}

function getItemBadge(item: ProblemSetItemVO): { label: string; tone: PillTone } {
  const d = (item.difficulty || '').toUpperCase();

  if (d in DIFFICULTY_TONE_MAP) {
    const diffKey = d as Difficulty;
    return {
      label: difficultyLabels[diffKey] || diffKey,
      tone: DIFFICULTY_TONE_MAP[diffKey],
    };
  }

  if (d in DB_TYPE_TONE_MAP) {
    const dbKey = d as DbQuestionType;
    return {
      label: dbKey === 'SELECT' ? 'SELECT 查询' : 'DML 操作',
      tone: DB_TYPE_TONE_MAP[dbKey],
    };
  }

  if (d in OBJECTIVE_TYPE_LABELS) {
    const objKey = d as ObjectiveType;
    return {
      label: OBJECTIVE_TYPE_LABELS[objKey],
      tone: OBJECTIVE_TYPE_TONE_MAP[objKey],
    };
  }

  return { label: item.difficulty || '综合', tone: 'neutral' };
}

const progressPercent = computed(() => {
  if (!detail.value || !detail.value.itemCount) return 0;
  return Math.min(
    100,
    Math.round(((detail.value.solvedCount || 0) / detail.value.itemCount) * 100),
  );
});

async function loadDetail() {
  const currentId = setId.value;
  if (!Number.isFinite(currentId) || currentId <= 0) {
    loading.value = false;
    error.value = '无效的题集编号';
    return;
  }
  const reqId = ++detailRequestId;
  loading.value = true;
  error.value = '';
  try {
    const res = await problemSetApi.detail(currentId);
    if (isUnmounted || reqId !== detailRequestId) return;
    detail.value = res;
  } catch (e) {
    if (!isUnmounted && reqId === detailRequestId) {
      error.value = e instanceof Error ? e.message : '加载题集详情失败';
    }
  } finally {
    if (!isUnmounted && reqId === detailRequestId) {
      loading.value = false;
    }
  }
}

async function toggleFav() {
  if (!detail.value) return;
  if (!userStore.isLoggedIn) {
    toast.info('请先登录后再收藏题集');
    router.push('/login');
    return;
  }
  try {
    const res = await problemSetApi.toggleFavorite(detail.value.id);
    detail.value.favorited = res.favorited;
    detail.value.participantCount = res.participantCount;
    toast.success(res.favorited ? '已收藏该题集' : '已取消收藏');
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败');
  }
}

function toggleSection(secId: number) {
  const next = new Set(expandedSections.value);
  if (next.has(secId)) {
    next.delete(secId);
  } else {
    next.add(secId);
  }
  expandedSections.value = next;
}

function filteredSectionItems(items: ProblemSetItemVO[]): ProblemSetItemVO[] {
  if (statusFilter.value === 'SOLVED') {
    return items.filter((i) => i.solved);
  }
  if (statusFilter.value === 'UNSOLVED') {
    return items.filter((i) => !i.solved);
  }
  return items;
}

function getItemRoute(item: ProblemSetItemVO) {
  const query = { setId: String(setId.value), itemId: String(item.id) };
  const t = (item.questionType || 'ALGO').toUpperCase();
  if (t === 'DB') {
    return { path: `/database/${item.questionId}`, query };
  }
  if (t === 'OBJECTIVE') {
    return { path: `/objective/${item.questionId}`, query };
  }
  return { path: `/questions/${item.questionId}`, query };
}

function touchItem(item: ProblemSetItemVO) {
  if (userStore.isLoggedIn && detail.value) {
    problemSetApi.touch(detail.value.id, item.id).catch(() => {});
  }
}

function openItem(item: ProblemSetItemVO) {
  if (!item.sourceAlive) {
    toast.info('该原题已归档或下架，暂不可进入作答');
    return;
  }
  touchItem(item);
  router.push(getItemRoute(item));
}

watch(
  () => route.params.id,
  () => {
    expandedSections.value = new Set();
    statusFilter.value = 'ALL';
    void loadDetail();
  },
);

onMounted(() => {
  void loadDetail();
});

onBeforeUnmount(() => {
  isUnmounted = true;
  detailRequestId++;
});
</script>

<template>
  <div class="set-detail-page container tabular-nums">
    <!-- 加载与异常状态 -->
    <div v-if="loading" class="state-card card" role="status">
      <div class="loading-spinner" aria-hidden="true"></div>
      <p class="muted">正在加载题集章节与同步 AC 进度…</p>
    </div>

    <div v-else-if="error" class="state-card card" role="alert">
      <div class="error-title">加载失败</div>
      <p class="error-msg">{{ error }}</p>
      <div class="error-actions">
        <button type="button" class="btn btn-primary btn-sm" @click="loadDetail">重试</button>
        <button type="button" class="btn btn-ghost btn-sm" @click="router.push('/problem-sets')">
          返回题集列表
        </button>
      </div>
    </div>

    <template v-else-if="detail">
      <!-- 顶部题集总览仪表盘 -->
      <header class="set-hero card">
        <div class="hero-top-bar">
          <button type="button" class="btn-back" @click="router.push('/problem-sets')">
            <span aria-hidden="true">←</span> 返回题集大厅
          </button>

          <div class="top-right-actions">
            <button
              type="button"
              class="btn-fav"
              :class="{ 'is-fav': detail.favorited }"
              :aria-pressed="Boolean(detail.favorited)"
              @click="toggleFav"
            >
              <span aria-hidden="true">{{ detail.favorited ? '★' : '☆' }}</span>
              <span>{{ detail.favorited ? '已收藏' : '收藏题集' }}</span>
              <span class="fav-count mono tabular-nums">({{ detail.participantCount }})</span>
            </button>
          </div>
        </div>

        <div class="hero-body">
          <div class="hero-main">
            <div class="badge-row">
              <span v-if="detail.isOfficial === 1" class="badge-official mono">
                OFFICIAL 精选
              </span>
              <span class="badge-cat mono">
                {{ categoryLabels[detail.category] || detail.category }}
              </span>
              <span class="hud-pill" :class="`tone-${getDifficultyTone(detail.difficulty)}`">
                {{ difficultyLabels[detail.difficulty] || detail.difficulty }}
              </span>
              <span class="author-meta mono">编排者：{{ detail.authorName }}</span>
            </div>

            <h1 class="set-title">{{ detail.title }}</h1>
            <div v-if="detail.subtitle" class="set-subtitle">{{ detail.subtitle }}</div>
            <!-- eslint-disable-next-line vue/no-v-html -- 内容已由 renderMarkdown 清洗 -->
            <div v-if="detail.description" class="set-desc prose" v-html="descriptionHtml"></div>

            <div v-if="detail.tags && detail.tags.length > 0" class="tag-row">
              <span v-for="t in detail.tags" :key="t" class="tag-chip"># {{ t }}</span>
            </div>
          </div>

          <!-- 右侧攻克进度与断点续刷控制盒 -->
          <aside class="hero-progress-box">
            <div class="prog-header mono tabular-nums">
              <span class="prog-label">MY PROGRESS</span>
              <span class="prog-pct">{{ progressPercent }}%</span>
            </div>

            <div class="prog-numbers mono tabular-nums">
              <span class="solved-big">{{ detail.solvedCount || 0 }}</span>
              <span class="total-slash">/ {{ detail.itemCount }} 题已攻克</span>
            </div>

            <div class="prog-bar-track">
              <div class="prog-bar-fill" :style="{ width: `${progressPercent}%` }"></div>
            </div>

            <div class="comp-pills mono tabular-nums">
              <span v-if="detail.algoCount > 0">算法 {{ detail.algoCount }}</span>
              <span v-if="detail.dbCount > 0">SQL {{ detail.dbCount }}</span>
              <span v-if="detail.objectiveCount > 0">客观 {{ detail.objectiveCount }}</span>
            </div>

            <button
              v-if="detail.resumeItem"
              type="button"
              class="btn btn-primary btn-block resume-btn"
              @click="openItem(detail.resumeItem)"
            >
              <span>
                {{ (detail.solvedCount || 0) > 0 ? '继续上次刷题 →' : '从第一题开始 →' }}
              </span>
            </button>
          </aside>
        </div>
      </header>

      <!-- 章节列表工具栏 -->
      <div class="sections-toolbar">
        <div class="toolbar-left mono tabular-nums">
          <span>CHAPTERS ({{ detail.sections.length }})</span>
          <span>·</span>
          <span>TOTAL {{ detail.itemCount }} ITEMS</span>
        </div>

        <div class="status-tabs">
          <button
            type="button"
            class="status-tab tabular-nums"
            :class="{ active: statusFilter === 'ALL' }"
            :aria-pressed="statusFilter === 'ALL'"
            @click="statusFilter = 'ALL'"
          >
            全部 ({{ detail.itemCount }})
          </button>
          <button
            type="button"
            class="status-tab tabular-nums"
            :class="{ active: statusFilter === 'UNSOLVED' }"
            :aria-pressed="statusFilter === 'UNSOLVED'"
            @click="statusFilter = 'UNSOLVED'"
          >
            未攻克 ({{ Math.max(0, detail.itemCount - (detail.solvedCount || 0)) }})
          </button>
          <button
            type="button"
            class="status-tab tabular-nums"
            :class="{ active: statusFilter === 'SOLVED' }"
            :aria-pressed="statusFilter === 'SOLVED'"
            @click="statusFilter = 'SOLVED'"
          >
            已攻克 ({{ detail.solvedCount || 0 }})
          </button>
        </div>
      </div>

      <!-- 章节折叠面板列表 -->
      <div class="sections-list">
        <section
          v-for="(sec, sIdx) in detail.sections"
          :key="sec.id || sIdx"
          class="section-block card"
        >
          <h2 class="section-heading">
            <button
              type="button"
              class="section-head"
              :aria-expanded="expandedSections.has(sec.id)"
              :aria-controls="`section-panel-${sec.id}`"
              @click="toggleSection(sec.id)"
            >
              <span class="sec-head-left">
                <span class="sec-index mono tabular-nums">
                  {{ String(sIdx + 1).padStart(2, '0') }}
                </span>
                <span class="sec-title-group">
                  <span class="sec-title">{{ sec.title }}</span>
                  <span v-if="sec.description" class="sec-desc">{{ sec.description }}</span>
                </span>
              </span>

              <span class="sec-head-right mono tabular-nums">
                <span class="sec-stat">{{ sec.solvedCount }} / {{ sec.totalCount }} 已通过</span>
                <span class="sec-toggle" aria-hidden="true">
                  {{ expandedSections.has(sec.id) ? '− 收起' : '+ 展开' }}
                </span>
              </span>
            </button>
          </h2>

          <div
            v-if="expandedSections.has(sec.id)"
            :id="`section-panel-${sec.id}`"
            :key="`${sec.id}-${statusFilter}`"
            class="section-items"
          >
            <div v-if="filteredSectionItems(sec.items).length === 0" class="section-empty muted">
              当前筛选条件下暂无题目
            </div>

            <!-- 题目条目：stretched-link 原生语义导航 -->
            <article
              v-for="(item, idx) in filteredSectionItems(sec.items)"
              :key="item.id"
              class="question-item card"
              :class="{ 'is-dead': !item.sourceAlive }"
              :style="{ '--drop-index': Math.min(idx, 9) }"
            >
              <!-- 左侧：解题状态图标 + 题号 + 标题与元数据 -->
              <div class="q-main-layout">
                <div class="q-status-wrap">
                  <span
                    v-if="item.solved"
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

                <!-- 题号 (Mono) -->
                <span class="q-number mono tabular-nums">#{{ item.questionId }}</span>

                <!-- 标题与次级信息 -->
                <div class="q-content">
                  <h3 class="q-title">
                    <RouterLink
                      v-if="item.sourceAlive"
                      :to="getItemRoute(item)"
                      class="q-title-link"
                      @click="touchItem(item)"
                    >
                      {{ item.title }}
                    </RouterLink>
                    <button
                      v-else
                      type="button"
                      class="q-title-link is-dead-btn"
                      aria-disabled="true"
                      @click="openItem(item)"
                    >
                      <span>{{ item.title }}</span>
                      <span class="hud-pill tone-neutral dead-tag">原题已归档</span>
                    </button>
                  </h3>

                  <!-- 相应题库对应的次级元数据行 -->
                  <div class="q-tags-row">
                    <template v-if="(item.questionType || detail.category) === 'ALGO'">
                      <span class="meta-tag limits mono tabular-nums">1000ms / 256MB</span>
                      <span v-for="lang in LANGUAGE_ORDER" :key="lang" class="meta-tag lang mono">
                        {{ formatLanguageLabel(lang) }}
                      </span>
                    </template>

                    <template v-else-if="(item.questionType || detail.category) === 'DB'">
                      <span class="meta-tag dialect mono">{{ getDialectLabel('MYSQL') }}</span>
                      <span class="meta-tag limits mono">无序比对</span>
                    </template>

                    <template v-else>
                      <span class="meta-tag type mono">标准客观题</span>
                      <span class="meta-tag limits mono">即时判分</span>
                    </template>
                  </div>
                </div>
              </div>

              <!-- 右侧：难度徽章 + 快速答题引导 -->
              <div class="q-right-layout">
                <span class="hud-pill" :class="`tone-${getItemBadge(item).tone}`">
                  {{ getItemBadge(item).label }}
                </span>

                <span class="action-cta" aria-hidden="true">
                  <span class="cta-text">{{ item.sourceAlive ? '开始做题' : '已归档' }}</span>
                  <svg
                    v-if="item.sourceAlive"
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
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.set-detail-page {
  padding-top: 28px;
  padding-bottom: 72px;
}

.set-hero {
  padding: 24px 28px 28px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  margin-bottom: 24px;
}

.hero-top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 18px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--kece-border);
}

.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 6px 12px;
  border: 1px solid var(--kece-border);
  background: var(--kece-surface-muted);
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-secondary);
  transition:
    border-color 0.15s ease,
    color 0.15s ease,
    background-color 0.15s ease;
}

.btn-back:hover {
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink);
  background: var(--kece-accent-soft);
}

.top-right-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-fav {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 34px;
  padding: 6px 14px;
  border: 1px solid var(--kece-border);
  background: var(--kece-surface-muted);
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.btn-fav:hover {
  border-color: var(--kece-warning);
  color: var(--kece-warning);
  background: var(--kece-warning-soft);
}

.btn-fav.is-fav {
  background: var(--kece-warning-soft);
  border-color: rgba(146, 64, 14, 0.4);
  color: var(--kece-warning);
}

.fav-count {
  margin-left: 2px;
  font-size: 12px;
}

.hero-body {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 32px;
  align-items: start;
}

.hero-main {
  display: flex;
  flex-direction: column;
}

.badge-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.badge-official {
  font-size: 11px;
  font-weight: 700;
  padding: 3px 8px;
  background: var(--ink);
  color: #ffffff;
  letter-spacing: 0.5px;
}

.badge-cat {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  color: var(--ink-secondary);
}

.author-meta {
  font-size: 12px;
  color: var(--ink-tertiary);
  margin-left: 4px;
}

.set-title {
  font-size: 26px;
  font-weight: 800;
  color: var(--ink);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
}

.set-subtitle {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-secondary);
  margin-bottom: 12px;
}

.set-desc.prose {
  padding: 0;
  font-size: 14px;
  color: var(--ink-secondary);
  line-height: 1.65;
  margin: 0 0 16px;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.set-desc :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 0;
  margin: 12px 0;
  display: block;
}

.set-desc :deep(p) {
  margin: 0 0 8px;
}

.set-desc :deep(p:last-child) {
  margin-bottom: 0;
}

.set-desc :deep(strong) {
  color: var(--ink);
  font-weight: 600;
}

.set-desc :deep(a) {
  color: var(--kece-accent-ink);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-chip {
  font-size: 12px;
  padding: 2px 9px;
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  color: var(--ink-secondary);
}

/* 右侧进度卡片 */
.hero-progress-box {
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  border-top: 2px solid var(--kece-accent);
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-self: start;
}

.prog-header {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-tertiary);
  letter-spacing: 0.6px;
}

.prog-pct {
  color: var(--kece-accent-ink);
  font-size: 13px;
}

.prog-numbers {
  margin: 8px 0 10px;
}

.solved-big {
  font-size: 32px;
  font-weight: 700;
  color: var(--ink);
}

.total-slash {
  font-size: 13px;
  color: var(--ink-secondary);
  margin-left: 6px;
}

.prog-bar-track {
  width: 100%;
  height: 8px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  margin-bottom: 12px;
}

.prog-bar-fill {
  height: 100%;
  background: var(--kece-accent);
  transition: width 0.25s ease;
}

.comp-pills {
  display: flex;
  gap: 8px;
  font-size: 11.5px;
  color: var(--ink-secondary);
  margin-bottom: 14px;
}

.comp-pills span {
  padding: 2px 7px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
}

.resume-btn {
  padding: 11px 18px;
  font-size: 14px;
  font-weight: 600;
  background: var(--kece-accent);
}

.resume-btn:hover:not(:disabled) {
  opacity: 1;
  background: var(--kece-accent-hover);
}

/* 工具栏 */
.sections-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.toolbar-left {
  display: flex;
  gap: 8px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-tertiary);
}

.status-tabs {
  display: flex;
  gap: 6px;
}

.status-tab {
  min-height: 32px;
  padding: 5px 12px;
  font-size: 12.5px;
  font-weight: 600;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  color: var(--ink-secondary);
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.status-tab:hover {
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink);
  background: var(--kece-accent-soft);
}

.status-tab.active {
  background: var(--kece-accent);
  color: #ffffff;
  border-color: var(--kece-accent);
}

/* 章节卡片列表 */
.sections-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-block {
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  overflow: hidden;
}

.section-heading {
  margin: 0;
  font-size: inherit;
  font-weight: inherit;
}

.section-head {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 22px;
  background: var(--kece-surface-muted);
  border: none;
  border-bottom: 1px solid var(--kece-border);
  cursor: pointer;
  user-select: none;
  text-align: left;
  transition: background-color 0.15s ease;
}

.section-head:hover {
  background: var(--kece-accent-soft);
}

.sec-head-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.sec-index {
  width: 32px;
  height: 32px;
  background: var(--ink);
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.sec-title-group {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.sec-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
}

.sec-desc {
  font-size: 12.5px;
  font-weight: 400;
  color: var(--ink-secondary);
  margin: 2px 0 0;
}

.sec-head-right {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  color: var(--ink-secondary);
  flex-shrink: 0;
}

.sec-toggle {
  padding: 2px 8px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  color: var(--ink);
}

/* 题目列表容器与条目 */
.section-items {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  background: var(--bg);
}

.section-empty {
  padding: 24px;
  text-align: center;
  font-size: 13px;
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

.question-item:hover:not(.is-dead) {
  background: var(--kece-accent-soft);
  border-color: var(--kece-accent);
}

.question-item.is-dead {
  background: var(--kece-surface-muted);
  border-color: var(--kece-border);
  cursor: not-allowed;
}

.question-item.is-dead .q-title,
.question-item.is-dead .q-number,
.question-item.is-dead .action-cta {
  color: var(--ink-tertiary);
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
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.q-title-link::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
}

.is-dead-btn {
  padding: 0;
  border: none;
  background: transparent;
  font: inherit;
  color: var(--ink-tertiary);
  cursor: not-allowed;
  text-align: left;
}

.dead-tag {
  margin-left: 6px;
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

.meta-tag.lang,
.meta-tag.dialect,
.meta-tag.type {
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  color: #334155;
  font-weight: 600;
}

/* 右侧：难度徽标与操作入口 */
.q-right-layout {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
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

.question-item:hover:not(.is-dead) .action-cta {
  color: var(--kece-accent-ink);
}

.question-item:hover:not(.is-dead) .cta-arrow {
  transform: translateX(3px);
}

.cta-arrow {
  width: 14px;
  height: 14px;
  transition: transform 0.15s ease;
}

/* 状态卡片 */
.state-card {
  padding: 56px 24px;
  text-align: center;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
}

.error-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--danger);
  margin-bottom: 8px;
}

.error-msg {
  font-size: 14px;
  color: var(--ink-secondary);
  margin-bottom: 20px;
}

.error-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

@media (prefers-reduced-motion: reduce) {
  .question-item {
    animation: none !important;
    transition: none !important;
  }

  .btn-back,
  .btn-fav,
  .prog-bar-fill,
  .status-tab,
  .section-head,
  .action-cta,
  .cta-arrow {
    transition: none !important;
  }

  .question-item:hover:not(.is-dead) .cta-arrow {
    transform: none;
  }
}

@media (max-width: 860px) {
  .hero-body {
    grid-template-columns: 1fr;
  }

  .sections-toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
}
</style>
