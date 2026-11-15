<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  adminApi,
  type CodeSubmissionRow,
  type DbSubmissionRow,
  type ObjectiveSubmissionRow,
} from '@/api/admin';
import { ApiError } from '@/api/request';
import DataTable from '@/components/DataTable.vue';
import Pagination from '@/components/Pagination.vue';
import { useConfirm } from '@/composables/useConfirm';
import { useToast } from '@/composables/useToast';
import type { SubmissionStatus } from '@/types';
import { LANGUAGE_LABELS, STATUS_ABBR, statusTone } from '@/utils/submission';

type TabKey = 'code' | 'database' | 'objective';
type CorrectFilter = '' | '1' | '0';

/**
 * 三类提交列表共享的展示契约（不包含得分字段，后台提交监控严格隐藏得分）。
 */
type SubmissionTableRow = {
  submissionId: number;
  userId: number;
  username: string;
  questionId: number;
  questionTitle: string | null;
  createdAt: string;
  status?: string;
  language?: string;
  codeContent?: string;
  timeUsedMs?: number | null;
  memoryUsedKb?: number | null;
  sqlContent?: string;
  type?: string;
  answer?: string;
  correct?: number;
  correctAnswer?: string;
};

type DrawerDetail =
  | { kind: 'code'; row: CodeSubmissionRow }
  | { kind: 'database'; row: DbSubmissionRow }
  | { kind: 'objective'; row: ObjectiveSubmissionRow };

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
  { key: 'submissionId', label: '提交 / 用户', width: '175px' },
  { key: 'questionTitle', label: '题目' },
  { key: 'language', label: '语言', width: '90px' },
  { key: 'status', label: '评测状态', width: '96px' },
  { key: 'resource', label: '耗时 / 内存', width: '132px' },
  { key: 'createdAt', label: '时间', width: '164px' },
];

const dbColumns = [
  { key: 'submissionId', label: '提交 / 用户', width: '175px' },
  { key: 'questionTitle', label: '题目' },
  { key: 'status', label: '评测状态', width: '96px' },
  { key: 'resource', label: '耗时', width: '96px' },
  { key: 'createdAt', label: '时间', width: '164px' },
];

const objColumns = [
  { key: 'submissionId', label: '提交 / 用户', width: '175px' },
  { key: 'questionTitle', label: '题目' },
  { key: 'type', label: '题型', width: '80px' },
  { key: 'correct', label: '对错', width: '90px' },
  { key: 'createdAt', label: '时间', width: '164px' },
];

const currentColumns = computed(() =>
  activeTab.value === 'code'
    ? codeColumns
    : activeTab.value === 'database'
      ? dbColumns
      : objColumns,
);

const currentRows = computed<SubmissionTableRow[]>(() =>
  activeTab.value === 'code'
    ? codeRows.value
    : activeTab.value === 'database'
      ? dbRows.value
      : objRows.value,
);

const currentTotal = computed(() =>
  activeTab.value === 'code'
    ? codeTotal.value
    : activeTab.value === 'database'
      ? dbTotal.value
      : objTotal.value,
);

const currentLoading = computed(() =>
  activeTab.value === 'code'
    ? codeLoading.value
    : activeTab.value === 'database'
      ? dbLoading.value
      : objLoading.value,
);

const currentError = computed(() =>
  activeTab.value === 'code'
    ? codeError.value
    : activeTab.value === 'database'
      ? dbError.value
      : objError.value,
);

/** 当前标签页的活跃筛选代理状态。 */
const activeUserId = ref('');
const activeQuestionId = ref('');
const activeStatus = ref<SubmissionStatus | ''>('');
const activePage = ref(1);

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

function toOptionalNumber(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : undefined;
}

const hasActiveFilters = computed(() => {
  if (activeTab.value === 'objective') {
    return (
      activeUserId.value.trim() !== '' ||
      activeQuestionId.value.trim() !== '' ||
      objCorrect.value !== ''
    );
  }
  return (
    activeUserId.value.trim() !== '' ||
    activeQuestionId.value.trim() !== '' ||
    activeStatus.value !== ''
  );
});

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
    syncDetailRow();
  } catch (e) {
    setError(e instanceof ApiError ? e.message : '请求失败，请稍后重试');
  } finally {
    setLoading(false);
  }
}

function resetAndLoad() {
  if (activePage.value === 1) {
    load();
  } else {
    activePage.value = 1;
  }
}

