<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  adminApi,
  type CodeSubmissionRow,
  type DbSubmissionRow,
  type ObjectiveSubmissionRow,
} from '@/api/admin';
import { ApiError } from '@/api/request';
import AdminNav from '@/components/AdminNav.vue';
import DataTable from '@/components/DataTable.vue';
import Pagination from '@/components/Pagination.vue';
import { useConfirm } from '@/composables/useConfirm';
import { useToast } from '@/composables/useToast';
import type { SubmissionStatus } from '@/types';
import { LANGUAGE_LABELS, STATUS_ABBR, statusTone } from '@/utils/submission';

type TabKey = 'code' | 'database' | 'objective';
type CorrectFilter = '' | '1' | '0';

const activeTab = ref<TabKey>('code');
const size = 20;

// ---- 编程题提交 ----
const codeUserId = ref('');
const codeQuestionId = ref('');
const codeStatus = ref<SubmissionStatus | ''>('');
const codePage = ref(1);
const codeRows = ref<CodeSubmissionRow[]>([]);
const codeTotal = ref(0);
const codeLoading = ref(false);
const codeError = ref('');

// ---- 数据库题提交 ----
const dbUserId = ref('');
const dbQuestionId = ref('');
const dbStatus = ref<SubmissionStatus | ''>('');
const dbPage = ref(1);
const dbRows = ref<DbSubmissionRow[]>([]);
const dbTotal = ref(0);
const dbLoading = ref(false);
const dbError = ref('');

// ---- 客观题作答 ----
const objUserId = ref('');
const objQuestionId = ref('');
const objCorrect = ref<CorrectFilter>('');
const objPage = ref(1);
const objRows = ref<ObjectiveSubmissionRow[]>([]);
const objTotal = ref(0);
const objLoading = ref(false);
const objError = ref('');

const statusOptions: Array<{ value: SubmissionStatus | ''; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'ACCEPTED', label: 'AC' },
  { value: 'WRONG_ANSWER', label: 'WA' },
  { value: 'TIME_LIMIT_EXCEEDED', label: 'TLE' },
  { value: 'MEMORY_LIMIT_EXCEEDED', label: 'MLE' },
  { value: 'RUNTIME_ERROR', label: 'RE' },
  { value: 'COMPILE_ERROR', label: 'CE' },
  { value: 'SYSTEM_ERROR', label: 'SE' },
];

const correctOptions: Array<{ value: CorrectFilter; label: string }> = [
  { value: '', label: '全部对错' },
  { value: '1', label: '答对' },
  { value: '0', label: '答错' },
];

const codeColumns = [
  { key: 'submissionId', label: 'ID', width: '70px' },
  { key: 'username', label: '用户', width: '110px' },
  { key: 'questionId', label: '题号', width: '70px' },
  { key: 'questionTitle', label: '题目' },
  { key: 'language', label: '语言', width: '80px' },
  { key: 'status', label: '状态', width: '80px' },
  { key: 'score', label: '得分', width: '70px' },
  { key: 'timeUsedMs', label: '耗时', width: '90px' },
  { key: 'memoryUsedKb', label: '内存', width: '100px' },
  { key: 'createdAt', label: '提交时间', width: '170px' },
];

const dbColumns = [
  { key: 'submissionId', label: 'ID', width: '70px' },
  { key: 'username', label: '用户', width: '110px' },
  { key: 'questionId', label: '题号', width: '70px' },
  { key: 'questionTitle', label: '题目' },
  { key: 'status', label: '状态', width: '80px' },
  { key: 'score', label: '得分', width: '70px' },
  { key: 'timeUsedMs', label: '耗时', width: '90px' },
  { key: 'createdAt', label: '提交时间', width: '170px' },
];

