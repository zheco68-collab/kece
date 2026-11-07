<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
const statusFilter = ref<'' | 'ACTIVE' | 'ENDED'>('')
const page = ref(1)
const size = 20

const rows = ref<PracticeSummary[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

const columns = [
  { key: 'accessCode', label: '房间口令', width: '110px', align: 'center' as const },
  { key: 'title', label: '课练主题与说明' },
  { key: 'questionDist', label: '题目构成', width: '180px' },
  { key: 'memberCount', label: '参练', width: '75px', align: 'center' as const },
  { key: 'durationMinutes', label: '时长', width: '85px', align: 'center' as const },
  { key: 'status', label: '状态', width: '85px', align: 'center' as const },
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
  keywordTimer = setTimeout(resetAndLoad, 260)
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

function openOverview(row: PracticeSummary) {
  router.push(`/admin/practices/${row.id}/overview`)
}

function editPractice(row: PracticeSummary) {
  router.push(`/admin/practices/${row.id}/edit`)
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
    toast.success(`课练「${row.title}」已收卷结算`)
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
onBeforeUnmount(() => {
  if (keywordTimer !== null) clearTimeout(keywordTimer)
})
</script>

<template>
  <div class="admin admin-practices container">
    <div class="page-head">
      <div class="head-left">
        <h1 class="page-title">随堂课练管理</h1>
        <p class="muted head-sub">发起课堂练习，查看作答进度与成绩。</p>
      </div>
      <button
        class="btn btn-primary btn-create"
        type="button"
        @click="router.push('/admin/practices/new')"
      >
        + 发起课堂练习
      </button>
    </div>

    <!-- 单行综合工具栏 -->
    <div class="toolbar card">
      <div class="toolbar-left">
        <div class="search-wrap">
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor">
            <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z" />
          </svg>
          <input
            v-model="keyword"
            class="input search-input"
            type="search"
            placeholder="搜索课练主题或 6 位房间口令…"
          />
        </div>

        <div class="filter-tabs">
          <button
            type="button"
            class="tab-btn"
            :class="{ active: statusFilter === '' }"
            @click="statusFilter = ''"
          >
            全部状态
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: statusFilter === 'ACTIVE' }"
            @click="statusFilter = 'ACTIVE'"
          >
            进行中
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: statusFilter === 'ENDED' }"
            @click="statusFilter = 'ENDED'"
          >
            已结束
          </button>
        </div>
      </div>

      <button
        type="button"
        class="btn btn-ghost btn-sm"
        :disabled="loading"
        @click="load"
      >
        {{ loading ? '刷新中…' : '↻ 刷新' }}
      </button>
    </div>

    <div v-if="loadError" class="submit-error" role="alert">{{ loadError }} <button type="button" class="retry-button" @click="load">重试</button></div>

    <div class="list-heading"><h2>课练列表</h2><span>共 {{ total }} 场课练</span></div>

    <DataTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      row-key="id"
      empty-text="暂无随堂课练"
    >
      <!-- 房间口令 -->
      <template #cell-accessCode="{ row }">
        <button
          class="access-code-pill mono"
          :class="{ 'is-ended': row.status === 'ENDED' }"
          type="button"
          title="点击复制口令"
          @click="copyCode(row.accessCode)"
        >
          {{ row.accessCode }} ⧉
        </button>
      </template>

      <!-- 课练主题与说明 -->
      <template #cell-title="{ row }">
        <div class="title-cell">
          <button class="title-text" type="button" :title="row.title" @click="openOverview(row)">{{ row.title }}</button>
          <span v-if="row.description" class="desc-text" :title="row.description">{{ row.description }}</span>
        </div>
      </template>

      <!-- 题目构成（0-Radius 黑白灰工业风标签） -->
      <template #cell-questionDist="{ row }">
        <div class="dist-tags mono">
          <span v-if="row.objectiveCount" class="tag tag-type">客观 {{ row.objectiveCount }}</span>
          <span v-if="row.practicalCount" class="tag tag-type">实操 {{ row.practicalCount }}</span>
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

      <!-- 状态 -->
      <template #cell-status="{ row }">
        <span class="state-pill" :class="row.status === 'ACTIVE' ? 'status-active' : 'status-ended'">
          {{ row.status === 'ACTIVE' ? '● 进行中' : '已结束' }}
        </span>
      </template>

      <!-- 发起时间 -->
      <template #cell-createdAt="{ row }">
        <span class="time mono">{{ formatTime(row.createdAt) }}</span>
      </template>

      <!-- 操作列 -->
      <template #actions="{ row }">
        <div class="action-buttons">
          <button
            class="action-btn btn-overview"
            type="button"
            title="查看实时考务大盘与代码快照审计"
            @click="openOverview(row)"
          >
            考务大盘
          </button>
          <button
            class="action-btn"
            type="button"
            title="编辑课练配置与题目"
            @click="editPractice(row)"
          >
            编辑
          </button>
          <button
            class="action-btn"
            type="button"
            title="以学生视角进入课练内页"
            @click="openPractice(row)"
          >
            进入视角
          </button>
          <button
            v-if="row.status === 'ACTIVE'"
            class="action-btn btn-warning"
            type="button"
            title="提前收卷并截止答题"
            @click="endPractice(row)"
          >
            收卷
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
  padding-top: 28px;
  padding-bottom: 64px;
}