function resetFilters() {
  if (filterTimer !== null) {
    clearTimeout(filterTimer);
    filterTimer = null;
  }
  activeUserId.value = '';
  activeQuestionId.value = '';
  if (activeTab.value === 'objective') {
    objCorrect.value = '';
  } else {
    activeStatus.value = '';
  }
  resetAndLoad();
}

function switchTab(key: TabKey) {
  if (activeTab.value === key) return;
  closeDetailDrawer();
  activeTab.value = key;
  load();
}

let filterTimer: ReturnType<typeof setTimeout> | null = null;
let copyTimer: ReturnType<typeof setTimeout> | null = null;

watch([activeUserId, activeQuestionId], () => {
  if (filterTimer !== null) clearTimeout(filterTimer);
  filterTimer = setTimeout(resetAndLoad, 300);
});

watch([activeStatus, objCorrect], resetAndLoad);

watch(activePage, () => {
  load();
});

function formatTime(iso: string): string {
  return iso ? iso.slice(0, 19).replace('T', ' ') : '—';
}

const OBJECTIVE_TYPE_LABELS: Record<string, string> = {
  SINGLE: '单选',
  MULTIPLE: '多选',
  JUDGE: '判断',
  FILL: '填空',
};

function formatMemory(kb: number | null | undefined): string {
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

const canBatchRejudge = computed(
  () => activeTab.value === 'objective' && toOptionalNumber(objQuestionId.value) != null,
);

async function rejudgeQuestionBatch() {
  const qid = toOptionalNumber(objQuestionId.value);
  if (qid == null) return;
  const ok = await confirm({
    title: '按题批量重判',
    message: `将用该题当前答案重判全部作答记录（题目 #${qid}），确认？`,
    confirmText: '重判',
  });
  if (!ok) return;
  try {
    const res = await adminApi.rejudgeObjectiveQuestion(qid);
    success(`已重判 ${res.rejudged ?? 0} 条作答记录`);
    await load();
  } catch (e) {
    error(e instanceof ApiError ? e.message : '批量重判失败，请稍后重试');
  }
}

// ---- 提交详情右侧抽屉 ----
const detail = ref<DrawerDetail | null>(null);
const rejudging = ref(false);
const copyTip = ref(false);
const drawerCloseBtn = ref<HTMLButtonElement | null>(null);
let previousFocus: HTMLElement | null = null;

function syncDetailRow() {
  if (!detail.value) return;
  const { kind, row } = detail.value;
  if (kind === 'code') {
    const updated = codeRows.value.find((item) => item.submissionId === row.submissionId);
    if (updated) detail.value = { kind: 'code', row: updated };
  } else if (kind === 'database') {
    const updated = dbRows.value.find((item) => item.submissionId === row.submissionId);
    if (updated) detail.value = { kind: 'database', row: updated };
  } else {
    const updated = objRows.value.find((item) => item.submissionId === row.submissionId);
    if (updated) detail.value = { kind: 'objective', row: updated };
  }
}

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
    if (copyTimer !== null) clearTimeout(copyTimer);
    copyTimer = setTimeout(() => {
      copyTip.value = false;
    }, 1500);
    success('已复制到剪贴板');
  } catch {
    error('复制失败，请手动选中文本复制');
  }
}

function toDrawerDetail(row: SubmissionTableRow): DrawerDetail | null {
  if (activeTab.value === 'code' && typeof row.codeContent === 'string') {
    return { kind: 'code', row: row as CodeSubmissionRow };
  }
  if (activeTab.value === 'database' && typeof row.sqlContent === 'string') {
    return { kind: 'database', row: row as DbSubmissionRow };
  }
  if (
    activeTab.value === 'objective' &&
    typeof row.type === 'string' &&
    typeof row.answer === 'string'
  ) {
    return { kind: 'objective', row: row as ObjectiveSubmissionRow };
  }
  return null;
}

function openDetailDrawer(row: SubmissionTableRow) {
  const resolved = toDrawerDetail(row);
  if (!resolved) {
    error('提交记录类型不匹配，请刷新页面后重试');
    return;
  }
  previousFocus = document.activeElement as HTMLElement | null;
  copyTip.value = false;
  detail.value = resolved;
  nextTick(() => {
    drawerCloseBtn.value?.focus();
  });
}

function closeDetailDrawer() {
  if (!detail.value) return;
  detail.value = null;
  const target = previousFocus;
  previousFocus = null;
  nextTick(() => {
    target?.focus();
  });
}

