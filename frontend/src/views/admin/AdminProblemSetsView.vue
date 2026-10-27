<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  listAdminProblemSets,
  deleteAdminProblemSet,
  type ProblemSetSummary
} from '@/api/problemSet'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const { confirm } = useConfirm()
const toast = useToast()

const keyword = ref('')
const categoryFilter = ref('')
const page = ref(1)
const size = 20

const rows = ref<ProblemSetSummary[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const columns = [
  { key: 'id', label: 'ID', width: '48px', align: 'center' as const },
  { key: 'title', label: '题集标题' },
  { key: 'category', label: '题型', width: '115px', align: 'center' as const },
  { key: 'difficulty', label: '难度', width: '76px', align: 'center' as const },
  { key: 'itemCount', label: '题数', width: '64px', align: 'center' as const },
  { key: 'participantCount', label: '学习人数', width: '82px', align: 'center' as const },
  { key: 'isOfficial', label: '属性', width: '90px', align: 'center' as const },
  { key: 'status', label: '状态', width: '76px', align: 'center' as const }
]

const categoryMap: Record<string, string> = {
  ALGO: '算法编程',
  DB: '数据库 SQL',
  OBJECTIVE: '标准客观',
  MIXED: '综合题集'
}

const difficultyMap: Record<string, string> = {
  EASY: '入门',
  MEDIUM: '进阶',
  HARD: '挑战',
  MIXED: '综合'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await listAdminProblemSets({
      category: categoryFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size
    })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '请求失败，请稍后重试'
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
watch(categoryFilter, resetAndLoad)
watch(page, load)

async function removeSet(row: ProblemSetSummary) {
  const ok = await confirm({
    title: '删除题集',
    message: `确定删除题集「${row.title}」吗？删除后将清理该题集的分节编排与用户进度聚合记录（底层题库原题与历史提交不受影响）。`,
    confirmText: '确认删除'
  })
  if (!ok) return
  try {
    await deleteAdminProblemSet(row.id)
    toast.success(`已删除题集「${row.title}」`)
    load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="admin-problem-sets container">
    <h1 class="page-title">题集管理</h1>

    <!-- 顶部紧凑单行工具栏：搜索、题型筛选、新建题集靠右 -->
    <div class="toolbar">
      <input
        v-model="keyword"
        class="input search"
        type="text"
        placeholder="搜索题集标题或描述…"
      />
      <select v-model="categoryFilter" class="input filter-select">
        <option value="">全部题型</option>
        <option value="ALGO">算法编程 (ALGO)</option>
        <option value="DB">数据库 SQL (DB)</option>
        <option value="OBJECTIVE">标准客观 (OBJECTIVE)</option>
      </select>
      <button
        class="btn btn-primary btn-create"
        type="button"
        @click="router.push('/admin/problem-sets/new')"
      >
        + 新建题集
      </button>
    </div>

    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

    <DataTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      row-key="id"
      empty-text="暂无题集，点击右上角「新建题集」创建"
    >
      <template #cell-title="{ row }">
        <div class="title-cell">
          <span class="title-text" :title="row.title">{{ row.title }}</span>
          <span v-if="row.description" class="desc-text" :title="row.description">{{ row.description }}</span>
        </div>
      </template>
      <template #cell-category="{ row }">
        <span class="badge cat-badge">
          {{ categoryMap[row.category] || row.category }}
        </span>
      </template>
      <template #cell-difficulty="{ row }">
        <span class="badge" :class="`diff-${(row.difficulty || 'MIXED').toLowerCase()}`">
          {{ difficultyMap[row.difficulty] || row.difficulty }}
        </span>
      </template>
      <template #cell-itemCount="{ row }">
        <span class="mono">{{ row.itemCount ?? 0 }}</span>
      </template>
      <template #cell-participantCount="{ row }">
        <span class="mono">{{ row.participantCount ?? 0 }}</span>
      </template>
      <template #cell-isOfficial="{ row }">
        <span class="badge" :class="row.isOfficial === 1 ? 'badge-official' : 'badge-custom'">
          {{ row.isOfficial === 1 ? '官方精选' : '教师自建' }}
        </span>
      </template>
      <template #cell-status="{ row }">
        <span class="badge" :class="row.status === 1 ? 'status-public' : 'status-private'">
          {{ row.status === 1 ? '公开' : '私有' }}
        </span>
      </template>
      <template #actions="{ row }">
        <div class="action-buttons">
          <button
            class="action-btn"
            type="button"
            title="前台查看"
            @click="router.push(`/problem-sets/${row.id}`)"
          >
            预览
          </button>
          <button
            class="action-btn"
            type="button"
            title="编辑题集与章节题目编排"
            @click="router.push(`/admin/problem-sets/${row.id}/edit`)"
          >
            编辑
          </button>
          <button
            class="action-btn btn-danger"
            type="button"
            title="删除题集"
            @click="removeSet(row)"
          >
            删除
          </button>
        </div>
      </template>
    </DataTable>

    <Pagination v-model:page="page" :size="size" :total="total" />
  </div>
</template>

<style scoped>
.admin-problem-sets {
  padding-top: 16px;
}

.page-title {
  margin: 16px 0 8px;
  font-size: 22px;
  font-weight: 600;
}

/* 顶部紧凑单行工具栏：搜索框、题型选择器，新建按钮靠右对齐 */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0 20px;
  flex-wrap: nowrap;
}

.search {
  width: 280px;
  height: 38px;
  flex-shrink: 0;
}

.filter-select {
  width: 190px;
  height: 38px;
  flex-shrink: 0;
}

.btn-create {
  margin-left: auto;
  height: 38px;
  padding: 0 18px;
  font-size: 14px;
  font-weight: 600;
  flex-shrink: 0;
}

/* 表格布局：100% 宽度固定排版，彻底消除横向滚动条与各单元格多余省略号 */
:deep(.data-table) {
  table-layout: fixed;
  width: 100%;
}

:deep(.data-table th),
:deep(.data-table td) {
  padding: 10px 8px;
  vertical-align: middle;
}

:deep(.data-table-wrap) {
  overflow-x: hidden;
}

:deep(.data-table th:last-child),
:deep(.data-table td:last-child) {
  width: 175px;
  text-align: center;
}

/* 仅在标题列限制单行超出文本省略，不污染其它徽章与按钮列 */
.title-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.title-text {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.desc-text {
  font-size: 12px;
  color: var(--ink-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 徽章基础紧凑化：避免默认 20px 横向 padding 挤压 */
:deep(.badge) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 2px 8px;
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
}

.cat-badge {
  background: #f5f5f5;
  color: #262626;
  border: 1px solid #d4d4d4;
  white-space: nowrap;
}

.diff-easy {
  background: #ecfdf5;
  color: #16a34a;
}

.diff-medium {
  background: #fffbeb;
  color: #d97706;
}

.diff-hard {
  background: #fef2f2;
  color: #dc2626;
}

.diff-mixed {
  background: #f3f4f6;
  color: #4b5563;
}

.badge-official {
  background: #111111;
  color: #ffffff;
}

.badge-custom {
  background: #f5f5f5;
  color: #525252;
}

.status-public {
  background: #ecfdf5;
  color: #16a34a;
}

.status-private {
  background: #f5f5f5;
  color: #737373;
}

/* 紧凑操作按钮 */
.action-buttons {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px 8px;
  font-size: 12px;
  line-height: 1.2;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
  border-radius: var(--radius);
  white-space: nowrap;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.action-btn.btn-danger {
  color: var(--danger);
  border-color: var(--line);
}

.action-btn.btn-danger:hover {
  background: var(--danger-bg);
  border-color: var(--danger);
}

.submit-error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}
</style>
