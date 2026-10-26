<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { examApi, type ExamAdminOverview } from '@/api/exam';
import { ApiError } from '@/api/request';
import DataTable from '@/components/DataTable.vue';
import { useToast } from '@/composables/useToast';

const route = useRoute();
const router = useRouter();
const toast = useToast();

const examId = computed(() => Number(route.params.id));
const overview = ref<ExamAdminOverview | null>(null);
const loading = ref(false);
const error = ref('');
const keyword = ref('');

const filteredStudents = computed(() => {
  if (!overview.value) return [];
  const q = keyword.value.trim().toLowerCase();
  if (!q) return overview.value.students;
  return overview.value.students.filter(
    (s) =>
      s.realName.toLowerCase().includes(q) ||
      s.studentNo.toLowerCase().includes(q) ||
      s.username.toLowerCase().includes(q) ||
      s.className.toLowerCase().includes(q) ||
      s.major.toLowerCase().includes(q),
  );
});

const columns = [
  { key: 'select', label: '选择', width: '48px', align: 'center' as const },
  { key: 'studentNo', label: '学号/准考证号', width: '130px' },
  { key: 'realName', label: '姓名', width: '100px' },
  { key: 'classInfo', label: '学院 / 班级' },
  { key: 'username', label: '系统账号', width: '110px' },
  { key: 'totalScore', label: '实得分 / 卷面', width: '130px' },
  { key: 'solvedCount', label: 'AC题数', width: '80px' },
  { key: 'status', label: '交卷状态', width: '100px' },
  { key: 'submittedAt', label: '交卷时间', width: '150px' },
];

const selectedUserIds = ref<number[]>([]);
const exportingBatch = ref(false);

const isAllSelected = computed(() => {
  if (filteredStudents.value.length === 0) return false;
  return filteredStudents.value.every((s) => selectedUserIds.value.includes(s.userId));
});

function toggleSelect(userId: number) {
  const idx = selectedUserIds.value.indexOf(userId);
  if (idx >= 0) {
    selectedUserIds.value.splice(idx, 1);
  } else {
    selectedUserIds.value.push(userId);
  }
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedUserIds.value = [];
  } else {
    selectedUserIds.value = filteredStudents.value.map((s) => s.userId);
  }
}

async function handleBatchExportAll() {
  if (!overview.value || overview.value.totalStudents === 0) {
    toast.error('当前考试暂无考生作答记录，无法批量导出');
    return;
  }
  exportingBatch.value = true;
  toast.info('正在打包全场考生答卷及标准空白卷，请稍候…');
  try {
    await examApi.downloadBatchWord(examId.value);
    toast.success('全场学生答卷 ZIP 打包完成并开始下载！');
  } catch (e: any) {
    toast.error(e?.message || '批量导出失败，请重试');
  } finally {
    exportingBatch.value = false;
  }
}

async function handleBatchExportSelected() {
  if (selectedUserIds.value.length === 0) {
    toast.error('请先勾选需要导出的考生');
    return;
  }
  exportingBatch.value = true;
  toast.info(`正在打包选中的 ${selectedUserIds.value.length} 份考生答卷，请稍候…`);
  try {
    await examApi.downloadBatchWord(examId.value, selectedUserIds.value);
    toast.success(`选中的 ${selectedUserIds.value.length} 份学生答卷导出成功！`);
  } catch (e: any) {
    toast.error(e?.message || '批量导出失败，请重试');
  } finally {
    exportingBatch.value = false;
  }
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const res = await examApi.getAdminOverview(examId.value);
    overview.value = res;
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载考试成绩与考生试卷失败';
    toast.error(error.value);
  } finally {
    loading.value = false;
  }
}

function formatTime(iso: string | null | undefined): string {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '—';
}

function exportBlankWord() {
  examApi.downloadWord(examId.value);
}

function exportBlankPdf() {
  examApi.openPrintPdf(examId.value);
}

function exportStudentWord(userId: number) {
  examApi.downloadWord(examId.value, userId);
}

function exportStudentPdf(userId: number) {
  examApi.openPrintPdf(examId.value, userId);
}

function viewStudentPaper(userId: number) {
  router.push(`/admin/exams/${examId.value}/students/${userId}/paper`);
}

onMounted(load);
</script>

