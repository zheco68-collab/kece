<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  listAdminAnnouncements,
  deleteAdminAnnouncement,
  toggleAdminAnnouncementTop,
  updateAdminAnnouncementStatus,
  type AnnouncementItem,
} from '@/api/announcement'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const { confirm } = useConfirm()
const toast = useToast()

const keyword = ref('')
const statusFilter = ref<string>('')
const page = ref(1)
const size = 15

const rows = ref<AnnouncementItem[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const columns = [
  { key: 'id', label: 'ID', width: '56px', align: 'center' as const },
  { key: 'title', label: '公告标题' },
  { key: 'authorName', label: '发布管理员', width: '120px', align: 'center' as const },
  { key: 'isTop', label: '置顶', width: '90px', align: 'center' as const },
  { key: 'status', label: '状态', width: '90px', align: 'center' as const },
  { key: 'viewCount', label: '阅读量', width: '80px', align: 'center' as const },
  { key: 'createdAt', label: '发布时间', width: '150px', align: 'center' as const },
]

function formatDate(str?: string): string {
  if (!str) return '-'
  const d = new Date(str)
  if (isNaN(d.getTime())) return str
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function isPinned(row: AnnouncementItem): boolean {
  return (
    row.isPinned === 1 ||
    row.isPinned === true ||
    row.isTop === 1 ||
    row.isTop === true
  )
}

function isPublished(row: AnnouncementItem): boolean {
  return row.status === 1 || row.status === 'PUBLISHED'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await listAdminAnnouncements({
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      page: page.value,
      size,
    })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '加载公告列表失败'
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  if (page.value === 1) {
    load()
  } else {
    page.value = 1
  }
}

let keywordTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (keywordTimer !== null) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(resetAndLoad, 300)
})
watch(statusFilter, resetAndLoad)
watch(page, load)

async function handleToggleTop(row: AnnouncementItem) {
  const current = isPinned(row)
  const next = !current
  try {
    await toggleAdminAnnouncementTop(row.id, next)
    row.isTop = next
    toast.success(next ? `已将「${row.title}」设为置顶` : `已取消「${row.title}」的置顶`)
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '切换置顶状态失败')
  }
}

async function handleToggleStatus(row: AnnouncementItem) {
  const published = isPublished(row)
  const nextStatus = published ? 'DRAFT' : 'PUBLISHED'
  try {
    await updateAdminAnnouncementStatus(row.id, nextStatus)
    row.status = nextStatus
    toast.success(nextStatus === 'PUBLISHED' ? '已将公告公开发布' : '已转为草稿下架')
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '更新状态失败')
  }
}

