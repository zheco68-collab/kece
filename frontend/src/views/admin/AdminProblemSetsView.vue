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
  { key: 'id', label: 'ID', width: '64px' },
  { key: 'title', label: '题集标题' },
  { key: 'category', label: '分类', width: '100px' },
  { key: 'difficulty', label: '难度', width: '90px' },
  { key: 'itemCount', label: '题目数', width: '80px' },
  { key: 'participantCount', label: '参与人数', width: '90px' },
  { key: 'isOfficial', label: '属性', width: '80px' },
  { key: 'status', label: '可见性', width: '80px' }
]

const categoryMap: Record<string, string> = {
  ALGO: '算法编程题集',
  DB: '数据库 SQL 题集',
  OBJECTIVE: '标准客观题集'
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

    <div class="toolbar">
      <input
        v-model="keyword"
        class="input search"
        type="text"
        placeholder="搜索题集标题或描述…"
      />
      <select v-model="categoryFilter" class="input">
        <option value="">全部分类</option>
        <option value="ALGO">算法编程题集 (ALGO)</option>
        <option value="DB">数据库 SQL 题集 (DB)</option>
        <option value="OBJECTIVE">标准客观题集 (OBJECTIVE)</option>
      </select>
      <button
        class="btn btn-primary"
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
      <template #cell-isOfficial="{ row }">
        <span class="badge" :class="row.isOfficial === 1 ? 'badge-official' : 'badge-custom'">
          {{ row.isOfficial === 1 ? '官方精选' : '教师自建' }}
        </span>
      </template>
      <template #cell-status="{ row }">
        <span class="badge" :class="row.status === 1 ? 'status-public' : 'status-private'">
          {{ row.status === 1 ? '公开' : '隐藏' }}
        </span>
      </template>
      <template #actions="{ row }">
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          @click="router.push(`/problem-sets/${row.id}`)"
        >
          前台预览
        </button>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          @click="router.push(`/admin/problem-sets/${row.id}/edit`)"
        >
          编辑章节与题目
        </button>
        <button
          class="btn btn-ghost btn-sm btn-danger"
          type="button"
          @click="removeSet(row)"
        >
          删除
        </button>
      </template>
    </DataTable>

    <Pagination v-model:page="page" :size="size" :total="total" />
  </div>
</template>

<style scoped>
.admin-problem-sets {
  padding-top: 32px;
}

.page-title {
  margin: 28px 0 8px;
  font-size: 22px;
  font-weight: 600;
}

.toolbar {
  display: flex;
  gap: 10px;
  margin: 16px 0;
  flex-wrap: wrap;
}

.search {
  width: 260px;
}

.cat-badge {
  background: #f5f5f5;
  color: #262626;
  border: 1px solid #d4d4d4;
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

.btn-danger {
  color: var(--danger);
}
</style>
