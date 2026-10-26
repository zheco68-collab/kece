<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { practiceApi } from '@/api/practice'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'
import type { PracticeSummary } from '@/types/practice'
import { copyToClipboard } from '@/utils/clipboard'

const router = useRouter()
const { confirm } = useConfirm()
const toast = useToast()

const keyword = ref('')
const statusFilter = ref('')
const page = ref(1)
const size = 20

const rows = ref<PracticeSummary[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const columns = [
  { key: 'accessCode', label: '房间口令', width: '100px', align: 'center' as const },
  { key: 'title', label: '练习主题与说明' },
  { key: 'questionDist', label: '题目构成', width: '150px' },
  { key: 'memberCount', label: '参练', width: '70px', align: 'center' as const },
  { key: 'durationMinutes', label: '时长', width: '80px', align: 'center' as const },
  { key: 'status', label: '状态', width: '80px', align: 'center' as const },
  { key: 'createdAt', label: '发起时间', width: '150px', align: 'center' as const },
]

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await practiceApi.listAdmin({
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value || undefined,
      page: page.value,
      size,
    })
    rows.value = res.list
    total.value = res.total
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '获取随堂课练列表失败，请稍后重试'
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

function formatTime(iso: string | null): string {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '—'
}

async function copyCode(code?: string | null) {
  if (!code) return
  const ok = await copyToClipboard(code)
  if (ok) {
    toast.success(`口令「${code}」已复制到剪贴板`)
  } else {
    toast.error('复制失败，请手动选取')
  }
}

function openPractice(row: PracticeSummary) {
  if (!row.accessCode) return
  router.push(`/practice/${row.accessCode}`)
}

function openMonitor(row: PracticeSummary) {
  if (!row.accessCode) return
  router.push(`/practice/${row.accessCode}/monitor`)
}

async function endPractice(row: PracticeSummary) {
  if (!row.accessCode) return
  const ok = await confirm({
    title: '提前收卷',
    message: `确定要提前结束「${row.title}」（口令：${row.accessCode}）吗？结束后学生将无法继续提交作答。`,
    confirmText: '立即收卷',
  })
  if (!ok) return
  try {
    await practiceApi.end(row.accessCode)
    toast.success(`课练「${row.title}」已收卷归档`)
    load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '操作失败')
  }
}

