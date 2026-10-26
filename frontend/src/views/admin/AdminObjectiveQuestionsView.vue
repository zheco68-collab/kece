<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { objectiveApi } from '@/api/objective'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'
import type { ObjectiveType } from '@/types'

type TypeFilter = '' | ObjectiveType
type StatusFilter = '' | '1' | '0'

/** 管理列表行：字段与后端 ObjectiveQuestionVO 对齐，只声明页面用到的列。 */
interface ObjectiveRow {
  id: number
  type: ObjectiveType
  content: string
  score: number
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

const rows = ref<ObjectiveRow[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const TYPE_LABELS: Record<ObjectiveType, string> = {
  SINGLE: '单选',
  MULTIPLE: '多选',
  JUDGE: '判断',
  FILL: '填空',
}

const typeOptions: Array<{ value: TypeFilter; label: string }> = [
  { value: '', label: '全部客观题' },
  { value: 'SINGLE', label: '单选题' },
  { value: 'JUDGE', label: '判断题' },
  { value: 'MULTIPLE', label: '多选题' },
  { value: 'FILL', label: '填空题' },
]

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: '', label: '全部状态' },
  { value: '1', label: '公开' },
  { value: '0', label: '私有' },
]

const columns = [
  { key: 'id', label: 'ID', width: '64px' },
  { key: 'content', label: '题干' },
  { key: 'type', label: '题型', width: '90px' },
  { key: 'score', label: '分值', width: '70px' },
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
    const res = await adminApi.listObjectiveQuestions({
      keyword: keyword.value.trim() || undefined,
      type: typeFilter.value || undefined,
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

// 关键词与创建人输入防抖 300ms；下拉筛选变化直接触发。
let filterTimer: ReturnType<typeof setTimeout> | null = null

watch([keyword, createdByInput], () => {
  if (filterTimer !== null) clearTimeout(filterTimer)
  filterTimer = setTimeout(resetAndLoad, 300)
})

watch([typeFilter, statusFilter], resetAndLoad)

watch(page, () => {
  load()
})

/** 创建时间：ISO 串截取前 19 位并把 T 换成空格。 */
function formatTime(iso: string): string {
  return iso ? iso.slice(0, 19).replace('T', ' ') : '—'
}

function goEdit(row: ObjectiveRow) {
  router.push(`/admin/objective-questions/${row.id}/edit`)
}

async function removeQuestion(row: ObjectiveRow) {
  const ok = await confirm({
    title: '删除客观题',
    message: `确定删除该客观题吗？有提交记录的题目仅管理员可强制删除，其提交记录将被级联清理。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await objectiveApi.remove(row.id)
    toast.success('已删除该客观题')
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
    <h1 class="page-title">客观题管理</h1>

    <!-- 题型独立分类切换栏（单选题、判断题、多选题、填空题） -->
    <div class="type-segment-tabs card">
      <button
        v-for="t in typeOptions"
        :key="t.value || 'all'"
        class="type-tab-btn"
        :class="{ active: typeFilter === t.value }"
        type="button"
        @click="typeFilter = t.value"
      >
        <span class="tab-text">{{ t.label }}</span>
        <span v-if="t.value" class="tab-badge" :class="`badge-${t.value.toLowerCase()}`">
          {{ t.value === 'SINGLE' ? '单选' : t.value === 'JUDGE' ? '判断' : t.value === 'MULTIPLE' ? '多选' : '填空' }}
        </span>
      </button>
    </div>

    <div class="filter-bar card">
      <input v-model="keyword" class="input keyword-input" type="search" placeholder="题干关键词" />
      <label class="filter">
        <span class="filter-label">题型</span>
        <select v-model="typeFilter" class="input">
          <option v-for="t in typeOptions" :key="t.value || 'all'" :value="t.value">
            {{ t.label }}
          </option>
        </select>
      </label>
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
      <RouterLink
        class="btn btn-primary btn-sm new-btn"
        :to="typeFilter ? `/admin/objective-questions/new?type=${typeFilter}` : '/admin/objective-questions/new'"
      >
        + 新建{{ typeFilter ? TYPE_LABELS[typeFilter as ObjectiveType] + '题' : '客观题' }}
      </RouterLink>
    </div>

    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

    <DataTable :columns="columns" :rows="rows" :loading="loading">
      <template #cell-content="{ row }">
        <span class="content-cell" :title="row.content">{{ row.content }}</span>
      </template>
      <template #cell-type="{ row }">
        <span class="badge" :class="`type-${row.type.toLowerCase()}`">
          {{ TYPE_LABELS[row.type as ObjectiveType] ?? row.type }}
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
.content-cell {
  display: block;
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 题型徽章沿用 style.css 既有灰度分级。 */
.type-single {
  background: #f0f0f0;
  color: var(--ink-secondary);
}
.type-multiple {
  background: #e2e2e2;
  color: var(--ink);
}
.type-fill {
  background: var(--button);
  color: #fff;
}
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
.btn-danger {
  color: var(--danger);
}

/* 分类切换分段栏 (KECE 极简风格) */
.type-segment-tabs {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  overflow-x: auto;
}

.type-tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  font-size: 13px;
  font-weight: 700;
  background: transparent;
  border: 1px solid transparent;
  color: #495057;
  cursor: pointer;
  white-space: nowrap;
}

.type-tab-btn:hover {
  background: #f1f3f5;
  color: #1e1e1e;
}

.type-tab-btn.active {
  background: #1e1e1e;
  border-color: #1e1e1e;
  color: #fff;
}

.tab-badge {
  font-size: 10px;
  padding: 1px 5px;
  font-weight: 700;
}

.tab-badge.badge-single {
  background: #e7f5ff;
  color: #1971c2;
}

.tab-badge.badge-judge {
  background: #ebfbee;
  color: #2b8a3e;
}

.tab-badge.badge-multiple {
  background: #f3f0ff;
  color: #7950f2;
}

.tab-badge.badge-fill {
  background: #fff9db;
  color: #f59f00;
}

.type-tab-btn.active .tab-badge {
  background: rgba(255, 255, 255, 0.25);
  color: #fff;
}

.type-judge {
  background: #ebfbee;
  color: #2b8a3e;
}
</style>