const objColumns = [
  { key: 'submissionId', label: 'ID', width: '70px' },
  { key: 'username', label: '用户', width: '110px' },
  { key: 'questionId', label: '题号', width: '70px' },
  { key: 'questionTitle', label: '题干' },
  { key: 'type', label: '题型', width: '80px' },
  { key: 'correct', label: '对错', width: '80px' },
  { key: 'score', label: '得分', width: '90px' },
  { key: 'createdAt', label: '提交时间', width: '170px' },
];

const currentColumns = () =>
  activeTab.value === 'code'
    ? codeColumns
    : activeTab.value === 'database'
      ? dbColumns
      : objColumns;
const currentRows = () =>
  activeTab.value === 'code'
    ? codeRows.value
    : activeTab.value === 'database'
      ? dbRows.value
      : objRows.value;
const currentTotal = () =>
  activeTab.value === 'code'
    ? codeTotal.value
    : activeTab.value === 'database'
      ? dbTotal.value
      : objTotal.value;
const currentLoading = () =>
  activeTab.value === 'code'
    ? codeLoading.value
    : activeTab.value === 'database'
      ? dbLoading.value
      : objLoading.value;
const currentError = () =>
  activeTab.value === 'code'
    ? codeError.value
    : activeTab.value === 'database'
      ? dbError.value
      : objError.value;

/** 当前标签页的用户 ID 输入（v-model 需要可赋值目标，用 get/set 分发到各 tab 状态）。 */
const activeUserId = ref('');
const activeQuestionId = ref('');
const activeStatus = ref<SubmissionStatus | ''>('');
const activePage = ref(1);

// 切换标签时，把当前 tab 的筛选值同步到「活动」ref，让模板用统一 v-model。
watch(activeTab, () => {
  if (activeTab.value === 'code') {
    activeUserId.value = codeUserId.value;
    activeQuestionId.value = codeQuestionId.value;
    activeStatus.value = codeStatus.value;
    activePage.value = codePage.value;
  } else if (activeTab.value === 'database') {
    activeUserId.value = dbUserId.value;
    activeQuestionId.value = dbQuestionId.value;
    activeStatus.value = dbStatus.value;
    activePage.value = dbPage.value;
  } else {
    activeUserId.value = objUserId.value;
    activeQuestionId.value = objQuestionId.value;
    activePage.value = objPage.value;
  }
});

watch(activeUserId, (v) => {
  if (activeTab.value === 'code') codeUserId.value = v;
  else if (activeTab.value === 'database') dbUserId.value = v;
  else objUserId.value = v;
});
watch(activeQuestionId, (v) => {
  if (activeTab.value === 'code') codeQuestionId.value = v;
  else if (activeTab.value === 'database') dbQuestionId.value = v;
  else objQuestionId.value = v;
});
watch(activeStatus, (v) => {
  if (activeTab.value === 'code') codeStatus.value = v;
  else if (activeTab.value === 'database') dbStatus.value = v;
});
watch(activePage, (v) => {
  if (activeTab.value === 'code') codePage.value = v;
  else if (activeTab.value === 'database') dbPage.value = v;
  else objPage.value = v;
});

/** 数字筛选输入转可选 number：空串/非数字一律 undefined。 */
function toOptionalNumber(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : undefined;
}

async function load() {
  const key = activeTab.value;
  const setLoading = (v: boolean) => {
    if (key === 'code') codeLoading.value = v;
    else if (key === 'database') dbLoading.value = v;
    else objLoading.value = v;
  };
  const setError = (msg: string) => {
    if (key === 'code') codeError.value = msg;
    else if (key === 'database') dbError.value = msg;
    else objError.value = msg;
  };
  setLoading(true);
  setError('');
  try {
    const page = activePage.value;
    if (key === 'code') {
      const res = await adminApi.listCodeSubmissions({
        userId: toOptionalNumber(codeUserId.value),
        questionId: toOptionalNumber(codeQuestionId.value),
        status: codeStatus.value || undefined,
        page,
        size,
      });
      codeRows.value = res.list;
      codeTotal.value = res.total;
    } else if (key === 'database') {
      const res = await adminApi.listDbSubmissions({
        userId: toOptionalNumber(dbUserId.value),
        questionId: toOptionalNumber(dbQuestionId.value),
        status: dbStatus.value || undefined,
        page,
        size,
      });
      dbRows.value = res.list;
      dbTotal.value = res.total;
    } else {
      const res = await adminApi.listObjectiveSubmissions({
        userId: toOptionalNumber(objUserId.value),
        questionId: toOptionalNumber(objQuestionId.value),
        correct: objCorrect.value === '' ? undefined : Number(objCorrect.value),
        page,
        size,
      });
      objRows.value = res.list;
      objTotal.value = res.total;
    }
  } catch (e) {
    setError(e instanceof ApiError ? e.message : '请求失败，请稍后重试');
  } finally {
    setLoading(false);
  }
}

