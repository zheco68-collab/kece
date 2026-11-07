<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { examApi, type Exam } from '@/api/exam';
import { ApiError } from '@/api/request';
import DataTable from '@/components/DataTable.vue';
import Pagination from '@/components/Pagination.vue';
import { useConfirm } from '@/composables/useConfirm';
import { useToast } from '@/composables/useToast';

const router = useRouter();
const { confirm } = useConfirm();
const toast = useToast();

const keyword = ref('');
const statusFilter = ref('');
const page = ref(1);
const size = 20;

const rows = ref<Exam[]>([]);
const total = ref(0);
const loading = ref(false);
const loadError = ref('');

const columns = [
  { key: 'id', label: 'ID', width: '64px' },
  { key: 'title', label: '试卷' },
  { key: 'sessionStatus', label: '考务状态', width: '90px' },
  { key: 'mode', label: '赛制', width: '70px' },
  { key: 'status', label: '可见性', width: '70px' },
  { key: 'durationMinutes', label: '时长(分)', width: '85px' },
  { key: 'startTime', label: '开考窗口', width: '150px' },
  { key: 'endTime', label: '最晚开考', width: '150px' },
];

function sessionStatusLabel(status: string | null): string {
  if (status === 'ENDED') return '已结束';
  if (status === 'ONGOING') return '进行中';
  if (status === 'CANCELLED') return '已取消';
  return '未开考';
}

function sessionStatusClass(status: string | null): string {
  if (status === 'ENDED') return 'status-ended';
  if (status === 'ONGOING') return 'status-ongoing';
  if (status === 'CANCELLED') return 'status-cancelled';
  return 'status-upcoming';
}

async function exportBlankDoc(examId: number) {
  try {
    await examApi.downloadWord(examId);
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '导出空白试卷失败，请重试');
  }
}

const exportingBatchExamId = ref<number | null>(null);

async function handleExportBatchDoc(examId: number, examTitle: string) {
  exportingBatchExamId.value = examId;
  toast.info(`正在打包考试「${examTitle}」的所有学生试卷，请稍候…`);
  try {
    await examApi.downloadBatchWord(examId);
    toast.success(`考试「${examTitle}」学生试卷导出成功！`);
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : '批量导出试卷失败，请稍后重试');
  } finally {
    exportingBatchExamId.value = null;
  }
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const res = await examApi.listAdmin({
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
      page: page.value,
      size,
    });
    rows.value = res.list;
    total.value = res.total;
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '请求失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

function resetAndLoad() {
  if (page.value === 1) {
    load();
  } else {
    page.value = 1;
  }
}

let keywordTimer: ReturnType<typeof setTimeout> | null = null;
watch(keyword, () => {
  if (keywordTimer !== null) clearTimeout(keywordTimer);
  keywordTimer = setTimeout(resetAndLoad, 300);
});
watch(statusFilter, resetAndLoad);
watch(page, () => {
  load();
});

function formatTime(iso: string | null): string {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '—';
}

async function removeExam(row: Exam) {
  const ok = await confirm({
    title: '删除试卷',
    message: `确定删除考试「${row.title}」吗？有作答记录的考试仅管理员可强制删除，其作答与评测数据将被级联清理，出题人无法删除。`,
    confirmText: '删除',
  });
  if (!ok) return;
  try {
    await examApi.remove(row.id);
    toast.success(`已删除「${row.title}」`);
    load();
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '删除失败');
  }
}

onMounted(load);
onBeforeUnmount(() => {
  if (keywordTimer !== null) clearTimeout(keywordTimer);
});
</script>

