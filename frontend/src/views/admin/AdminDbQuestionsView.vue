<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { databaseApi } from '@/api/database'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import QuestionListShell from '@/components/admin/QuestionListShell.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'
import { usePagedQuery } from '@/shared/composables/usePagedQuery'
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

const {
  page,
  size,
  list: rows,
  total,
  loading,
  error: loadError,
  load,
  reset,
} = usePagedQuery<DbQuestionRow>({
  defaultSize: 20,
  immediate: true,
  fetcher: async ({ page, size }) => {
    const createdByRaw = createdByInput.value.trim()
    const createdByNum = createdByRaw === '' ? NaN : Number(createdByRaw)
    return adminApi.listDbQuestions({
      type: typeFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
      createdBy: Number.isFinite(createdByNum) ? createdByNum : undefined,
      page,
      size,
    })
  },
})

// 关键词与创建人输入防抖 300ms，下拉筛选变化直接重置并加载
let filterTimer: ReturnType<typeof setTimeout> | null = null

watch([keyword, createdByInput], () => {
  if (filterTimer !== null) clearTimeout(filterTimer)
  filterTimer = setTimeout(reset, 300)
})

watch([typeFilter, statusFilter], reset)

function clearFilters() {
  keyword.value = ''
  typeFilter.value = ''
  statusFilter.value = ''
  createdByInput.value = ''
}

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

onBeforeUnmount(() => {
  if (filterTimer !== null) clearTimeout(filterTimer)
})
</script>

<template>
  <QuestionListShell title="数据库题管理" description="管理 SQL 题目、题型与公开状态。" :total="total" @reset="clearFilters">
    <template #action>
      <RouterLink class="btn btn-primary" to="/admin/db-questions/new">新建数据库题</RouterLink>
    </template>
    <template #filters>
      <label class="filter-field"><span>题目标题</span><input v-model="keyword" class="input keyword-input" type="search" placeholder="输入标题关键词" /></label>
      <label class="filter-field">
        <span class="filter-label">题型</span>
        <select v-model="typeFilter" class="input">
          <option v-for="t in typeOptions" :key="t.value || 'all'" :value="t.value">
            {{ t.label }}
          </option>
        </select>
      </label>
      <label class="filter-field">
        <span class="filter-label">状态</span>
        <select v-model="statusFilter" class="input">
          <option v-for="s in statusOptions" :key="s.value || 'all'" :value="s.value">
            {{ s.label }}
          </option>
        </select>
      </label>
      <label v-if="userStore.hasRole('ADMIN')" class="filter-field">
        <span class="filter-label">出题人</span>
        <input
          v-model="createdByInput"
          class="input creator-input"
          type="search"
          placeholder="创建人 ID"
        />
      </label>
    </template>

    <p v-if="loadError" class="submit-error" role="alert">{{ loadError }} <button class="retry-button" type="button" @click="load()">重试</button></p>

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

    <Pagination :page="page" :size="size" :total="total" @update:page="load" />
  </QuestionListShell>
</template>

<style scoped>
.keyword-input { width: 240px; max-width: 100%; }
.creator-input { width: 160px; max-width: 100%; }
.filter-field select { min-width: 150px; }
.filter-label { color: #555; font-size: 14px; }
.submit-error { margin: 16px 24px; color: var(--danger); }
.retry-button { color: inherit; background: none; border: 0; padding: 4px 8px; text-decoration: underline; cursor: pointer; }
.status-enabled { background: var(--success-bg); color: var(--success); }
.status-disabled { background: var(--danger-bg); color: var(--danger); }
.time { color: #555; font-size: 13px; }
.row-actions { display: flex; align-items: center; gap: 8px; }
.btn-danger { color: var(--danger); }
</style>