/** 筛选变化时回到第一页再加载；已在第一页则手动触发。 */
function resetAndLoad() {
  if (activePage.value === 1) {
    load();
  } else {
    activePage.value = 1;
  }
}

function switchTab(key: TabKey) {
  if (activeTab.value === key) return;
  activeTab.value = key;
  load();
}

// 用户/题号输入防抖 300ms；下拉筛选变化直接触发。
let filterTimer: ReturnType<typeof setTimeout> | null = null;

watch([activeUserId, activeQuestionId], () => {
  if (filterTimer !== null) clearTimeout(filterTimer);
  filterTimer = setTimeout(resetAndLoad, 300);
});

watch([activeStatus, objCorrect], resetAndLoad);

watch(activePage, () => {
  load();
});

/** 创建时间：ISO 串截取前 19 位并把 T 换成空格，得到 "YYYY-MM-DD HH:mm:ss"。 */
function formatTime(iso: string): string {
  return iso ? iso.slice(0, 19).replace('T', ' ') : '—';
}

const OBJECTIVE_TYPE_LABELS: Record<string, string> = {
  SINGLE: '单选',
  MULTIPLE: '多选',
  JUDGE: '判断',
  FILL: '填空',
};

function formatMemory(kb: number | null): string {
  if (kb == null) return '-';
  if (kb < 1024) return `${kb}KB`;
  const mb = kb / 1024;
  if (mb >= 1000) return `${Math.round(mb)}MB`;
  return `${mb.toFixed(1).replace(/\.0$/, '')}MB`;
}

const { confirm } = useConfirm();
const { success, error } = useToast();

const REJUDGE_HINT =
  '重判将使用题目当前的数据（用例/时限/答案），结果可能与原结果不同；已通过的标记会暂时消失。确认重判？';

/** 单条重判：按当前 tab 调用对应接口，成功刷新列表。 */
async function rejudgeRow(row: CodeSubmissionRow | DbSubmissionRow | ObjectiveSubmissionRow) {
  const ok = await confirm({ title: '重判该提交', message: REJUDGE_HINT, confirmText: '重判' });
  if (!ok) return;
  try {
    if (activeTab.value === 'code') {
      await adminApi.rejudgeSubmission(row.submissionId);
    } else if (activeTab.value === 'database') {
      await adminApi.rejudgeDbSubmission(row.submissionId);
    } else {
      await adminApi.rejudgeObjectiveSubmission(row.submissionId);
    }
    success('重判完成，列表已刷新');
    load();
  } catch (e) {
    error(e instanceof ApiError ? e.message : '重判失败，请稍后重试');
  }
}

/** 客观题按题批量重判：使用当前题目 ID 筛选框的值。 */
async function rejudgeQuestionBatch() {
  const qid = toOptionalNumber(objQuestionId.value);
  if (qid == null) return;
  const ok = await confirm({
    title: '按题批量重判',
    message: `将用该题当前答案与分值重判全部作答记录（题目 ${qid}），确认？`,
    confirmText: '重判',
  });
  if (!ok) return;
  try {
    const res = await adminApi.rejudgeObjectiveQuestion(qid);
    success(`已重判 ${res.rejudged ?? 0} 条作答记录`);
    load();
  } catch (e) {
    error(e instanceof ApiError ? e.message : '批量重判失败，请稍后重试');
  }
}