async function rejudgeDetail() {
  if (!detail.value || rejudging.value) return;
  const current = detail.value;
  const ok = await confirm({
    title: `重判提交 #${current.row.submissionId}`,
    message: REJUDGE_HINT,
    confirmText: '确认重判',
  });
  if (!ok) return;

  rejudging.value = true;
  try {
    if (current.kind === 'code') {
      await adminApi.rejudgeSubmission(current.row.submissionId);
    } else if (current.kind === 'database') {
      await adminApi.rejudgeDbSubmission(current.row.submissionId);
    } else {
      await adminApi.rejudgeObjectiveSubmission(current.row.submissionId);
    }
    success('重判完成，列表已刷新');
    await load();
  } catch (e) {
    error(e instanceof ApiError ? e.message : '重判失败，请稍后重试');
  } finally {
    rejudging.value = false;
  }
}

function handleWindowKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && detail.value) {
    closeDetailDrawer();
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleWindowKeydown);
  load();
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleWindowKeydown);
  if (filterTimer !== null) clearTimeout(filterTimer);
  if (copyTimer !== null) clearTimeout(copyTimer);
});
</script>

<template>
  <div class="admin container submissions-page">
    <header class="page-header">
      <div class="header-copy">
        <h1 class="page-title">提交监控</h1>
        <p>按题型检索提交记录，核查代码与作答详情，或触发单条 / 按题重判。</p>
      </div>
      <div class="header-actions">
        <button
          class="btn btn-outline btn-sm refresh-btn"
          type="button"
          :disabled="currentLoading"
          @click="load"
        >
          {{ currentLoading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <div class="toolbar-card card">
      <div class="tabs" role="tablist" aria-label="提交题型">
        <button
          class="tab"
          role="tab"
          type="button"
          :aria-selected="activeTab === 'code'"
          :class="{ active: activeTab === 'code' }"
          @click="switchTab('code')"
        >
          编程题
        </button>
        <button
          class="tab"
          role="tab"
          type="button"
          :aria-selected="activeTab === 'database'"
          :class="{ active: activeTab === 'database' }"
          @click="switchTab('database')"
        >
          数据库题
        </button>
        <button
          class="tab"
          role="tab"
          type="button"
          :aria-selected="activeTab === 'objective'"
          :class="{ active: activeTab === 'objective' }"
          @click="switchTab('objective')"
        >
          客观题
        </button>
      </div>

      <div class="filter-bar">
        <label class="filter">
          <span class="filter-label">用户 ID</span>
          <input
            v-model="activeUserId"
            class="input id-input"
            type="search"
            inputmode="numeric"
            placeholder="精确用户 ID"
          />
        </label>
        <label class="filter">
          <span class="filter-label">题目 ID</span>
          <input
            v-model="activeQuestionId"
            class="input id-input"
            type="search"
            inputmode="numeric"
            placeholder="精确题目 ID"
          />
        </label>
        <label v-if="activeTab !== 'objective'" class="filter">
          <span class="filter-label">评测状态</span>
          <select v-model="activeStatus" class="input">
            <option v-for="s in statusOptions" :key="s.value || 'all'" :value="s.value">
              {{ s.label }}
            </option>
          </select>
        </label>
        <label v-else class="filter">
          <span class="filter-label">对错状态</span>
          <select v-model="objCorrect" class="input">
            <option v-for="c in correctOptions" :key="c.value || 'all'" :value="c.value">
              {{ c.label }}
            </option>
          </select>
        </label>
        <div class="filter-actions">
          <button
            v-if="hasActiveFilters"
            class="btn btn-ghost btn-sm"
            type="button"
            @click="resetFilters"
          >
            重置
          </button>
          <button
            v-if="canBatchRejudge"
            class="btn btn-outline btn-sm batch-btn"
            type="button"
            @click="rejudgeQuestionBatch"
          >
            按题批量重判
          </button>
        </div>
      </div>
    </div>

    <p v-if="currentError" class="submit-error" role="alert">{{ currentError }}</p>

    <DataTable
      :key="activeTab"
      :columns="currentColumns"
      :rows="currentRows"
      :loading="currentLoading"
      row-key="submissionId"
      empty-text="暂无符合条件的提交记录"
    >
      <template #cell-submissionId="{ row }">
        <div class="cell-primary">
          <span class="mono sub-id">#{{ row.submissionId }}</span>
          <span class="sub-user">{{ row.username }}</span>
        </div>
      </template>
      <template #cell-questionTitle="{ row }">
        <div class="cell-question">
          <span class="q-title" :title="row.questionTitle || undefined">{{ row.questionTitle || '—' }}</span>
          <span class="q-id mono">题目 #{{ row.questionId }}</span>
        </div>
      </template>
      <template #cell-language="{ row }">
        <span class="lang-tag">{{ LANGUAGE_LABELS[row.language ?? ''] ?? row.language ?? '—' }}</span>
      </template>
      <template #cell-status="{ row }">
        <button
          type="button"
          class="status-badge clickable"
          :class="statusTone((row.status ?? 'SYSTEM_ERROR') as SubmissionStatus)"
          :title="activeTab === 'code' ? '查看代码与详情' : '查看 SQL 与详情'"
          @click="openDetailDrawer(row)"
        >
          {{ STATUS_ABBR[(row.status ?? 'SYSTEM_ERROR') as SubmissionStatus] ?? row.status ?? '—' }}
        </button>
      </template>
      <template #cell-resource="{ row }">
        <span class="mono resource-meta">
          {{ row.timeUsedMs != null ? `${row.timeUsedMs}ms` : '-' }}
          <template v-if="activeTab === 'code'"> / {{ formatMemory(row.memoryUsedKb) }}</template>
        </span>
      </template>
      <template #cell-type="{ row }">
        <span>{{ OBJECTIVE_TYPE_LABELS[row.type ?? ''] ?? row.type ?? '—' }}</span>
      </template>
      <template #cell-correct="{ row }">
        <button
          type="button"
          class="status-badge clickable"
          :class="row.correct === 1 ? 'ac' : 'wa'"
          title="查看作答详情"
          @click="openDetailDrawer(row)"
        >
          {{ row.correct === 1 ? '答对 AC' : '答错 WA' }}
        </button>
      </template>
      <template #cell-createdAt="{ row }">
        <span class="time mono">{{ formatTime(row.createdAt) }}</span>
      </template>
      <template #actions="{ row }">
        <button class="btn btn-outline btn-sm action-btn" type="button" @click="openDetailDrawer(row)">
          查看详情
        </button>
      </template>
    </DataTable>

    <Pagination
      :key="'p-' + activeTab"
      v-model:page="activePage"
      :size="size"
      :total="currentTotal"
    />

    <!-- 右侧详情抽屉 -->
    <Teleport to="body">
      <div
        v-if="detail"
        class="drawer-backdrop"
        @click.self="closeDetailDrawer"
      >
        <aside
          class="detail-drawer"
          role="dialog"
          aria-modal="true"
          aria-labelledby="submission-drawer-title"
        >
          <div class="drawer-header">
            <div class="drawer-title-group">
              <span class="mono drawer-id">#{{ detail.row.submissionId }}</span>
              <h2 id="submission-drawer-title" class="drawer-title">
                {{ detail.row.questionTitle || (detail.kind === 'code' ? '编程题提交' : detail.kind === 'database' ? '数据库题提交' : '客观题作答详情') }}
              </h2>
              <span
                v-if="detail.kind === 'objective'"
                class="status-badge"
                :class="detail.row.correct === 1 ? 'ac' : 'wa'"
              >
                {{ detail.row.correct === 1 ? '答对 AC' : '答错 WA' }}
              </span>
              <span
                v-else
                class="status-badge"
                :class="statusTone(detail.row.status as SubmissionStatus)"
              >
                {{ STATUS_ABBR[detail.row.status as SubmissionStatus] ?? detail.row.status }}
              </span>
            </div>
            <button
              ref="drawerCloseBtn"
              class="drawer-close-btn"
              type="button"
              aria-label="关闭详情抽屉"
              @click="closeDetailDrawer"
            >
              ✕
            </button>
          </div>

          <div class="drawer-body">
            <dl class="drawer-meta-grid">
              <div class="meta-item">
                <dt>提交用户</dt>
                <dd>
                  <strong>{{ detail.row.username }}</strong>
                  <span class="mono muted-id">UID {{ detail.row.userId }}</span>
                </dd>
              </div>
              <div class="meta-item">
                <dt>关联题目</dt>
                <dd class="mono">#{{ detail.row.questionId }}</dd>
              </div>
              <div class="meta-item">
                <dt>提交时间</dt>
                <dd class="mono">{{ formatTime(detail.row.createdAt) }}</dd>
              </div>

              <template v-if="detail.kind === 'code'">
                <div class="meta-item">
                  <dt>语言</dt>
                  <dd>{{ LANGUAGE_LABELS[detail.row.language] ?? detail.row.language }}</dd>
                </div>
                <div class="meta-item">
                  <dt>耗时</dt>
                  <dd class="mono">{{ detail.row.timeUsedMs != null ? `${detail.row.timeUsedMs}ms` : '-' }}</dd>
                </div>
                <div class="meta-item">
                  <dt>内存</dt>
                  <dd class="mono">{{ formatMemory(detail.row.memoryUsedKb) }}</dd>
                </div>
              </template>

              <template v-else-if="detail.kind === 'database'">
                <div class="meta-item">
                  <dt>耗时</dt>
                  <dd class="mono">{{ detail.row.timeUsedMs != null ? `${detail.row.timeUsedMs}ms` : '-' }}</dd>
                </div>
              </template>

              <template v-else>
                <div class="meta-item">
                  <dt>题型</dt>
                  <dd>{{ OBJECTIVE_TYPE_LABELS[detail.row.type] ?? detail.row.type }}</dd>
                </div>
              </template>
            </dl>

            <template v-if="detail.kind === 'code'">
              <div class="section-bar">
                <span class="section-title">提交代码</span>
                <button
                  type="button"
                  class="btn btn-ghost btn-sm"
                  @click="copyText(parseCode(detail.row.codeContent))"
                >
                  {{ copyTip ? '已复制' : '复制代码' }}
                </button>
              </div>
              <pre class="code-pre mono"><code>{{ parseCode(detail.row.codeContent) }}</code></pre>
            </template>

            <template v-else-if="detail.kind === 'database'">
              <div class="section-bar">
                <span class="section-title">提交 SQL</span>
                <button
                  type="button"
                  class="btn btn-ghost btn-sm"
                  @click="copyText(detail.row.sqlContent)"
                >
                  {{ copyTip ? '已复制' : '复制 SQL' }}
                </button>
              </div>
              <pre class="code-pre mono"><code>{{ detail.row.sqlContent }}</code></pre>
            </template>

            <template v-else>
              <div class="obj-detail-section">
                <div class="obj-detail-label">题目内容</div>
                <div class="obj-detail-content">{{ detail.row.questionTitle || '—' }}</div>
              </div>
              <div class="obj-detail-grid">
                <div
                  class="obj-detail-box"
                  :class="detail.row.correct === 1 ? 'box-correct' : 'box-wrong'"
                >
                  <div class="box-label">学生作答</div>
                  <div class="box-content mono">{{ detail.row.answer || '（未作答）' }}</div>
                </div>
                <div class="obj-detail-box std-ans">
                  <div class="box-label">标准答案</div>
                  <div class="box-content mono">{{ detail.row.correctAnswer || '—' }}</div>
                </div>
              </div>
            </template>
          </div>

          <div class="drawer-footer">
            <span class="drawer-foot-note">重判将按题目最新数据重新评测并更新本条记录</span>
            <div class="drawer-foot-actions">
              <button class="btn btn-ghost btn-sm" type="button" @click="closeDetailDrawer">
                关闭
              </button>
              <button
                class="btn btn-primary btn-sm rejudge-btn"
                type="button"
                :disabled="rejudging"
                @click="rejudgeDetail"
              >
                {{ rejudging ? '重判中…' : '重判' }}
              </button>
            </div>
          </div>
        </aside>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.admin {
  padding-top: 8px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin: 0 0 18px;
}

.page-header .page-title {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink);
}

.page-header p {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13.5px;
}

.header-actions {
  flex-shrink: 0;
}

.toolbar-card {
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: var(--kece-card-radius, 0px);
  padding: 14px 16px;
  margin-bottom: 16px;
  display: grid;
  gap: 12px;
}

.tabs {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-bottom: 1px solid var(--kece-border, var(--line));
  padding-bottom: 10px;
}

.tab {
  padding: 6px 16px;
  border-radius: 0;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ink-secondary);
  border: 1px solid transparent;
  background: transparent;
  transition: color 0.15s ease, background 0.15s ease, border-color 0.15s ease;
}