async function removeAnnouncement(row: AnnouncementItem) {
  const ok = await confirm({
    title: '删除公告',
    message: `确定要彻底删除公告「${row.title}」吗？此操作无法撤销。`,
    confirmText: '确认删除',
  })
  if (!ok) return

  try {
    await deleteAdminAnnouncement(row.id)
    toast.success(`已删除公告「${row.title}」`)
    load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除公告失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-announcements container">
    <!-- 页面标题 -->
    <div class="page-header">
      <div class="header-titles">
        <h1 class="page-title">公告管理</h1>
        <span class="page-subtitle">// ANNOUNCEMENT OPERATIONS</span>
      </div>
      <button
        class="btn btn-primary btn-create"
        type="button"
        @click="router.push('/admin/announcements/create')"
      >
        + 发布新公告
      </button>
    </div>

    <!-- 顶部紧凑工业工具栏：检索与状态筛选 -->
    <div class="toolbar">
      <div class="search-box">
        <input
          v-model="keyword"
          class="input search-input"
          type="text"
          placeholder="搜索公告标题或内容关键字…"
        />
      </div>
      <div class="filter-box">
        <select v-model="statusFilter" class="input filter-select">
          <option value="">全部状态</option>
          <option value="PUBLISHED">已公开</option>
          <option value="DRAFT">草稿/已下架</option>
        </select>
      </div>
    </div>

    <!-- 错误横幅 -->
    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

    <!-- 数据表格 -->
    <DataTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      row-key="id"
      empty-text="暂无公告数据，点击右上角「+ 发布新公告」添加"
    >
      <template #cell-title="{ row }">
        <div class="title-cell">
          <span
            class="title-text"
            :title="row.title"
            @click="router.push(`/admin/announcements/${row.id}/edit`)"
          >
            {{ row.title }}
          </span>
          <span v-if="row.summary" class="summary-hint" :title="row.summary">
            {{ row.summary }}
          </span>
        </div>
      </template>

      <template #cell-authorName="{ row }">
        <span class="mono author-cell">{{ row.authorName || 'admin' }}</span>
      </template>

      <template #cell-isTop="{ row }">
        <button
          type="button"
          class="toggle-top-btn"
          :class="{ 'is-pinned': isPinned(row) }"
          :title="isPinned(row) ? '点击取消置顶' : '点击设为置顶'"
          @click="handleToggleTop(row)"
        >
          {{ isPinned(row) ? '★ 置顶' : '— 普通' }}
        </button>
      </template>

      <template #cell-status="{ row }">
        <button
          type="button"
          class="status-btn"
          :class="isPublished(row) ? 'status-published' : 'status-draft'"
          :title="isPublished(row) ? '点击下架设为草稿' : '点击发布上线'"
          @click="handleToggleStatus(row)"
        >
          {{ isPublished(row) ? '已公开' : '草稿' }}
        </button>
      </template>

      <template #cell-viewCount="{ row }">
        <span class="mono">{{ row.viewCount ?? 0 }}</span>
      </template>

      <template #cell-createdAt="{ row }">
        <span class="mono time-cell">{{ formatDate(row.createdAt) }}</span>
      </template>

      <template #actions="{ row }">
        <div class="action-buttons">
          <button
            class="action-btn"
            type="button"
            title="前台查看效果"
            @click="router.push(`/announcements/${row.id}`)"
          >
            前台查看
          </button>
          <button
            class="action-btn"
            type="button"
            title="编辑公告"
            @click="router.push(`/admin/announcements/${row.id}/edit`)"
          >
            编辑
          </button>
          <button
            class="action-btn btn-danger"
            type="button"
            title="删除公告"
            @click="removeAnnouncement(row)"
          >
            删除
          </button>
        </div>
      </template>
    </DataTable>

    <!-- 分页 -->
    <Pagination v-model:page="page" :size="size" :total="total" />
  </div>
</template>

<style scoped>
.admin-announcements {
  padding-top: 16px;
  padding-bottom: 48px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0 20px;
}

.header-titles {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--ink);
}

.page-subtitle {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-tertiary);
  letter-spacing: 0.5px;
}

.btn-create {
  height: 38px;
  padding: 0 16px;
  font-size: 13.5px;
  border-radius: 0;
}

/* 工业工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.search-box {
  flex: 1;
}

.search-input {
  height: 38px;
  font-size: 14px;
  border-radius: 0;
  background: #ffffff;
}

.filter-box {
  width: 180px;
}

.filter-select {
  height: 38px;
  font-size: 14px;
  border-radius: 0;
  background: #ffffff;
}

/* 单元格定制 */
.title-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  text-align: left;
}

.title-text {
  font-weight: 600;
  color: var(--ink);
  cursor: pointer;
  transition: color 0.15s ease;
}

.title-text:hover {
  color: #1677ff;
}

.summary-hint {
  font-size: 12px;
  color: var(--ink-tertiary);
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.author-cell {
  font-size: 13px;
  color: var(--ink-secondary);
}

.time-cell {
  font-size: 12px;
  color: var(--ink-tertiary);
}

/* 按钮式微调置顶开关 */
.toggle-top-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px 8px;
  font-size: 11.5px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink-tertiary);
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}

.toggle-top-btn.is-pinned {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}

.toggle-top-btn:hover {
  border-color: var(--ink);
}

/* 发布状态按钮 */
.status-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px 8px;
  font-size: 11.5px;
  font-weight: 600;
  border-radius: 0;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
}

.status-published {
  background: #ebfbee;
  color: #2b8a3e;
  border-color: #b2f2bb;
}

.status-published:hover {
  background: #d3f9d8;
}

.status-draft {
  background: #f1f3f5;
  color: #868e96;
  border-color: #dee2e6;
}

.status-draft:hover {
  background: #e9ecef;
}

/* 操作按钮 */
.action-buttons {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.action-btn {
  padding: 4px 8px;
  font-size: 12px;
  color: var(--ink-secondary);
  border: 1px solid var(--line);
  background: #ffffff;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
  background: var(--surface);
}

.action-btn.btn-danger {
  color: var(--danger);
  border-color: #ffa8a8;
}

.action-btn.btn-danger:hover {
  background: var(--danger-bg);
  border-color: var(--danger);
}
</style>