/** 客观题 tab：题目 ID 已填时允许按题批量重判。 */
const canBatchRejudge = () =>
  activeTab.value === 'objective' && toOptionalNumber(objQuestionId.value) != null;

// ---- 提交详情/代码查看弹窗 ----
const selectedCodeRow = ref<CodeSubmissionRow | null>(null);
const selectedDbRow = ref<DbSubmissionRow | null>(null);
const selectedObjRow = ref<ObjectiveSubmissionRow | null>(null);
const copyTip = ref(false);

function parseCode(codeContent: string): string {
  if (!codeContent) return '';
  try {
    const obj = JSON.parse(codeContent);
    if (obj && typeof obj === 'object') {
      const val = Object.values(obj).find((v) => typeof v === 'string' && v.length > 0);
      if (typeof val === 'string') return val;
    }
  } catch {
    return codeContent;
  }
  return codeContent;
}

async function copyText(text: string) {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    copyTip.value = true;
    setTimeout(() => {
      copyTip.value = false;
    }, 1500);
    success('已复制到剪贴板');
  } catch {
    error('复制失败，请手动选中文本复制');
  }
}

function openViewModal(row: CodeSubmissionRow | DbSubmissionRow | ObjectiveSubmissionRow) {
  if (activeTab.value === 'code') {
    selectedCodeRow.value = row as CodeSubmissionRow;
  } else if (activeTab.value === 'database') {
    selectedDbRow.value = row as DbSubmissionRow;
  } else {
    selectedObjRow.value = row as ObjectiveSubmissionRow;
  }
}

onMounted(() => {
  load();
});

onBeforeUnmount(() => {
  if (filterTimer !== null) clearTimeout(filterTimer);
});
</script>