<template>
  <div class="admin-exams container">
    <header class="page-header">
      <div>
        <h1 class="page-title">考试管理</h1>
        <p class="page-description">创建考试，管理试卷并查看考生成绩。</p>
      </div>
      <button class="btn btn-primary create-button" type="button" @click="router.push('/admin/exams/new')">
        新建试卷
      </button>
    </header>

    <div class="toolbar" aria-label="筛选考试">
      <label class="filter-field search-field"><span>试卷标题</span><input v-model="keyword" class="input search" type="search" placeholder="输入试卷标题" /></label>
      <label class="filter-field"><span>可见状态</span><select v-model="statusFilter" class="input">
        <option value="">全部状态</option>
        <option value="1">公开</option>
        <option value="0">私有</option>
      </select></label>
      <button class="btn btn-ghost reset-button" type="button" @click="keyword = ''; statusFilter = ''">重置筛选</button>
    </div>

    <div v-if="loadError" class="submit-error" role="alert">{{ loadError }} <button type="button" class="retry-button" @click="load">重试</button></div>

    <div class="list-heading"><h2>考试列表</h2><span>共 {{ total }} 场考试</span></div>

    <DataTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      row-key="id"
      empty-text="暂无试卷"
    >
      <template #cell-sessionStatus="{ row }">
        <span class="badge" :class="sessionStatusClass(row.sessionStatus)">
          {{ sessionStatusLabel(row.sessionStatus) }}
        </span>
      </template>
      <template #cell-mode="{ row }">
        <span class="badge" :class="row.mode === 'ACM' ? 'mode-acm' : 'mode-oi'">
          {{ row.mode === 'ACM' ? 'ACM' : 'OI' }}
        </span>
      </template>
      <template #cell-status="{ row }">
        <span class="badge" :class="row.status === 1 ? 'status-public' : 'status-private'">
          {{ row.status === 1 ? '公开' : '私有' }}
        </span>
      </template>
      <template #cell-startTime="{ row }">
        <span class="time">{{ formatTime(row.startTime) }}</span>
      </template>
      <template #cell-endTime="{ row }">
        <span class="time">{{ formatTime(row.endTime) }}</span>
      </template>
      <template #actions="{ row }">
        <button
          class="btn btn-ghost btn-sm btn-highlight"
          type="button"
          @click="router.push(`/admin/exams/${row.id}/papers`)"
        >
          成绩与查卷
        </button>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="exportingBatchExamId === row.id"
          title="批量打包导出本场所有考生的试卷 Word (.doc) 压缩包"
          @click="handleExportBatchDoc(row.id, row.title)"
        >
          {{ exportingBatchExamId === row.id ? '打包中…' : '批量导出答卷' }}
        </button>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          title="导出标准 A 卷 Word 文档"
          @click="exportBlankDoc(row.id)"
        >
          导出 A 卷
        </button>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          @click="router.push(`/admin/exams/${row.id}/edit`)"
        >
          编辑
        </button>
        <button class="btn btn-ghost btn-sm btn-danger" type="button" @click="removeExam(row)">
          删除
        </button>
      </template>
    </DataTable>

    <Pagination v-model:page="page" :size="size" :total="total" />
  </div>
</template>

<style scoped>
.admin-exams {
  padding-top: 28px;
  padding-bottom: 48px;
}
.page-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 700;
}
.page-description { margin: 0; font-size: 14px; color: var(--ink-secondary); }
.create-button { min-height: 40px; }
.toolbar {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 24px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  background: var(--surface);
  flex-wrap: wrap;
}
.filter-field { display: flex; flex-direction: column; gap: 7px; font-size: 13px; font-weight: 600; color: var(--ink-secondary); }
.filter-field .input { width: 180px; min-height: 40px; background: white; font-weight: 400; }
.filter-field.search-field .input { width: 260px; }
.reset-button { min-height: 40px; background: white; }
.list-heading { display:flex; align-items:baseline; justify-content:space-between; gap:12px; margin:0 0 12px; }
.list-heading h2 { margin:0; font-size:16px; }
.list-heading span { color:var(--ink-secondary); font-size:13px; }
.submit-error { padding:12px 16px; border:1px solid var(--danger); color:var(--danger); }
.retry-button { border:0; background:none; color:inherit; cursor:pointer; text-decoration:underline; }
.time {
  color: var(--ink-tertiary);
  font-size: 13px;
}
.mode-acm {
  background: var(--surface);
  color: var(--ink);
}
.mode-oi {
  background: var(--success-bg, #ecfdf5);
  color: var(--success);
}
.status-public {
  background: var(--success-bg, #ecfdf5);
  color: var(--success);
}
.status-private {
  background: var(--bg);
  color: var(--ink-tertiary);
}
.btn-danger {
  color: var(--danger);
}
.btn-highlight {
  color: var(--ink);
  font-weight: 600;
}
.status-ended {
  background: #f1f3f5;
  color: #495057;
}
.status-ongoing {
  background: #ebfbee;
  color: #2b8a3e;
}
.status-upcoming {
  background: var(--surface);
  color: var(--ink-secondary);
}
.status-cancelled {
  background: #fff5f5;
  color: #fa5252;
}
@media (max-width: 600px) {
  .toolbar { align-items: stretch; }
  .filter-field, .filter-field .input, .filter-field.search-field .input { width: 100%; }
  .reset-button { width: 100%; }
}
</style>