.tab:hover {
  color: var(--ink);
  background: var(--kece-surface-muted, #f8fafc);
}

.tab.active {
  background: var(--kece-accent-soft, #eef6f4);
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
  font-weight: 600;
}

.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 16px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  color: var(--ink-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.filter select {
  width: 130px;
}

.id-input {
  width: 136px;
}

.filter-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.submit-error {
  margin: 0 0 14px;
  padding: 10px 14px;
  border: 1px solid var(--kece-danger, #c4314b);
  background: var(--kece-danger-soft, #fdf2f4);
  color: var(--kece-danger, #c4314b);
  font-size: 13px;
}

.cell-primary,
.cell-question {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.sub-id {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-tertiary);
}

.sub-user {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}

.q-title {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink);
  font-weight: 500;
}

.q-id {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.lang-tag {
  font-size: 13px;
  color: var(--ink-secondary);
}

.resource-meta {
  font-size: 13px;
  color: var(--ink-secondary);
}

.time {
  color: var(--ink-tertiary);
  font-size: 12.5px;
}

.status-badge {
  display: inline-block;
  min-width: 48px;
  padding: 3px 10px;
  border: none;
  border-radius: 0;
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
  background: #111827;
}

.status-badge.mle {
  background: #d97706;
}

.status-badge.re {
  background: #7c3aed;
}

.status-badge.ce,
.status-badge.se {
  background: #475569;
}

.status-badge.run {
  background: #6b7280;
}

.clickable {
  cursor: pointer;
  transition: opacity 0.15s ease;
}

.clickable:hover {
  opacity: 0.88;
}

.action-btn {
  padding: 4px 10px;
  font-size: 12.5px;
  border-color: var(--kece-border, var(--line));
}

.action-btn:hover {
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
}

/* 右侧详情抽屉 */
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.35);
  display: flex;
  justify-content: flex-end;
  z-index: 9999;
}

.detail-drawer {
  width: min(640px, 100vw);
  height: 100dvh;
  background: #ffffff;
  border-left: 1px solid var(--kece-border, var(--line));
  border-radius: 0;
  display: flex;
  flex-direction: column;
  box-shadow: -8px 0 32px rgba(15, 23, 42, 0.12);
}

.drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--kece-border, var(--line));
  background: var(--kece-surface-muted, #f8fafc);
}

.drawer-title-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
  min-width: 0;
}

.drawer-id {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-secondary);
}

.drawer-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  word-break: break-word;
}