<template>
  <div class="admin-papers-page container">
    <div class="top-breadcrumb">
      <button class="btn btn-ghost btn-sm" @click="router.push('/admin/exams')">
        ← 返回考试列表
      </button>
      <span class="sep">/</span>
      <span class="current-label">考生成绩与答卷总览</span>
    </div>

    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在汇总考试全场数据与考生试卷…</p>
    </div>

    <div v-else-if="error" class="state-card card error-card">
      <p class="error-text">{{ error }}</p>
      <button class="btn btn-primary" @click="load">重试加载</button>
    </div>

    <template v-else-if="overview">
      <!-- 试卷标题与导出工具栏 -->
      <header class="overview-header card">
        <div class="header-left">
          <div class="exam-badge-row">
            <span class="badge" :class="overview.exam.mode === 'ACM' ? 'mode-acm' : 'mode-oi'">
              {{ overview.exam.mode }} 赛制
            </span>
            <span class="badge status-ended">
              {{ overview.exam.sessionStatus === 'ENDED' ? '已收卷归档' : (overview.exam.sessionStatus === 'ONGOING' ? '正在开考中' : '未开考') }}
            </span>
            <span v-if="overview.exam.requireStudentInfo" class="badge badge-info">
              实名信息登记已开启
            </span>
          </div>
          <h1 class="exam-main-title">
            {{ overview.exam.title }}
          </h1>
          <p class="exam-meta-line">
            {{ overview.exam.schoolName || '湖北科技职业学院' }} · {{ overview.exam.semester || '期末考核' }} · 试卷共 {{ overview.exam.questions?.length || 0 }} 题
          </p>
        </div>

        <div class="header-right-actions">
          <button
            class="btn btn-success btn-export"
            :disabled="exportingBatch || !overview.totalStudents"
            @click="handleBatchExportAll"
            title="一键打包导出全场所有考生的试卷 Word (.doc) 压缩包"
          >
            <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>{{ exportingBatch ? '正在打包中…' : '批量导出学生试卷 (ZIP)' }}</span>
          </button>
          <button class="btn btn-primary btn-export" @click="exportBlankWord">
            <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>导出空白 A 卷 (Word)</span>
          </button>
          <button class="btn btn-outline" @click="exportBlankPdf">
            <span>打印 / 导出空白卷 (PDF)</span>
          </button>
        </div>
      </header>

      <!-- Bento 统计指标矩阵 -->
      <section class="stat-bento-grid">
        <div class="bento-card card">
          <span class="bento-label">参考考生总数</span>
          <div class="bento-val-row">
            <span class="bento-val">{{ overview.totalStudents }}</span>
            <span class="bento-unit">人</span>
          </div>
          <span class="bento-sub">本场已进入并登记作答</span>
        </div>

        <div class="bento-card card">
          <span class="bento-label">已正式交卷数</span>
          <div class="bento-val-row">
            <span class="bento-val highlight">{{ overview.submittedCount }}</span>
            <span class="bento-unit">人</span>
          </div>
          <span class="bento-sub">
            交卷率 {{ overview.totalStudents > 0 ? Math.round((overview.submittedCount / overview.totalStudents) * 100) : 0 }}%
          </span>
        </div>

        <div class="bento-card card">
          <span class="bento-label">全场平均总分</span>
          <div class="bento-val-row">
            <span class="bento-val">{{ overview.avgScore }}</span>
            <span class="bento-unit">分</span>
          </div>
          <span class="bento-sub">折算后实际总得分均值</span>
        </div>

        <div class="bento-card card">
          <span class="bento-label">全场最高分</span>
          <div class="bento-val-row">
            <span class="bento-val success-val">{{ overview.maxScore }}</span>
            <span class="bento-unit">分</span>
          </div>
          <span class="bento-sub">最低分: {{ overview.minScore }} 分</span>
        </div>
      </section>

      <!-- 考生答卷表格 -->
      <section class="students-table-section card">
        <div class="table-toolbar">
          <div class="search-box">
            <input
              v-model="keyword"
              class="input search"
              type="text"
              placeholder="搜索姓名、学号、班级、专业或账号…"
            />
          </div>
          <div class="batch-select-actions">
            <button
              class="btn btn-sm btn-ghost"
              type="button"
              @click="toggleSelectAll"
            >
              {{ isAllSelected ? '取消全选' : '全选当页' }}
            </button>
            <button
              v-if="selectedUserIds.length > 0"
              class="btn btn-sm btn-success"
              type="button"
              :disabled="exportingBatch"
              @click="handleBatchExportSelected"
            >
              批量导出选中试卷 ({{ selectedUserIds.length }}份)
            </button>
            <span class="count-tag">已选 {{ selectedUserIds.length }} / 共 {{ filteredStudents.length }} 名考生</span>
          </div>
        </div>

        <DataTable
          :columns="columns"
          :rows="filteredStudents"
          :loading="loading"
          row-key="userId"
          empty-text="本场考试暂无考生作答记录"
        >
          <template #cell-select="{ row }">
            <input
              type="checkbox"
              class="student-checkbox"
              :checked="selectedUserIds.includes(row.userId)"
              @change="toggleSelect(row.userId)"
              :title="'勾选考生 ' + (row.realName || row.username)"
            />
          </template>

          <template #cell-studentNo="{ row }">
            <span class="mono bold-text">{{ row.studentNo }}</span>
          </template>

          <template #cell-realName="{ row }">
            <span class="student-name">{{ row.realName }}</span>
          </template>

          <template #cell-classInfo="{ row }">
            <div class="class-info-block">
              <span class="college-text">{{ row.college !== '—' ? row.college : '' }}</span>
              <span class="major-class">{{ [row.major, row.className].filter(Boolean).join(' · ') || '—' }}</span>
            </div>
          </template>

          <template #cell-username="{ row }">
            <span class="mono muted">{{ row.username }}</span>
          </template>

          <template #cell-totalScore="{ row }">
            <span class="score-display">
              <strong class="earned-score">{{ row.totalScore }}</strong>
              <span class="slash">/</span>
              <span class="full-score">{{ row.fullScore }} 分</span>
            </span>
          </template>

          <template #cell-solvedCount="{ row }">
            <span class="solved-pill">
              {{ row.solvedCount }} / {{ row.totalQuestions }}
            </span>
          </template>

          <template #cell-status="{ row }">
            <span class="badge" :class="row.submitted ? 'status-submitted' : 'status-answering'">
              {{ row.submitted ? '已交卷' : '答题中' }}
            </span>
          </template>

          <template #cell-submittedAt="{ row }">
            <span class="time-text mono">{{ formatTime(row.submittedAt) }}</span>
          </template>

          <template #actions="{ row }">
            <div class="action-btn-group">
              <button
                class="btn btn-primary btn-sm btn-paper-view"
                type="button"
                @click="viewStudentPaper(row.userId)"
              >
                查看答卷
              </button>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                title="导出该生 Word 答卷"
                @click="exportStudentWord(row.userId)"
              >
                导出 Word
              </button>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                title="打印 / 导出该生 PDF 答卷"
                @click="exportStudentPdf(row.userId)"
              >
                PDF
              </button>
            </div>
          </template>
        </DataTable>
      </section>
    </template>
  </div>