async function removePractice(row: PracticeSummary) {
  const ok = await confirm({
    title: '删除随堂课练',
    message: `确定要删除课练「${row.title}」（口令：${row.accessCode || '—'}）吗？该课练的所有题目快照、学生作答与成绩记录将被级联清理且不可恢复。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await practiceApi.remove(row.id)
    toast.success(`课练「${row.title}」已删除`)
    load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="admin admin-practices container">
    <h1 class="page-title">随堂课练管理</h1>

    <!-- 顶部单行工具栏：搜索框、状态选择器、发起课练按钮靠右 -->
    <div class="toolbar">
      <input
        v-model="keyword"
        class="input search"
        type="text"
        placeholder="搜索课练主题或 6 位口令…"
      />
      <select v-model="statusFilter" class="input filter-select">
        <option value="">全部状态</option>
        <option value="ACTIVE">进行中</option>
        <option value="ENDED">已归档</option>
      </select>
      <button class="btn btn-primary btn-create" type="button" @click="router.push('/admin/practices/new')">
        + 发起课堂练习
      </button>
    </div>

    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

    <!-- 数据表格 -->
    <DataTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      row-key="id"
      empty-text="暂无随堂课练"
    >
      <!-- 房间口令 -->
      <template #cell-accessCode="{ row }">
        <span class="access-code-pill mono" title="点击复制口令" @click="copyCode(row.accessCode)">
          {{ row.accessCode }}
        </span>
      </template>

      <!-- 练习主题与说明 -->
      <template #cell-title="{ row }">
        <div class="title-cell">
          <span class="title-text" :title="row.title">{{ row.title }}</span>
          <span v-if="row.description" class="desc-text" :title="row.description">{{ row.description }}</span>
        </div>
      </template>

      <!-- 题目构成 -->
      <template #cell-questionDist="{ row }">
        <div class="dist-tags mono">
          <span v-if="row.objectiveCount" class="tag tag-obj">客观 {{ row.objectiveCount }}</span>
          <span v-if="row.practicalCount" class="tag tag-prac">实操 {{ row.practicalCount }}</span>
          <span class="tag tag-total">共 {{ row.questionCount }} 题</span>
        </div>
      </template>

      <!-- 参练人数 -->
      <template #cell-memberCount="{ row }">
        <span class="mono">{{ row.memberCount }} 人</span>
      </template>

      <!-- 时长 -->
      <template #cell-durationMinutes="{ row }">
        <span class="mono">{{ row.durationMinutes ? `${row.durationMinutes} 分钟` : '不限时' }}</span>
      </template>

      <!-- 状态 Badge -->
      <template #cell-status="{ row }">
        <span class="badge" :class="row.status === 'ACTIVE' ? 'status-active' : 'status-ended'">
          {{ row.status === 'ACTIVE' ? '进行中' : '已归档' }}
        </span>
      </template>

      <!-- 发起时间 -->
      <template #cell-createdAt="{ row }">
        <span class="time">{{ formatTime(row.createdAt) }}</span>
      </template>

      <!-- 操作列 -->
      <template #actions="{ row }">
        <div class="action-buttons">
          <button
            class="action-btn"
            type="button"
            title="查看课练详情与题目作答"
            @click="openPractice(row)"
          >
            进入课练
          </button>
          <button
            class="action-btn btn-projector"
            type="button"
            title="查看课练实时与统计数据看板"
            @click="openMonitor(row)"
          >
            课练数据
          </button>
          <button
            class="action-btn"
            type="button"
            title="复制房间口令"
            @click="copyCode(row.accessCode)"
          >
            复制口令
          </button>
          <button
            v-if="row.status === 'ACTIVE'"
            class="action-btn btn-warning"
            type="button"
            title="提前收卷并截止答题"
            @click="endPractice(row)"
          >
            提前收卷
          </button>
          <button
            class="action-btn btn-danger"
            type="button"
            title="删除随堂课练及相关作答记录"
            @click="removePractice(row)"
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
.admin-practices {
  padding-top: 16px;
}

.page-title {
  margin: 16px 0 8px;
  font-size: 22px;
  font-weight: 600;
}

/* 顶部紧凑单行工具栏 */
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
  width: 130px;
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

.time {
  color: var(--ink-tertiary);
  font-size: 13px;
  white-space: nowrap;
}

.access-code-pill {
  display: inline-block;
  padding: 2px 7px;
  background: var(--ink);
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
  border-radius: var(--radius);
  cursor: pointer;
  transition: opacity 0.15s ease;
}

.access-code-pill:hover {
  opacity: 0.85;
}

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
  max-width: 320px;
}

.desc-text {
  font-size: 12px;
  color: var(--ink-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}

.dist-tags {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  white-space: nowrap;
}

.tag {
  padding: 1px 5px;
  border-radius: var(--radius);
  border: 1px solid var(--line);
  background: var(--surface);
  white-space: nowrap;
}

.tag-obj {
  color: #7048e8;
  border-color: #d0bfff;
  background: #f8f0fc;
}

.tag-prac {
  color: #2b8a3e;
  border-color: #b2f2bb;
  background: #ebfbee;
}

.tag-total {
  color: var(--ink-secondary);
  border-color: var(--line);
}

.status-active {
  background: var(--success-bg, #ecfdf5);
  color: var(--success, #10b981);
}

.status-ended {
  background: var(--bg);
  color: var(--ink-tertiary);
}

/* 紧凑操作按钮栏 */
.action-buttons {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 4px 10px;
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

.action-btn.btn-projector {
  background: var(--button);
  color: #ffffff;
  border-color: var(--button);
  font-weight: 600;
}

.action-btn.btn-projector:hover {
  opacity: 0.88;
}

.action-btn.btn-warning {
  color: #d97706;
  border-color: var(--line);
}

.action-btn.btn-warning:hover {
  background: #fef3c7;
  border-color: #f59e0b;
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
