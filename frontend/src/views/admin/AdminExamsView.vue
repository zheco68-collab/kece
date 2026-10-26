<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
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
const modeFilter = ref('');
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

function exportBlankDoc(examId: number) {
  examApi.downloadWord(examId);
}

const exportingBatchExamId = ref<number | null>(null);

async function handleExportBatchDoc(examId: number, examTitle: string) {
  exportingBatchExamId.value = examId;
  toast.info(`正在打包考试「${examTitle}」的所有学生试卷，请稍候…`);
  try {
    await examApi.downloadBatchWord(examId);
    toast.success(`考试「${examTitle}」学生试卷导出成功！`);
  } catch (e: any) {
    toast.error(e?.message || '批量导出试卷失败，请稍后重试');
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
watch([statusFilter, modeFilter], resetAndLoad);
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
</script>

<template>
  <div class="admin-exams container">
    <h1 class="page-title">考试管理</h1>

    <div class="toolbar">
      <input v-model="keyword" class="input search" type="text" placeholder="搜索试卷标题…" />
      <select v-model="statusFilter" class="input">
        <option value="">全部状态</option>
        <option value="1">公开</option>
        <option value="0">私有</option>
      </select>
      <button class="btn btn-primary" type="button" @click="router.push('/admin/exams/new')">
        新建试卷
      </button>
    </div>

    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

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
          @click="handleExportBatchDoc(row.id, row.title)"
          title="批量打包导出本场所有考生的试卷 Word (.doc) 压缩包"
        >
          {{ exportingBatchExamId === row.id ? '打包中…' : '批量导出答卷' }}
        </button>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          @click="exportBlankDoc(row.id)"
          title="导出标准 A 卷 Word 文档"
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
  width: 240px;
}
.time {
  color: var(--ink-tertiary);
  font-size: 13px;
}
.mode-acm {
  background: var(--accent-bg, #eef2ff);
  color: #6366f1;
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
  color: #1971c2;
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
  background: #e7f5ff;
  color: #1c7ed6;
}
.status-cancelled {
  background: #fff5f5;
  color: #fa5252;
}
</style>