</template>

<style scoped>
.admin-papers-page {
  padding-top: 24px;
  padding-bottom: 60px;
}
.top-breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 14px;
}
.top-breadcrumb .sep {
  color: var(--ink-tertiary);
}
.current-label {
  font-weight: 600;
  color: var(--ink-primary);
}

.overview-header {
  margin: 16px 0;
  padding: 20px 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  background: #f4f5f7;
  border: 1px solid #ced4da;
  border-radius: 0;
}
.exam-badge-row {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
.badge-info {
  background: #e7f5ff;
  color: #1971c2;
}
.exam-main-title {
  margin: 0 0 6px 0;
  font-size: 24px;
  font-weight: 700;
  color: #111;
}
.exam-meta-line {
  margin: 0;
  font-size: 13px;
  color: #666;
}
.header-right-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
.btn-icon {
  width: 16px;
  height: 16px;
  margin-right: 4px;
}

.stat-bento-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
@media (max-width: 900px) {
  .stat-bento-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
.bento-card {
  padding: 16px 20px;
  background: #ffffff;
  border: 1px solid #ced4da;
  border-radius: 0;
  display: flex;
  flex-direction: column;
}
.bento-label {
  font-size: 13px;
  color: #666;
  font-weight: 600;
  margin-bottom: 6px;
}
.bento-val-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-bottom: 4px;
}
.bento-val {
  font-size: 28px;
  font-weight: 800;
  color: #212529;
  font-family: 'JetBrains Mono', monospace;
}
.bento-val.highlight {
  color: #1971c2;
}
.bento-val.success-val {
  color: #2b8a3e;
}
.bento-unit {
  font-size: 13px;
  color: #888;
}
.bento-sub {
  font-size: 12px;
  color: #999;
}

.students-table-section {
  padding: 20px;
  background: #ffffff;
  border: 1px solid #ced4da;
  border-radius: 0;
}
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.search-box .search {
  width: 320px;
}
.count-tag {
  font-size: 13px;
  color: #666;
  font-weight: 500;
}

.bold-text {
  font-weight: 600;
  color: #111;
}
.student-name {
  font-weight: 600;
  color: #1971c2;
}
.class-info-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
.college-text {
  color: #888;
}
.major-class {
  color: #333;
}
.score-display {
  font-family: 'JetBrains Mono', monospace;
  font-size: 14px;
}
.earned-score {
  font-size: 16px;
  color: #d9480f;
  font-weight: 700;
}
.slash {
  margin: 0 4px;
  color: #aaa;
}
.full-score {
  color: #666;
}
.solved-pill {
  font-size: 12px;
  font-weight: 600;
  color: #2b8a3e;
  background: #ebfbee;
  padding: 2px 8px;
  border-radius: 2px;
}
.status-submitted {
  background: #ebfbee;
  color: #2b8a3e;
}
.status-answering {
  background: #fff9db;
  color: #f59f00;
}
.time-text {
  font-size: 12px;
  color: #666;
}
.action-btn-group {
  display: flex;
  gap: 6px;
  align-items: center;
}
.btn-paper-view {
  font-weight: 600;
}
.batch-select-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.student-checkbox {
  cursor: pointer;
  width: 16px;
  height: 16px;
  accent-color: #1971c2;
}
.btn-success {
  background: #2b8a3e;
  color: #fff;
  border-color: #2b8a3e;
}
.btn-success:hover:not(:disabled) {
  background: #237032;
  border-color: #237032;
}
</style>
