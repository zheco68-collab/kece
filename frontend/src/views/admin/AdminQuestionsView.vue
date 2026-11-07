<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { questionApi } from '@/api/question'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import QuestionListShell from '@/components/admin/QuestionListShell.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'
import { usePagedQuery } from '@/shared/composables/usePagedQuery'
import { useUserStore } from '@/stores/user'
import type { Difficulty } from '@/types'

type DifficultyFilter = '' | Difficulty
type StatusFilter = '' | '1' | '0'

/** 管理列表行：字段与后端 QuestionVO 对齐，只声明页面用到的列。 */
interface QuestionRow {
  id: number
  title: string
  difficulty: Difficulty
  status: number
  createdBy: number
  createdAt: string
}

const router = useRouter()
const userStore = useUserStore()
const { confirm } = useConfirm()
const toast = useToast()

const keyword = ref('')
const questionIdInput = ref('')
const difficultyFilter = ref<DifficultyFilter>('')
const statusFilter = ref<StatusFilter>('')
const createdByInput = ref('')

const DIFFICULTY_LABELS: Record<Difficulty, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}

const difficultyOptions: Array<{ value: DifficultyFilter; label: string }> = [
  { value: '', label: '全部难度' },
  { value: 'EASY', label: '简单' },
  { value: 'MEDIUM', label: '中等' },
  { value: 'HARD', label: '困难' },
]

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: '', label: '全部状态' },
  { value: '1', label: '公开' },
  { value: '0', label: '私有' },
]

const columns = [
  { key: 'id', label: 'ID', width: '64px' },
  { key: 'title', label: '标题' },
  { key: 'difficulty', label: '难度', width: '90px' },
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
} = usePagedQuery<QuestionRow>({
  defaultSize: 20,
  immediate: true,
  fetcher: async ({ page, size }) => {
    const questionIdRaw = questionIdInput.value.trim()
    const questionId = questionIdRaw === '' ? undefined : Number(questionIdRaw)
    if (questionIdRaw !== '' && (!/^[1-9]\d*$/.test(questionIdRaw) || !Number.isSafeInteger(questionId))) {
      throw new Error('请输入有效的题目 ID')
    }
    const createdByRaw = createdByInput.value.trim()
    const createdByNum = createdByRaw === '' ? NaN : Number(createdByRaw)
    return adminApi.listQuestions({
      id: questionId,
      keyword: keyword.value.trim() || undefined,
      difficulty: difficultyFilter.value || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
      createdBy: Number.isFinite(createdByNum) ? createdByNum : undefined,
      page,
      size,
    })
  },
})

// 关键词与创建人输入防抖 300ms，下拉筛选变化直接重置并加载
let filterTimer: ReturnType<typeof setTimeout> | null = null

watch([keyword, questionIdInput, createdByInput], () => {
  if (filterTimer !== null) clearTimeout(filterTimer)
  filterTimer = setTimeout(reset, 300)
})

watch([difficultyFilter, statusFilter], reset)

function clearFilters() {
  keyword.value = ''
  questionIdInput.value = ''
  difficultyFilter.value = ''
  statusFilter.value = ''
  createdByInput.value = ''
}

/** 创建时间：ISO 串截取前 19 位并把 T 换成空格，得到 "YYYY-MM-DD HH:mm:ss"。 */
function formatTime(iso: string): string {
  return iso ? iso.slice(0, 19).replace('T', ' ') : '—'
}

function goEdit(row: QuestionRow) {
  router.push(`/admin/questions/${row.id}/edit`)
}

async function removeQuestion(row: QuestionRow) {
  const ok = await confirm({
    title: '删除编程题',
    message: `确定删除题目「${row.title}」吗？有提交记录的题目仅管理员可强制删除，其提交与评测记录将被级联清理。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await questionApi.remove(row.id)
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
  <QuestionListShell title="编程题管理" description="管理算法题目、公开状态与评测数据。" :total="total" @reset="clearFilters">
    <template #action>
      <RouterLink class="btn btn-primary" to="/admin/questions/new">新建编程题</RouterLink>
    </template>
    <template #filters>
      <label class="filter-field"><span>题目标题</span><input v-model="keyword" class="input keyword-input" type="search" placeholder="输入标题关键词" /></label>
      <label class="filter-field"><span>题目 ID</span><input
        v-model="questionIdInput"
        class="input question-id-input"
        type="search"
        inputmode="numeric"
        aria-label="题目 ID"
        placeholder="题目 ID"
      /></label>
      <label class="filter-field">
        <span class="filter-label">难度</span>
        <select v-model="difficultyFilter" class="input">
          <option v-for="d in difficultyOptions" :key="d.value || 'all'" :value="d.value">
            {{ d.label }}
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
      <template #cell-difficulty="{ row }">
        <span class="badge" :class="`difficulty-${row.difficulty.toLowerCase()}`">
          {{ DIFFICULTY_LABELS[row.difficulty as Difficulty] ?? row.difficulty }}
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
.question-id-input { width: 140px; max-width: 100%; }
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
