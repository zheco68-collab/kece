<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getAnnouncements, type AnnouncementItem } from '@/api/announcement'
import { ApiError } from '@/api/request'
import Pagination from '@/components/Pagination.vue'

const router = useRouter()

const loading = ref(false)
const loadError = ref('')
const announcements = ref<AnnouncementItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 10

function formatDate(str?: string): string {
  if (!str) return '-'
  const d = new Date(str)
  if (isNaN(d.getTime())) return str
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function getSummary(item: AnnouncementItem): string {
  if (item.summary && item.summary.trim()) {
    return item.summary.trim()
  }
  if (item.content) {
    // 移除 markdown 符号生成简短摘要
    return item.content
      .replace(/[#*`~>[\]()!-]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 160)
  }
  return '暂无摘要'
}

function isPinned(item: AnnouncementItem): boolean {
  return (
    item.isPinned === 1 ||
    item.isPinned === true ||
    item.isTop === 1 ||
    item.isTop === true
  )
}

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await getAnnouncements({
      page: page.value,
      size,
    })
    announcements.value = res.list || []
    total.value = res.total || 0
  } catch (err) {
    loadError.value = err instanceof ApiError ? err.message : '加载公告列表失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function navigateToDetail(id: number) {
  router.push(`/announcements/${id}`)
}

watch(page, () => {
  loadData()
  window.scrollTo({ top: 0, behavior: 'smooth' })
})

onMounted(loadData)
</script>

<template>
  <div class="announcements-board container">
    <!-- 标头区域：平台公告栏 // BULLETIN BOARD，显示总篇数 -->
    <header class="board-header">
      <div class="header-main">
        <h1 class="page-title">
          平台公告栏 <span class="sub-slash">// BULLETIN BOARD</span>
        </h1>
        <div class="meta-tag mono">TOTAL: {{ total }}</div>
      </div>
      <div class="header-divider"></div>
    </header>

    <!-- 错误横幅 -->
    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

    <!-- 加载状态 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spin"></div>
      <p class="muted">正在加载公告列表…</p>
    </div>

    <!-- 空数据提示 -->
    <div v-else-if="announcements.length === 0" class="empty card">
      暂无平台公告
    </div>

    <!-- 公告列表卡片集：纯直角、厚度舒适、三层结构 -->
    <div v-else class="announcement-list">
      <article
        v-for="item in announcements"
        :key="item.id"
        class="announcement-card card"
        @click="navigateToDetail(item.id)"
      >
        <!-- 顶层：置顶微标 + 标题 + 查看详情操作按钮 -->
        <div class="card-top">
          <div class="title-wrap">
            <span v-if="isPinned(item)" class="pin-badge">置顶</span>
            <h2 class="announcement-title" :title="item.title">
              {{ item.title }}
            </h2>
          </div>
          <button
            type="button"
            class="view-btn"
            @click.stop="navigateToDetail(item.id)"
          >
            <span>查看详情</span>
            <span class="btn-arrow">→</span>
          </button>
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
          <span class="meta-dot">·</span>
          <span class="meta-item">
            <span class="meta-label">发布时间:</span>
            <span class="meta-value mono">{{ formatDate(item.createdAt) }}</span>
          </span>
          <span class="meta-dot">·</span>
          <span class="meta-item">
            <span class="meta-label">阅读量:</span>
            <span class="meta-value mono">{{ item.viewCount ?? 0 }}</span>
          </span>
        </div>
      </article>
    </div>

    <!-- 底部分页 -->
    <div v-if="total > size" class="pagination-wrap">
      <Pagination v-model:page="page" :size="size" :total="total" />
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
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--ink);
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.sub-slash {
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-tertiary);
  letter-spacing: 1px;
}

.meta-tag {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 4px 10px;
  border-radius: 0;
}

.header-divider {
  margin-top: 14px;
  height: 2px;
  background: var(--ink);
}

/* 列表卡片集 */
.announcement-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 单张公告卡片：舒适厚度（~100px），直角工业基底 */
.announcement-card {
  padding: 20px 24px 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  box-shadow: none;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease;
  position: relative;
}

.announcement-card:hover {
  border-color: var(--line-strong);
  background: #ffffff;
}

.announcement-card:hover .announcement-title {
  color: #1677ff;
}

.announcement-card:hover .view-btn {
  border-color: #1677ff;
  color: #1677ff;
  background: #f0f7ff;
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
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 22px;
  padding: 0 6px;
  background: var(--ink);
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  border-radius: 0;
  letter-spacing: 0.5px;
  white-space: nowrap;
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

.view-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  background: var(--surface-white);
  border: 1px solid var(--line);
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
  border-top: 1px solid var(--line);
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

.pagination-wrap {
  margin-top: 28px;
}

@media (max-width: 640px) {
  .card-top {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .view-btn {
    align-self: flex-start;
  }
}
</style>