.drawer-close-btn {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--kece-border, var(--line));
  background: #ffffff;
  color: var(--ink-secondary);
  font-size: 14px;
  cursor: pointer;
  flex-shrink: 0;
}

.drawer-close-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.drawer-meta-grid {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px 16px;
  padding: 12px 14px;
  border: 1px solid var(--kece-border, var(--line));
  background: var(--kece-surface-muted, #f8fafc);
}

.meta-item dt {
  font-size: 11.5px;
  color: var(--ink-tertiary);
  margin-bottom: 2px;
}

.meta-item dd {
  margin: 0;
  font-size: 13px;
  color: var(--ink);
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}

.muted-id {
  font-size: 11.5px;
  color: var(--ink-tertiary);
}

.section-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
}

.code-pre {
  margin: 0;
  padding: 14px 16px;
  background: #f6f8fa;
  color: #1f2328;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: 0;
  font-size: 13px;
  line-height: 1.6;
  max-height: 520px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.obj-detail-section {
  padding: 12px 14px;
  background: var(--kece-surface-muted, #f8fafc);
  border-radius: 0;
  border: 1px solid var(--kece-border, var(--line));
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
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.obj-detail-box {
  padding: 12px 14px;
  border-radius: 0;
  border: 1px solid var(--kece-border, var(--line));
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
  word-break: break-word;
}

.box-correct {
  background: var(--kece-success-soft, #eefbf3);
  border-color: #86efac;
  color: var(--kece-success, #16794f);
}

.box-wrong {
  background: var(--kece-danger-soft, #fdf2f4);
  border-color: #fca5a5;
  color: var(--kece-danger, #c4314b);
}

.std-ans {
  background: #f8fafc;
  border-color: #cbd5e1;
  color: #1e293b;
}

.drawer-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--kece-border, var(--line));
  background: var(--kece-surface-muted, #f8fafc);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.drawer-foot-note {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.drawer-foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rejudge-btn {
  background: var(--kece-accent, #137969);
  border-color: var(--kece-accent, #137969);
  color: #ffffff;
}

.rejudge-btn:hover:not(:disabled) {
  background: var(--kece-accent-hover, #0e6053);
  border-color: var(--kece-accent-hover, #0e6053);
}

@media (max-width: 720px) {
  .drawer-meta-grid,
  .obj-detail-grid {
    grid-template-columns: 1fr;
  }
  .filter-actions {
    margin-left: 0;
  }
}
</style>
