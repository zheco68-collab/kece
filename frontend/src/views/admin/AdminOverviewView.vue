<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { adminApi, type CodeSubmissionRow } from '@/api/admin';
import type { ActivityTab, OverviewActivity } from '@/api/adminOverview';
import AdminSubmissionTrend from '@/components/admin/AdminSubmissionTrend.vue';
import { useAdminOverview } from '@/composables/useAdminOverview';
import { useJudgeCluster } from '@/features/judging/composables/useJudgeCluster';
import type { SubmissionStatus } from '@/types';
import { LANGUAGE_LABELS, STATUS_ABBR, statusTone } from '@/utils/submission';

const {
  isAdmin,
  activities,
  reloadActivities,
  catalog,
  catalogTotal,
  range,
  trend,
  trend7,
  loadTrend,
  changeRange,
  refresh: refreshOverview,
  refreshing: overviewRefreshing,
} = useAdminOverview();

const {
  summary: clusterSummary,
  loading: clusterLoading,
  initialLoading: clusterInitialLoading,
  error: clusterError,
  isStale: clusterStale,
  isUnavailable: clusterUnavailable,
  readyPools,
  totalPools,
  pools,
  refresh: refreshCluster,
} = useJudgeCluster();

// ---- 最近代码提交（真实数据）----
const recentRows = ref<CodeSubmissionRow[]>([]);
const recentLoading = ref(false);
const recentError = ref('');

async function loadRecentSubmissions() {
  recentLoading.value = true;
  recentError.value = '';
  try {
    const res = await adminApi.listCodeSubmissions({ page: 1, size: 6 });
    recentRows.value = res.list;
  } catch (e) {
    recentError.value = e instanceof Error ? e.message : '读取最近提交失败';
  } finally {
    recentLoading.value = false;
  }
}

const isRefreshing = computed(
  () => overviewRefreshing.value || clusterLoading.value || recentLoading.value,
);

function refreshAll() {
  refreshOverview();
  void refreshCluster(true);
  void loadRecentSubmissions();
}

const tabs: { key: ActivityTab; label: string; empty: string }[] = [
  { key: 'active', label: '进行中', empty: '当前没有正在进行的考务场次。' },
  { key: 'upcoming', label: '待开考', empty: '未来 7 天暂无待开考的公开考试。' },
  { key: 'ended', label: '已结束', empty: '最近 7 天暂无已结束的考务记录。' },
];
const tab = ref<ActivityTab>('active');

const shortcuts = computed(() => {
  const base = [
    { name: 'admin-submissions', label: '提交监控' },
    { name: 'admin-question-new', label: '新建编程题' },
    { name: 'admin-practice-new', label: '发起课练' },
  ];
  if (isAdmin.value) {
    base.splice(1, 0, { name: 'admin-judgers', label: '评测机管理' });
  }
  return base;
});

const timeZone = 'Asia/Shanghai';
const timeFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone,
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});
const dayFormat = new Intl.DateTimeFormat('zh-CN', { timeZone, month: 'long', day: 'numeric' });
const number = new Intl.NumberFormat('zh-CN');

const snapshot = computed(() => activities.value.data);
const activeTabMeta = computed(() => tabs.find((item) => item.key === tab.value));
const activeList = computed(() => snapshot.value?.[tab.value].items ?? []);
const catalogRows = computed(() => catalog.map((item) => ({ ...item, result: item.state.value })));
const catalogFailed = computed(() => catalog.some((item) => item.state.value.failed));

const weeklySubmissions = computed<number | null>(() => {
  if (trend7.value.failed || !trend7.value.data) return null;
  return trend7.value.data.buckets.reduce((sum, b) => sum + b.algoCount + b.dbCount, 0);
});

const totalActiveJobs = computed(() => pools.value.reduce((sum, p) => sum + p.activeJobs, 0));
const totalPendingJobs = computed(() => pools.value.reduce((sum, p) => sum + p.pendingJobs, 0));