<template>
  <div class="admin container">
    <h1 class="page-title">提交监控</h1>
    <AdminNav />

    <div class="tabs">
      <button class="tab" :class="{ active: activeTab === 'code' }" @click="switchTab('code')">
        编程题
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'database' }"
        @click="switchTab('database')"
      >
        数据库题
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'objective' }"
        @click="switchTab('objective')"
      >
        客观题
      </button>
    </div>

    <div class="filter-bar card">
      <label class="filter">
        <span class="filter-label">用户 ID</span>
        <input
          v-model="activeUserId"
          class="input id-input"
          type="search"
          placeholder="提交者用户 ID"
        />
      </label>
      <label class="filter">
        <span class="filter-label">题目 ID</span>
        <input
          v-model="activeQuestionId"
          class="input id-input"
          type="search"
          placeholder="题目 ID"
        />
      </label>
      <label v-if="activeTab !== 'objective'" class="filter">
        <span class="filter-label">状态</span>
        <select v-model="activeStatus" class="input">
          <option v-for="s in statusOptions" :key="s.value || 'all'" :value="s.value">
            {{ s.label }}
          </option>
        </select>
      </label>
      <label v-else class="filter">
        <span class="filter-label">对错</span>
        <select v-model="objCorrect" class="input">
          <option v-for="c in correctOptions" :key="c.value || 'all'" :value="c.value">
            {{ c.label }}
          </option>
        </select>
      </label>
      <button
        v-if="canBatchRejudge()"
        class="btn btn-ghost btn-sm batch-btn"
        type="button"
        @click="rejudgeQuestionBatch"
      >
        按题批量重判
      </button>
    </div>

    <p v-if="currentError()" class="submit-error">{{ currentError() }}</p>

    <DataTable
      :key="activeTab"
      :columns="currentColumns()"
      :rows="currentRows()"
      :loading="currentLoading()"
      row-key="submissionId"
    >
      <template #cell-status="{ row }">
        <span
          class="status-badge clickable"
          :class="statusTone(row.status as SubmissionStatus)"
          :title="activeTab === 'code' ? '点击查看代码' : '点击查看 SQL'"
          @click="openViewModal(row)"
        >
          {{ STATUS_ABBR[row.status as SubmissionStatus] ?? row.status }}
        </span>
      </template>
      <template #cell-score="{ row }">
        <span class="mono">{{ row.score ?? '-' }}</span>
      </template>
      <template #cell-timeUsedMs="{ row }">
        <span class="mono">{{ row.timeUsedMs != null ? row.timeUsedMs + 'ms' : '-' }}</span>
      </template>
      <template #cell-memoryUsedKb="{ row }">
        <span class="mono">{{ formatMemory(row.memoryUsedKb) }}</span>
      </template>
      <template #cell-language="{ row }">
        <span>{{ LANGUAGE_LABELS[row.language] ?? row.language }}</span>
      </template>
      <template #cell-correct="{ row }">
        <span
          class="status-badge clickable"
          :class="row.correct === 1 ? 'ac' : 'wa'"
          title="点击查看作答详情"
          @click="openViewModal(row)"
        >
          {{ row.correct === 1 ? 'AC' : 'WA' }}
        </span>
      </template>
      <template #cell-type="{ row }">
        <span>{{ OBJECTIVE_TYPE_LABELS[row.type] ?? row.type }}</span>
      </template>
      <template #cell-questionTitle="{ row }">
        <span class="q-title">{{ row.questionTitle || '—' }}</span>
      </template>
      <template #cell-createdAt="{ row }">
        <span class="time">{{ formatTime(row.createdAt) }}</span>
      </template>
      <template #cell-username="{ row }">
        <span class="muted">{{ row.username }}</span>
      </template>
      <template #actions="{ row }">
        <div class="row-actions">
          <button class="btn btn-outline btn-sm action-btn" type="button" @click="openViewModal(row)">
            {{ activeTab === 'code' ? '查看代码' : activeTab === 'database' ? '查看 SQL' : '查看作答' }}
          </button>
          <button class="btn btn-ghost btn-sm action-btn" type="button" @click="rejudgeRow(row)">重判</button>
        </div>
      </template>
    </DataTable>

    <Pagination
      :key="'p-' + activeTab"
      v-model:page="activePage"
      :size="size"
      :total="currentTotal()"
    />

    <!-- 弹窗 1: 编程题代码预览弹窗 -->
    <Teleport to="body">
      <div v-if="selectedCodeRow" class="modal-overlay" @click.self="selectedCodeRow = null">
        <div class="modal-card code-view-modal" role="dialog" aria-modal="true">
          <div class="modal-header">
            <div class="code-modal-title-group">
              <span class="mono code-modal-id">#{{ selectedCodeRow.submissionId }}</span>
              <span class="code-modal-title">{{ selectedCodeRow.questionTitle || '编程题' }}</span>
              <span class="status-badge" :class="statusTone(selectedCodeRow.status as SubmissionStatus)">
                {{ STATUS_ABBR[selectedCodeRow.status as SubmissionStatus] ?? selectedCodeRow.status }}
              </span>
            </div>
            <button class="modal-close-btn" type="button" @click="selectedCodeRow = null">✕</button>
          </div>

          <div class="modal-body modal-code-body">
            <div class="code-modal-meta mono">
              <span>用户: <b>{{ selectedCodeRow.username }}</b> (UID: {{ selectedCodeRow.userId }})</span>
              <span>语言: <b>{{ LANGUAGE_LABELS[selectedCodeRow.language] ?? selectedCodeRow.language }}</b></span>
              <span>得分: <b>{{ selectedCodeRow.score ?? '-' }}</b></span>
              <span>用时: <b>{{ selectedCodeRow.timeUsedMs != null ? selectedCodeRow.timeUsedMs + 'ms' : '-' }}</b></span>
              <span>内存: <b>{{ formatMemory(selectedCodeRow.memoryUsedKb) }}</b></span>
              <span class="copy-action-span">
                <button
                  type="button"
                  class="btn btn-ghost btn-sm copy-btn"
                  @click="copyText(parseCode(selectedCodeRow.codeContent))"
                >
                  {{ copyTip ? '已复制 ✔' : '复制代码' }}
                </button>
              </span>
            </div>
            <pre class="code-pre mono"><code>{{ parseCode(selectedCodeRow.codeContent) }}</code></pre>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 弹窗 2: 数据库 SQL 预览弹窗 -->
    <Teleport to="body">
      <div v-if="selectedDbRow" class="modal-overlay" @click.self="selectedDbRow = null">
        <div class="modal-card code-view-modal" role="dialog" aria-modal="true">
          <div class="modal-header">
            <div class="code-modal-title-group">
              <span class="mono code-modal-id">#{{ selectedDbRow.submissionId }}</span>
              <span class="code-modal-title">{{ selectedDbRow.questionTitle || '数据库题' }}</span>
              <span class="status-badge" :class="statusTone(selectedDbRow.status as SubmissionStatus)">
                {{ STATUS_ABBR[selectedDbRow.status as SubmissionStatus] ?? selectedDbRow.status }}
              </span>
            </div>
            <button class="modal-close-btn" type="button" @click="selectedDbRow = null">✕</button>
          </div>

          <div class="modal-body modal-code-body">
            <div class="code-modal-meta mono">
              <span>用户: <b>{{ selectedDbRow.username }}</b> (UID: {{ selectedDbRow.userId }})</span>
              <span>得分: <b>{{ selectedDbRow.score ?? '-' }}</b></span>
              <span>用时: <b>{{ selectedDbRow.timeUsedMs != null ? selectedDbRow.timeUsedMs + 'ms' : '-' }}</b></span>
              <span class="copy-action-span">
                <button
                  type="button"
                  class="btn btn-ghost btn-sm copy-btn"
                  @click="copyText(selectedDbRow.sqlContent)"
                >
                  {{ copyTip ? '已复制 ✔' : '复制 SQL' }}
                </button>
              </span>
            </div>
            <pre class="code-pre mono"><code>{{ selectedDbRow.sqlContent }}</code></pre>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 弹窗 3: 客观题作答预览弹窗 -->
    <Teleport to="body">
      <div v-if="selectedObjRow" class="modal-overlay" @click.self="selectedObjRow = null">
        <div class="modal-card code-view-modal" role="dialog" aria-modal="true">
          <div class="modal-header">
            <div class="code-modal-title-group">
              <span class="mono code-modal-id">#{{ selectedObjRow.submissionId }}</span>
              <span class="code-modal-title">客观题作答详情</span>
              <span class="status-badge" :class="selectedObjRow.correct === 1 ? 'ac' : 'wa'">
                {{ selectedObjRow.correct === 1 ? '答对 AC' : '答错 WA' }}
              </span>
            </div>
            <button class="modal-close-btn" type="button" @click="selectedObjRow = null">✕</button>
          </div>

          <div class="modal-body modal-code-body">
            <div class="code-modal-meta mono">
              <span>用户: <b>{{ selectedObjRow.username }}</b> (UID: {{ selectedObjRow.userId }})</span>
              <span>题型: <b>{{ OBJECTIVE_TYPE_LABELS[selectedObjRow.type] ?? selectedObjRow.type }}</b></span>
              <span>得分: <b>{{ selectedObjRow.score ?? '-' }} / {{ selectedObjRow.fullScore ?? '-' }}</b></span>
            </div>
            <div class="obj-detail-section">
              <div class="obj-detail-label">题目内容：</div>
              <div class="obj-detail-content">{{ selectedObjRow.questionTitle }}</div>
            </div>
            <div class="obj-detail-grid">
              <div class="obj-detail-box user-ans" :class="selectedObjRow.correct === 1 ? 'box-correct' : 'box-wrong'">
                <div class="box-label">学生作答</div>
                <div class="box-content mono">{{ selectedObjRow.answer || '（未作答）' }}</div>
              </div>
              <div class="obj-detail-box std-ans">
                <div class="box-label">标准答案</div>
                <div class="box-content mono">{{ selectedObjRow.correctAnswer || '—' }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
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
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.tab {
  padding: 7px 20px;
  border-radius: var(--pill);
  font-size: 14px;
  color: var(--ink-secondary);
  border: 1px solid var(--line);
  transition:
    color 0.15s ease,
    background 0.15s ease;
}
.tab.active {
  background: var(--button);
  border-color: var(--button);
  color: #fff;
}
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
  padding: 16px;
  margin-bottom: 24px;
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
  width: 130px;
}
.id-input {
  width: 150px;
}
.batch-btn {
  margin-left: auto;
}
.submit-error {
  margin: 0 0 16px;
}
.status-badge {
  display: inline-block;
  min-width: 44px;
  padding: 2px 10px;
  border: none;
  border-radius: var(--pill);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  font-family: inherit;
}
.status-badge.ac {
  background: var(--success);
}
.status-badge.wa {
  background: var(--danger);
}
.status-badge.tle {
  background: #000;
}
.status-badge.mle {
  background: #f57c00;
}
.status-badge.re {
  background: #9c27b0;
}
.status-badge.ce,
.status-badge.se {
  background: #607d8b;
}
.status-badge.run {
  background: #9e9e9e;
}
.q-title {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  vertical-align: bottom;
}
.time {
  color: var(--ink-tertiary);
  font-size: 13px;
}
.clickable {
  cursor: pointer;
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.clickable:hover {
  opacity: 0.85;
  transform: translateY(-1px);
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.action-btn {
  padding: 3px 8px;
  font-size: 12px;
}
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 20px;
}
.modal-card {
  background: var(--bg, #fff);
  border: 1px solid var(--line, #e5e7eb);
  border-radius: 12px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.2);
  width: 100%;
  max-width: 820px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: modalIn 0.18s ease-out;
}
@keyframes modalIn {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line, #e5e7eb);
  background: var(--bg-secondary, #fafafa);
}
.code-modal-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.code-modal-id {
  font-size: 14px;
  color: var(--ink-secondary);
  font-weight: 700;
}
.code-modal-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}
.modal-close-btn {
  font-size: 16px;
  color: var(--ink-tertiary);
  padding: 4px;
  line-height: 1;
  background: none;
  border: none;
  cursor: pointer;
}
.modal-close-btn:hover {
  color: var(--ink);
}
.modal-code-body {
  padding: 18px 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}
.code-modal-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 13px;
  color: var(--ink-secondary);
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line, #e5e7eb);
}
.copy-action-span {
  margin-left: auto;
}
.code-pre {
  margin: 0;
  padding: 16px;
  background: #1e1e1e;
  color: #f8f8f2;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  max-height: 480px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.obj-detail-section {
  padding: 10px 14px;
  background: var(--bg-secondary, #f9fafb);
  border-radius: 8px;
  border: 1px solid var(--line, #e5e7eb);
  font-size: 14px;
  line-height: 1.6;
}
.obj-detail-label {
  font-weight: 600;
  color: var(--ink-secondary);
  margin-bottom: 4px;
  font-size: 12px;
}
.obj-detail-content {
  color: var(--ink);
}
.obj-detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.obj-detail-box {
  padding: 12px 14px;
  border-radius: 8px;
  border: 1px solid var(--line, #e5e7eb);
}
.box-label {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--ink-secondary);
}
.box-content {
  font-size: 14px;
  font-weight: 600;
}
.box-correct {
  background: #f0fdf4;
  border-color: #86efac;
  color: #166534;
}
.box-wrong {
  background: #fef2f2;
  border-color: #fca5a5;
  color: #991b1b;
}
.std-ans {
  background: #f8fafc;
  border-color: #cbd5e1;
  color: #1e293b;
}
</style>
