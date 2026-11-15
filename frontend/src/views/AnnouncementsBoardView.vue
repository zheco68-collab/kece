<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { getAnnouncements, type AnnouncementItem } from '@/api/announcement';
import { ApiError } from '@/api/request';
import Pagination from '@/components/Pagination.vue';
import { stripMarkdown } from '@/utils/plainText';

const loading = ref(false);
const loadError = ref('');
const announcements = ref<AnnouncementItem[]>([]);
const total = ref(0);
const page = ref(1);
const size = 10;

let requestId = 0;
let isUnmounted = false;

function formatDate(str?: string): string {
  if (!str) return '-';
  const d = new Date(str);
  if (isNaN(d.getTime())) return str;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function getSummary(item: AnnouncementItem): string {
  if (item.summary && item.summary.trim()) {
    return stripMarkdown(item.summary, 160);
  }
  if (item.content) {
    const stripped = stripMarkdown(item.content, 160);
    if (stripped) return stripped;
  }
  return '暂无摘要';
}

function isPinned(item: AnnouncementItem): boolean {
  return item.isPinned === 1 || item.isPinned === true || item.isTop === 1 || item.isTop === true;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

async function loadData() {
  const reqId = ++requestId;
  loading.value = true;
  loadError.value = '';
  try {
    const res = await getAnnouncements({
      page: page.value,
      size,
    });
    if (isUnmounted || reqId !== requestId) return;
    announcements.value = res.list || [];
    total.value = res.total || 0;
  } catch (err) {
    if (!isUnmounted && reqId === requestId) {
      loadError.value = err instanceof ApiError ? err.message : '加载公告列表失败，请稍后重试';
    }
  } finally {
    if (!isUnmounted && reqId === requestId) {
      loading.value = false;
    }
  }
}

function resetToFirstPage() {
  if (page.value !== 1) {
    page.value = 1;
  } else {
    void loadData();
  }
}

watch(page, () => {
  void loadData();
  if (typeof window !== 'undefined' && typeof window.scrollTo === 'function') {
    window.scrollTo({
      top: 0,
      behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    });
  }
});

onMounted(() => {
  void loadData();
});

onBeforeUnmount(() => {
  isUnmounted = true;
  requestId++;
});
</script>

<template>
  <div class="announcements-board container tabular-nums">
    <!-- 标头区域：简洁主标题与总篇数 -->
    <header class="board-header">
      <div class="header-main">
        <div class="header-title-group">
          <h1 class="page-title">平台公告栏</h1>
          <p class="muted head-sub">系统通知、竞赛日程与题库更新公告</p>
        </div>
        <div class="meta-tag mono tabular-nums">TOTAL: {{ total }}</div>
      </div>
      <div class="header-divider"></div>
    </header>

    <!-- 错误横幅 -->
    <div v-if="loadError" class="state-banner is-error" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" class="btn-inline-retry" @click="loadData">重试</button>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="state-card card" role="status">
      <div class="loading-spin" aria-hidden="true"></div>
      <p class="muted">正在加载公告列表…</p>
    </div>

    <!-- 空数据提示 -->
    <div v-else-if="announcements.length === 0 && !loadError" class="empty card">
      <p class="empty-title">暂无平台公告</p>
      <button
        v-if="page > 1"
        type="button"
        class="btn-inline-retry empty-reset-btn"
        @click="resetToFirstPage"
      >
        返回第一页
      </button>
    </div>

    <!-- 公告列表卡片集：stretched-link 原生语义链接，零嵌套交互 -->
    <div v-else-if="announcements.length > 0" :key="page" class="announcement-list">
      <article
        v-for="(item, index) in announcements"
        :key="item.id"
        class="announcement-card card"
        :style="{ '--drop-index': Math.min(index, 9) }"
      >
        <!-- 顶层：置顶微标 + 标题 + 查看详情视觉指示 -->
        <div class="card-top">
          <div class="title-wrap">
            <span v-if="isPinned(item)" class="hud-pill tone-teal pin-badge">置顶</span>
            <h2 class="announcement-title" :title="item.title">
              <RouterLink :to="`/announcements/${item.id}`" class="ann-link">
                {{ item.title }}
              </RouterLink>
            </h2>
          </div>
          <span class="view-indicator" aria-hidden="true">
            <span>查看详情</span>
            <span class="btn-arrow">→</span>
          </span>
        </div>

        <!-- 中层：1~2 行简明导读摘要 -->
        <div class="card-mid">
          <p class="summary-text">{{ getSummary(item) }}</p>
        </div>

        <!-- 底层：细线分隔栏，展示发布管理员、时间、阅读量 -->
        <div class="card-bottom">
          <span class="meta-item">
            <span class="meta-label">发布管理员:</span>
            <span class="meta-value">{{ item.authorName || 'admin' }}</span>
          </span>
          <span class="meta-dot" aria-hidden="true">·</span>
          <span class="meta-item">
            <span class="meta-label">发布时间:</span>
            <span class="meta-value mono tabular-nums">{{ formatDate(item.createdAt) }}</span>
          </span>
          <span class="meta-dot" aria-hidden="true">·</span>
          <span class="meta-item">
            <span class="meta-label">阅读量:</span>
            <span class="meta-value mono tabular-nums">{{ item.viewCount ?? 0 }}</span>
          </span>
        </div>
      </article>
    </div>

    <!-- 底部分页 -->
    <div v-if="total > size" class="pagination-wrap">
      <Pagination v-model:page="page" :size="size" :total="total" :disabled="loading" />
    </div>
  </div>
</template>

<style scoped>
.announcements-board {
  padding-top: 28px;
  padding-bottom: 64px;
}

/* 标头区域 */
.board-header {
  margin-bottom: 24px;
}

.header-main {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.header-title-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.page-title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--ink);
}

.head-sub {
  margin: 0;
  font-size: 14px;
  color: var(--ink-secondary);
}

.meta-tag {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  padding: 4px 10px;
  border-radius: 0;
}

.header-divider {
  margin-top: 16px;
  height: 2px;
  background: var(--kece-accent);
}

/* 列表卡片集 */
.announcement-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.announcement-card {
  position: relative;
  padding: 20px 24px 16px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  box-shadow: none;
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease;
  animation: kece-card-drop-in 0.44s cubic-bezier(0.22, 1, 0.36, 1) backwards;
  animation-delay: calc(min(var(--drop-index, 0), 11) * 45ms);
}

.announcement-card:hover {
  border-color: var(--kece-accent);
  background: var(--kece-accent-soft);
}

.announcement-card:hover .announcement-title {
  color: var(--kece-accent-ink);
}

.announcement-card:hover .view-indicator {
  border-color: var(--kece-accent);
  color: var(--kece-accent-ink);
  background: var(--surface-white);
}

/* 顶层 */
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.pin-badge {
  flex-shrink: 0;
  user-select: none;
}

.announcement-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.15s ease;
}

