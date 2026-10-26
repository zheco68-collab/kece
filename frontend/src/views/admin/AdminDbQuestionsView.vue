<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { databaseApi } from '@/api/database'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'
import type { DbQuestionType } from '@/types'

type TypeFilter = '' | DbQuestionType
type StatusFilter = '' | '1' | '0'

/** 管理列表行：字段与后端 DbQuestionVO 对齐，只声明页面用到的列。 */
interface DbQuestionRow {
  id: number
  title: string
  type: DbQuestionType
  status: number
  createdBy: number
  createdAt: string
}

const router = useRouter()
const userStore = useUserStore()
const { confirm } = useConfirm()
const toast = useToast()

const keyword = ref('')
const typeFilter = ref<TypeFilter>('')
const statusFilter = ref<StatusFilter>('')
const createdByInput = ref('')
const page = ref(1)
const size = 20

const rows = ref<DbQuestionRow[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const TYPE_LABELS: Record<DbQuestionType, string> = {
  SELECT: '查询',
  DML: '变更',
}

const typeOptions: Array<{ value: TypeFilter; label: string }> = [
  { value: '', label: '全部题型' },
  { value: 'SELECT', label: '查询' },
  { value: 'DML', label: '变更' },
]

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: '', label: '全部状态' },
  { value: '1', label: '公开' },
  { value: '0', label: '私有' },
]

const columns = [
  { key: 'id', label: 'ID', width: '64px' },
  { key: 'title', label: '标题' },
  { key: 'type', label: '题型', width: '90px' },
  { key: 'status', label: '状态', width: '90px' },
  { key: 'createdBy', label: '出题人', width: '100px' },
  { key: 'createdAt', label: '创建时间', width: '170px' },
]

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const createdByRaw = createdByInput.value.trim()
    const createdByNum = createdByRaw === '' ? NaN : Number(createdByRaw)
    const res = await adminApi.listDbQuestions({
      type: typeFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
      createdBy: Number.isFinite(createdByNum) ? createdByNum : undefined,
      page: page.value,
      size,
    })
    rows.value = res.list
    total.value = res.total
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '请求失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 筛选变化时回到第一页再加载；已在第一页则手动触发。 */
function resetAndLoad() {
  if (page.value === 1) {
    load()
  } else {
    page.value = 1
  }
}

// 关键词与创建人输入防抖 300ms，避免每次击键都发请求；下拉筛选变化直接触发。
let filterTimer: ReturnType<typeof setTimeout> | null = null

watch([keyword, createdByInput], () => {
  if (filterTimer !== null) clearTimeout(filterTimer)
  filterTimer = setTimeout(resetAndLoad, 300)
})

watch([typeFilter, statusFilter], resetAndLoad)

watch(page, () => {
  load()
})

/** 创建时间：ISO 串截取前 19 位并把 T 换成空格，得到 "YYYY-MM-DD HH:mm:ss"。 */
function formatTime(iso: string): string {
  return iso ? iso.slice(0, 19).replace('T', ' ') : '—'
}

function goEdit(row: DbQuestionRow) {
  router.push(`/admin/db-questions/${row.id}/edit`)
}

async function removeQuestion(row: DbQuestionRow) {
  const ok = await confirm({
    title: '删除数据库题',
    message: `确定删除题目「${row.title}」吗？有提交记录的题目仅管理员可强制删除，其提交与评测记录将被级联清理。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await databaseApi.remove(row.id)
    toast.success(`已删除「${row.title}」`)
    load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

onMounted(() => {
  load()
})

onBeforeUnmount(() => {
  if (filterTimer !== null) clearTimeout(filterTimer)
})
</script>

<template>
  <div class="admin container">
    <h1 class="page-title">数据库题管理</h1>

    <div class="filter-bar card">
      <label class="filter">
        <span class="filter-label">题型</span>
        <select v-model="typeFilter" class="input">
          <option v-for="t in typeOptions" :key="t.value || 'all'" :value="t.value">
            {{ t.label }}
          </option>
        </select>
      </label>
      <input v-model="keyword" class="input keyword-input" type="search" placeholder="标题关键词" />
      <label class="filter">
        <span class="filter-label">状态</span>
        <select v-model="statusFilter" class="input">
          <option v-for="s in statusOptions" :key="s.value || 'all'" :value="s.value">
            {{ s.label }}
          </option>
        </select>
      </label>
      <label v-if="userStore.hasRole('ADMIN')" class="filter">
        <span class="filter-label">出题人</span>
        <input
          v-model="createdByInput"
          class="input creator-input"
          type="search"
          placeholder="创建人 ID"
        />
      </label>
      <RouterLink class="btn btn-primary btn-sm new-btn" to="/admin/db-questions/new">
        新建
      </RouterLink>
    </div>

    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

    <DataTable :columns="columns" :rows="rows" :loading="loading">
      <template #cell-type="{ row }">
        <span class="badge" :class="`type-${row.type.toLowerCase()}`">
          {{ TYPE_LABELS[row.type as DbQuestionType] ?? row.type }}
        </span>
      </template>
      <template #cell-status="{ row }">
        <span class="badge" :class="row.status === 1 ? 'status-enabled' : 'status-disabled'">
          {{ row.status === 1 ? '公开' : '私有' }}
        </span>
      </template>
      <template #cell-createdBy="{ row }">
        <span class="muted">#{{ row.createdBy }}</span>
      </template>
      <template #cell-createdAt="{ row }">
        <span class="time">{{ formatTime(row.createdAt) }}</span>
      </template>
      <template #actions="{ row }">
        <div class="row-actions">
          <button class="btn btn-ghost btn-sm" type="button" @click="goEdit(row)">编辑</button>
          <button class="btn btn-ghost btn-sm btn-danger" type="button" @click="removeQuestion(row)">
            删除
          </button>
        </div>
      </template>
    </DataTable>

    <Pagination v-model:page="page" :size="size" :total="total" />
  </div>
</template>

<style scoped>
.admin {
  padding-top: 32px;
}
.page-title {
  margin: 28px 0 16px;
  font-size: 22px;
  font-weight: 600;
}
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
  padding: 16px;
  margin-bottom: 24px;
}
.keyword-input {
  max-width: 280px;
}
.filter {
  display: flex;
  align-items: center;
  gap: 10px;
}
.filter-label {
  color: var(--ink-secondary);
  font-size: 14px;
  white-space: nowrap;
}
.filter select {
  width: 120px;
}
.creator-input {
  width: 140px;
}
.new-btn {
  margin-left: auto;
}
.submit-error {
  margin: 0 0 16px;
}
/* 状态徽章配色与 AdminUsersView 的启用/禁用徽章保持一致。 */
.status-enabled {
  background: var(--success-bg);
  color: var(--success);
}
.status-disabled {
  background: var(--danger-bg);
  color: var(--danger);
}
.time {
  color: var(--ink-tertiary);
  font-size: 13px;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.btn-danger {
  color: var(--danger);
}
</style>