const clusterHealth = computed(() => {
  if (clusterInitialLoading.value) {
    return { label: '读取中', tone: 'neutral' as const };
  }
  if (clusterUnavailable.value) {
    return { label: '不可达', tone: 'danger' as const };
  }
  if (clusterStale.value || clusterError.value) {
    return { label: '数据过期', tone: 'warning' as const };
  }
  if (readyPools.value === totalPools.value && totalPools.value > 0) {
    return { label: '全部就绪', tone: 'ok' as const };
  }
  if (readyPools.value > 0) {
    return { label: '部分降级', tone: 'warning' as const };
  }
  return { label: '无就绪池', tone: 'danger' as const };
});

function dateTime(value: string | null) {
  if (!value) return '未设时间';
  const timestamp = new Date(value);
  const day = dayFormat.format(timestamp);
  const snapshotDay = snapshot.value ? dayFormat.format(new Date(snapshot.value.asOf)) : '';
  const time = timeFormat.format(timestamp);
  return day === snapshotDay ? time : `${day} ${time}`;
}

function fullDateTime(value: string) {
  const timestamp = new Date(value);
  return `${dayFormat.format(timestamp)} ${timeFormat.format(timestamp)}`;
}

function formatSubmitTime(iso: string) {
  return iso ? iso.slice(5, 19).replace('T', ' ') : '—';
}

function formatMemory(kb: number | null | undefined): string {
  if (kb == null) return '-';
  if (kb < 1024) return `${kb}KB`;
  const mb = kb / 1024;
  return `${mb.toFixed(1).replace(/\.0$/, '')}MB`;
}

function rowTiming(row: OverviewActivity, section: ActivityTab) {
  if (section === 'upcoming') return `${dateTime(row.startAt)} 开考`;
  if (row.endAt) return `${dateTime(row.endAt)} ${section === 'ended' ? '已结束' : '结束'}`;
  return row.kind === 'PRACTICE' ? '不限时' : '未设窗口结束时间';
}

function action(row: OverviewActivity, section: ActivityTab) {
  if (row.kind === 'PRACTICE') return { name: 'admin-practice-overview', label: '考务总览' };
  if (section === 'upcoming') return { name: 'admin-exam-edit', label: '编辑试卷' };
  return { name: 'admin-exam-papers', label: '成绩与查卷' };
}

function poolStateLabel(state: string): string {
  switch (state) {
    case 'READY':
      return '就绪';
    case 'BUSY':
      return '繁忙';
    case 'OFFLINE':
      return '离线';
    case 'DRAINING':
      return '排空';
    case 'PENDING_SWITCH':
      return '切换中';
    default:
      return state;
  }
}

function tabId(key: ActivityTab) {
  return `overview-tab-${key}`;
}

function selectTab(key: ActivityTab, moveFocus = false) {
  tab.value = key;
  if (moveFocus) void nextTick(() => document.getElementById(tabId(key))?.focus());
}

function navigateTabs(event: KeyboardEvent, index: number) {
  let target: number;
  switch (event.key) {
    case 'ArrowLeft':
      target = (index - 1 + tabs.length) % tabs.length;
      break;
    case 'ArrowRight':
      target = (index + 1) % tabs.length;
      break;
    case 'Home':
      target = 0;
      break;
    case 'End':
      target = tabs.length - 1;
      break;
    default:
      return;
  }
  event.preventDefault();
  const item = tabs[target];
  if (item) selectTab(item.key, true);
}

onMounted(() => {
  void loadRecentSubmissions();
});
</script>