.ann-link {
  color: inherit;
  text-decoration: none;
}

.ann-link::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
}

.view-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  background: var(--kece-surface-muted);
  border: 1px solid var(--kece-border);
  border-radius: 0;
  white-space: nowrap;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background-color 0.15s ease;
  flex-shrink: 0;
}

.btn-arrow {
  font-size: 14px;
  line-height: 1;
  transition: transform 0.15s ease;
}

.announcement-card:hover .btn-arrow {
  transform: translateX(2px);
}

/* 中层：摘要 */
.card-mid {
  margin-bottom: 12px;
}

.summary-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

/* 底层：细线分隔栏 */
.card-bottom {
  border-top: 1px solid var(--kece-border);
  padding-top: 10px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-tertiary);
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.meta-label {
  color: var(--ink-tertiary);
}

.meta-value {
  color: var(--ink-secondary);
  font-weight: 500;
}

.meta-dot {
  color: var(--line);
}

.empty-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-secondary);
}

.empty-reset-btn {
  margin-top: 12px;
}

.pagination-wrap {
  margin-top: 28px;
}

@media (prefers-reduced-motion: reduce) {
  .announcement-card {
    animation: none !important;
    transition: none !important;
  }

  .announcement-title,
  .view-indicator,
  .btn-arrow {
    transition: none !important;
  }

  .announcement-card:hover .btn-arrow {
    transform: none;
  }
}

@media (max-width: 640px) {
  .card-top {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .view-indicator {
    align-self: flex-start;
  }
}
</style>