.mono {
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.head-sub {
  margin: 0;
  font-size: 13.5px;
}
.list-heading { display:flex; align-items:baseline; justify-content:space-between; gap:12px; margin:0 0 12px; }
.list-heading h2 { margin:0; font-size:16px; }
.list-heading span { color:var(--ink-secondary); font-size:13px; }
.retry-button { border:0; background:none; color:inherit; cursor:pointer; text-decoration:underline; }

.btn-create {
  height: 36px;
  padding: 0 18px;
  font-size: 13.5px;
  font-weight: 600;
  border-radius: 0;
}

/* 顶部综合筛选工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  margin-bottom: 18px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
  flex-wrap: wrap;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  flex: 1;
}
.search-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 260px;
}
.search-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: var(--ink-secondary);
  pointer-events: none;
}
.search-input {
  width: 100%;
  height: 34px;
  padding-left: 32px;
  font-size: 13px;
  border-radius: 0;
}
.filter-tabs {
  display: flex;
  gap: 6px;
}
.tab-btn {
  padding: 5px 12px;
  font-size: 12.5px;
  font-weight: 500;
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}
.tab-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}
.tab-btn.active {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
  font-weight: 600;
}

.time {
  color: var(--ink-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.access-code-pill {
  display: inline-block;
  padding: 2px 8px;
  background: var(--ink);
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.8px;
  border-radius: 0;
  cursor: pointer;
  border: 0;
  transition: opacity 0.15s ease;
}
.access-code-pill.is-ended {
  background: #52525b;
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
  border: 0;
  padding: 0;
  background: none;
  text-align: left;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 340px;
  cursor: pointer;
}
.title-text:hover {
  text-decoration: underline;
}
.desc-text {
  font-size: 12px;
  color: var(--ink-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 340px;
}

.dist-tags {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  white-space: nowrap;
}
.tag {
  padding: 1px 6px;
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--bg);
  white-space: nowrap;
}
.tag-type {
  background: #f4f4f5;
  border-color: #e4e4e7;
  color: #27272a;
  font-weight: 600;
}
.tag-total {
  color: var(--ink-secondary);
}

.state-pill {
  display: inline-block;
  padding: 2px 8px;
  font-size: 11.5px;
  font-weight: 600;
  border: 1px solid var(--line);
  border-radius: 0;
  white-space: nowrap;
}
.status-active {
  background: #ebfbee;
  color: #2b8a3e;
  border-color: #b2f2bb;
}
.status-ended {
  background: var(--bg);
  color: var(--ink-tertiary);
}

/* 紧凑操作按钮栏 */
.action-buttons {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}
.action-btn {
  padding: 4px 9px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}
.action-btn:hover {
  border-color: var(--ink);
  background: var(--bg);
}
.btn-overview {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}
.btn-overview:hover {
  background: #27272a;
  color: #ffffff;
}
.btn-warning {
  color: #d97706;
  border-color: rgba(217, 119, 6, 0.35);
}
.btn-warning:hover {
  background: #fffbeb;
  border-color: #d97706;
}
.btn-danger {
  color: var(--danger);
  border-color: rgba(220, 38, 38, 0.3);
}
.btn-danger:hover {
  background: var(--danger-bg);
  border-color: var(--danger);
}
.submit-error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}
</style>