<template>
  <div class="overview">
    <header class="overview-header">
      <div class="header-copy">
        <p class="overview-kicker">
          教学工作台 · {{ isAdmin ? '全站管理范围' : '我的管理范围' }}
          <template v-if="snapshot">
            · {{ activities.failed ? '上次更新' : '更新于' }}
            <time :datetime="snapshot.asOf">{{ fullDateTime(snapshot.asOf) }}</time>
          </template>
        </p>
        <h1>概况</h1>
        <p class="overview-description">统筹进行中考务、评测节点运行与近期提交流水。</p>
      </div>
      <div class="header-tools">
        <RouterLink class="btn btn-primary btn-sm primary-cta" :to="{ name: 'admin-exam-new' }">
          新建试卷
        </RouterLink>
        <button
          class="btn btn-outline btn-sm refresh-btn"
          type="button"
          :disabled="isRefreshing"
          @click="refreshAll"
        >
          {{ isRefreshing ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <!-- 顶部 4 张核心概览指标卡 -->
    <section class="metrics-grid" aria-label="核心工作台指标">
      <article class="metric-card">
        <span class="metric-label">进行中考务</span>
        <strong class="metric-value mono">
          {{ snapshot ? number.format(snapshot.active.total) : activities.failed ? '暂缺' : '—' }}
        </strong>
        <span class="metric-sub">当前正在进行的考试与课练</span>
      </article>
      <article class="metric-card">
        <span class="metric-label">未来 7 天待开考</span>
        <strong class="metric-value mono">
          {{ snapshot ? number.format(snapshot.upcoming.total) : activities.failed ? '暂缺' : '—' }}
        </strong>
        <span class="metric-sub">已定时的待开考公开场次</span>
      </article>
      <article class="metric-card">
        <span class="metric-label">近 7 天提交</span>
        <strong class="metric-value mono">
          {{
            weeklySubmissions !== null
              ? number.format(weeklySubmissions)
              : trend7.failed
                ? '暂缺'
                : '—'
          }}
        </strong>
        <span class="metric-sub">近 7 个自然日代码与 SQL 提交</span>
      </article>
      <article class="metric-card">
        <span class="metric-label">题库总量</span>
        <strong class="metric-value mono">
          {{
            catalogTotal !== null
              ? number.format(catalogTotal)
              : catalogFailed
                ? '暂缺'
                : '—'
          }}
        </strong>
        <span class="metric-sub">编程、数据库与客观题合计</span>
      </article>
    </section>

    <!-- 主体双栏区：考务安排 + 评测运行 -->
    <div class="overview-grid">
      <section
        class="panel exam-panel"
        aria-labelledby="overview-activities-heading"
        :aria-busy="activities.pending"
      >
        <header class="panel-heading">
          <div>
            <h2 id="overview-activities-heading">考务安排</h2>
          </div>
          <div class="panel-links">
            <RouterLink :to="{ name: 'admin-exams' }">考试管理 →</RouterLink>
            <RouterLink :to="{ name: 'admin-practices' }">课练管理 →</RouterLink>
          </div>
        </header>

        <div class="activity-tabs" role="tablist" aria-label="考务状态">
          <button
            v-for="(item, index) in tabs"
            :id="tabId(item.key)"
            :key="item.key"
            type="button"
            role="tab"
            aria-controls="overview-activity-panel"
            :aria-selected="tab === item.key"
            :tabindex="tab === item.key ? 0 : -1"
            @click="selectTab(item.key)"
            @keydown="navigateTabs($event, index)"
          >
            <span>{{ item.label }}</span>
            <span v-if="snapshot" class="tab-badge mono">{{ number.format(snapshot[item.key].total) }}</span>
          </button>
        </div>

        <div v-if="activities.failed" class="panel-notice" role="status">
          <span>{{ snapshot ? '考务暂未刷新，显示上次结果。' : '考务列表读取失败。' }}</span>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="activities.pending" @click="reloadActivities">
            重试
          </button>
        </div>

        <div
          id="overview-activity-panel"
          class="panel-body"
          role="tabpanel"
          :aria-labelledby="tabId(tab)"
          tabindex="0"
        >
          <ul v-if="activeList.length" class="activity-list">
            <li
              v-for="row in activeList"
              :key="`${row.kind}-${row.id}`"
              class="activity-row"
              :class="{ 'is-live': tab === 'active' }"
            >
              <div class="activity-main">
                <span class="activity-kind">{{ row.kind === 'EXAM' ? '考试' : '课练' }}</span>
                <strong class="activity-title">{{ row.title }}</strong>
                <span v-if="row.kind === 'PRACTICE' && row.memberCount !== null" class="member-tag mono">
                  {{ number.format(row.memberCount) }} 人
                </span>
              </div>
              <div class="activity-side">
                <span class="activity-time mono">{{ rowTiming(row, tab) }}</span>
                <RouterLink
                  class="activity-link"
                  :to="{ name: action(row, tab).name, params: { id: row.id } }"
                >
                  {{ action(row, tab).label }} →
                </RouterLink>
              </div>
            </li>
          </ul>

          <div v-else class="compact-empty">
            <p class="empty-msg">
              <template v-if="!snapshot">
                {{ activities.failed ? '暂无可展示的考务场次。' : '正在加载考务数据…' }}
              </template>
              <template v-else>{{ activeTabMeta?.empty }}</template>
            </p>
            <div v-if="snapshot" class="empty-actions">
              <RouterLink class="btn btn-primary btn-sm primary-cta" :to="{ name: 'admin-exam-new' }">
                新建试卷
              </RouterLink>
              <RouterLink class="btn btn-outline btn-sm" :to="{ name: 'admin-practice-new' }">
                发起课练
              </RouterLink>
            </div>
          </div>
        </div>

        <footer class="panel-footer">
          <span class="foot-hint">显示各状态最近场次</span>
          <RouterLink class="foot-link" to="/exams">前往前台考试大厅 ↗</RouterLink>
        </footer>
      </section>

      <aside class="panel judge-panel" aria-labelledby="overview-judge-heading">
        <header class="panel-heading">
          <h2 id="overview-judge-heading">评测运行</h2>
          <span class="health-badge" :class="`tone-${clusterHealth.tone}`">
            {{ clusterHealth.label }}
          </span>
        </header>

        <div class="judge-body">
          <div v-if="clusterInitialLoading" class="judge-state">正在读取评测集群摘要…</div>

          <div v-else-if="clusterUnavailable" class="judge-error" role="alert">
            <p>暂时无法连接评测集群摘要：{{ clusterError }}</p>
            <button class="btn btn-outline btn-sm" type="button" @click="refreshCluster(true)">
              重试读取
            </button>
          </div>

          <template v-else-if="clusterSummary">
            <div class="judge-stats">
              <div class="jstat">
                <span>就绪专池</span>
                <strong class="mono">{{ readyPools }} / {{ totalPools }}</strong>
              </div>
              <div class="jstat">
                <span>执行中任务</span>
                <strong class="mono">{{ totalActiveJobs }}</strong>
              </div>
              <div class="jstat">
                <span>排队任务</span>
                <strong class="mono">{{ totalPendingJobs }}</strong>
              </div>
            </div>

            <ul class="pool-list">
              <li v-for="pool in pools" :key="pool.pool" class="pool-row">
                <div class="pool-name">
                  <strong>{{ pool.name }}</strong>
                  <span class="mono pool-nodes">{{ pool.readyNodes }}/{{ pool.totalNodes }} 节点</span>
                </div>
                <div class="pool-meta">
                  <span v-if="pool.activeJobs + pool.pendingJobs > 0" class="mono pool-jobs">
                    {{ pool.activeJobs }} 跑 / {{ pool.pendingJobs }} 等
                  </span>
                  <span class="pool-pill" :class="`state-${pool.state.toLowerCase()}`">
                    {{ poolStateLabel(pool.state) }}
                  </span>
                </div>
              </li>
            </ul>
          </template>
        </div>

        <div class="quick-links-bar">
          <span class="quick-label">快捷操作</span>
          <div class="quick-grid">
            <RouterLink
              v-for="item in shortcuts"
              :key="item.name"
              class="quick-chip"
              :to="{ name: item.name }"
            >
              {{ item.label }}
            </RouterLink>
          </div>
        </div>
      </aside>
    </div>

    <!-- 通栏：最近提交流水（严格不展示得分） -->
    <section class="panel recent-panel" aria-labelledby="overview-recent-heading">
      <header class="panel-heading">
        <div>
          <h2 id="overview-recent-heading">最近提交</h2>
        </div>
        <RouterLink class="panel-link-primary" :to="{ name: 'admin-submissions' }">
          前往提交监控 →
        </RouterLink>
      </header>

      <div v-if="recentError" class="panel-notice" role="status">
        <span>{{ recentError }}</span>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="recentLoading" @click="loadRecentSubmissions">
          重试
        </button>
      </div>

      <div v-else-if="recentLoading && recentRows.length === 0" class="judge-state">
        正在加载近期代码提交…
      </div>

      <div v-else-if="recentRows.length === 0" class="compact-empty">
        <p class="empty-msg">暂无最近提交记录。</p>
      </div>

      <div v-else class="recent-table-wrap">
        <table class="recent-table">
          <thead>
            <tr>
              <th>提交 / 用户</th>
              <th>题目</th>
              <th>语言</th>
              <th>状态</th>
              <th>耗时 / 内存</th>
              <th>提交时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in recentRows" :key="row.submissionId">
              <td>
                <span class="mono row-id">#{{ row.submissionId }}</span>
                <span class="row-user">{{ row.username }}</span>
              </td>
              <td class="row-question" :title="row.questionTitle || undefined">
                {{ row.questionTitle || `题目 #${row.questionId}` }}
              </td>
              <td>{{ LANGUAGE_LABELS[row.language] ?? row.language }}</td>
              <td>
                <span class="status-pill" :class="statusTone(row.status as SubmissionStatus)">
                  {{ STATUS_ABBR[row.status as SubmissionStatus] ?? row.status }}
                </span>
              </td>
              <td class="mono muted-cell">
                {{ row.timeUsedMs != null ? `${row.timeUsedMs}ms` : '-' }} / {{ formatMemory(row.memoryUsedKb) }}
              </td>
              <td class="mono muted-cell">{{ formatSubmitTime(row.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 紧凑题库资产条 + 提交趋势 -->
    <div class="bottom-grid">
      <section class="panel catalog-panel" aria-labelledby="overview-catalog-heading">
        <header class="panel-heading">
          <h2 id="overview-catalog-heading">题库资产</h2>
          <span class="subtle">{{ isAdmin ? '全站题库' : '我的题库' }}</span>
        </header>
        <div class="catalog-strip">
          <div v-for="item in catalogRows" :key="item.route" class="catalog-cell">
            <RouterLink class="catalog-link" :to="{ name: item.route }">
              <span class="catalog-name">{{ item.title }}</span>
              <strong v-if="item.result.data !== null" class="catalog-num mono">
                {{ number.format(item.result.data) }}
              </strong>
              <span v-else class="catalog-num subtle">
                {{ item.result.failed ? '暂缺' : '…' }}
              </span>
            </RouterLink>
            <button
              v-if="item.result.failed"
              class="retry-tiny"
              type="button"
              :disabled="item.result.pending"
              @click="item.load"
            >
              重试
            </button>
          </div>
        </div>
      </section>

      <AdminSubmissionTrend
        :range="range"
        :data="trend.data"
        :pending="trend.pending"
        :failed="trend.failed"
        @change-range="changeRange"
        @retry="loadTrend"
      />
    </div>

    <footer class="overview-scope-footer">
      <span>范围：{{ isAdmin ? '全站管理员视角' : '出题人本人创建范围' }}</span>
      <span>时间口径：北京时间（Asia/Shanghai / UTC+8）</span>
    </footer>
  </div>
</template>

<style scoped>
.overview {
  width: 100%;
  max-width: 1460px;
  margin-inline: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.overview-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.overview-kicker {
  margin: 0 0 4px;
  font-size: 12.5px;
  color: var(--ink-tertiary);
}

.overview-header h1 {
  margin: 0 0 4px;
  font-size: 26px;
  font-weight: 700;
  color: var(--ink);
}

.overview-description {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-secondary);
}

.header-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.primary-cta {
  background: var(--kece-accent, #137969);
  border-color: var(--kece-accent, #137969);
  color: #ffffff;
}

.primary-cta:hover {
  background: var(--kece-accent-hover, #0e6053);
  border-color: var(--kece-accent-hover, #0e6053);
}

/* 顶部 4 指标卡 */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: var(--kece-card-radius, 0px);
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.metric-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-secondary);
}

.metric-value {
  font-size: 28px;
  line-height: 1.15;
  font-weight: 700;
  color: var(--ink);
}

.metric-sub {
  font-size: 12px;
  color: var(--ink-tertiary);
}

/* 主体双栏 */
.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(300px, 1fr);
  gap: 16px;
  align-items: stretch;
}

.panel {
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: var(--kece-card-radius, 0px);
  display: flex;
  flex-direction: column;
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--kece-border, var(--line));
}

.panel-heading h2 {
  margin: 0;
  font-size: 15.5px;
  font-weight: 700;
  color: var(--ink);
}

.panel-links {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.panel-links a:hover,
.panel-link-primary:hover,
.foot-link:hover {
  color: var(--kece-accent, #137969);
}

.panel-link-primary {
  font-size: 13px;
  font-weight: 600;
  color: var(--kece-accent-ink, #0f5f53);
}

.activity-tabs {
  display: flex;
  gap: 6px;
  padding: 10px 18px;
  border-bottom: 1px solid #f1f5f9;
  background: var(--kece-surface-muted, #f8fafc);
}

.activity-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-secondary);
  border: 1px solid transparent;
  background: transparent;
  border-radius: 0;
  cursor: pointer;
}

.activity-tabs button[aria-selected='true'] {
  background: #ffffff;
  border-color: var(--kece-border, var(--line));
  color: var(--kece-accent-ink, #0f5f53);
  font-weight: 600;
}

.tab-badge {
  font-size: 11.5px;
  color: var(--ink-tertiary);
}

.panel-notice {
  margin: 12px 18px 0;
  padding: 10px 12px;
  background: var(--kece-danger-soft, #fdf2f4);
  border: 1px solid var(--kece-danger, #c4314b);
  color: var(--kece-danger, #c4314b);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
}

.panel-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.activity-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.activity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid #f1f5f9;
  border-left: 3px solid transparent;
}

.activity-row.is-live {
  border-left-color: var(--kece-accent, #137969);
}

.activity-row:last-child {
  border-bottom: 0;
}

.activity-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.activity-kind {
  padding: 2px 6px;
  font-size: 11px;
  font-weight: 600;
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--kece-border, var(--line));
  color: var(--ink-secondary);
  flex-shrink: 0;
}

.activity-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member-tag {
  font-size: 11.5px;
  color: var(--ink-tertiary);
  flex-shrink: 0;
}

.activity-side {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.activity-time {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.activity-link {
  font-size: 13px;
  font-weight: 600;
  color: var(--kece-accent-ink, #0f5f53);
}

.compact-empty {
  padding: 28px 18px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.empty-msg {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-secondary);
}

.empty-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.panel-footer {
  margin-top: auto;
  padding: 10px 18px;
  border-top: 1px solid #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ink-tertiary);
}

.foot-link {
  font-weight: 600;
  color: var(--ink-secondary);
}

/* 评测运行面板 */
.health-badge {
  padding: 2px 8px;
  font-size: 11.5px;
  font-weight: 600;
  border: 1px solid var(--kece-border, var(--line));
}

.tone-ok {
  background: var(--kece-accent-soft, #eef6f4);
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
}

.tone-warning {
  background: var(--kece-warning-soft, #fff7ed);
  border-color: #fed7aa;
  color: var(--kece-warning, #b25c16);
}

.tone-danger {
  background: var(--kece-danger-soft, #fdf2f4);
  border-color: #fecdd3;
  color: var(--kece-danger, #c4314b);
}

.tone-neutral {
  background: var(--kece-surface-muted, #f8fafc);
  color: var(--ink-secondary);
}

.judge-body {
  padding: 14px 18px;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.judge-state {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--ink-tertiary);
}

.judge-error {
  padding: 14px;
  border: 1px solid var(--kece-danger, #c4314b);
  background: var(--kece-danger-soft, #fdf2f4);
  color: var(--kece-danger, #c4314b);
  font-size: 13px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.judge-error p {
  margin: 0;
}

.judge-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
}

.jstat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.jstat span {
  font-size: 11.5px;
  color: var(--ink-tertiary);
}

.jstat strong {
  font-size: 17px;
  color: var(--ink);
}

.pool-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pool-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
}

.pool-name {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.pool-name strong {
  font-weight: 600;
  color: var(--ink);
}

.pool-nodes,
.pool-jobs {
  font-size: 11.5px;
  color: var(--ink-tertiary);
}

.pool-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pool-pill {
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid var(--kece-border, var(--line));
}

.pool-pill.state-ready {
  background: var(--kece-success-soft, #eefbf3);
  border-color: #a7f3d0;
  color: var(--kece-success, #16794f);
}

.pool-pill.state-busy {
  background: var(--kece-warning-soft, #fff7ed);
  border-color: #fed7aa;
  color: var(--kece-warning, #b25c16);
}

.pool-pill.state-offline {
  background: var(--kece-danger-soft, #fdf2f4);
  border-color: #fecdd3;
  color: var(--kece-danger, #c4314b);
}

.quick-links-bar {
  padding: 12px 18px 14px;
  border-top: 1px solid #f1f5f9;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quick-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-tertiary);
}

.quick-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.quick-chip {
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid var(--kece-border, var(--line));
  background: var(--kece-surface-muted, #f8fafc);
  color: var(--ink-secondary);
}

.quick-chip:hover {
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
  background: var(--kece-accent-soft, #eef6f4);
}

/* 最近提交表 */
.recent-table-wrap {
  overflow-x: auto;
}

.recent-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
}

.recent-table th {
  text-align: left;
  padding: 10px 18px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-tertiary);
  background: var(--kece-surface-muted, #f8fafc);
  border-bottom: 1px solid var(--kece-border, var(--line));
}

.recent-table td {
  padding: 10px 18px;
  border-bottom: 1px solid #f1f5f9;
  color: var(--ink);
}

.recent-table tr:last-child td {
  border-bottom: 0;
}

.row-id {
  font-size: 12px;
  color: var(--ink-tertiary);
  margin-right: 6px;
}

.row-user {
  font-weight: 600;
}

.row-question {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted-cell {
  font-size: 12.5px;
  color: var(--ink-tertiary);
}

.status-pill {
  display: inline-block;
  min-width: 44px;
  padding: 2px 8px;
  color: #ffffff;
  font-size: 11.5px;
  font-weight: 600;
  text-align: center;
}

.status-pill.ac { background: var(--success); }
.status-pill.wa { background: var(--danger); }
.status-pill.tle { background: #111827; }
.status-pill.mle { background: #d97706; }
.status-pill.re { background: #7c3aed; }
.status-pill.ce,
.status-pill.se { background: #475569; }
.status-pill.run { background: #6b7280; }

/* 底部题库资产与趋势 */
.bottom-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.9fr) minmax(0, 1.5fr);
  gap: 16px;
  align-items: start;
}

.subtle {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.catalog-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.catalog-cell {
  border-right: 1px solid #f1f5f9;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}

.catalog-cell:last-child {
  border-right: 0;
}

.catalog-link {
  padding: 18px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.catalog-link:hover {
  background: var(--kece-surface-muted, #f8fafc);
}

.catalog-name {
  font-size: 12.5px;
  color: var(--ink-secondary);
}

.catalog-num {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink);
}

.retry-tiny {
  margin: 0 14px 10px;
  padding: 3px 8px;
  font-size: 12px;
  border: 1px solid var(--kece-border, var(--line));
  background: #ffffff;
  cursor: pointer;
}

.overview-scope-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 4px;
  font-size: 12px;
  color: var(--ink-tertiary);
}

@media (max-width: 1080px) {
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .overview-grid,
  .bottom-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .metrics-grid,
  .catalog-strip {
    grid-template-columns: 1fr;
  }
  .catalog-cell {
    border-right: 0;
    border-bottom: 1px solid #f1f5f9;
  }
}
</style>
