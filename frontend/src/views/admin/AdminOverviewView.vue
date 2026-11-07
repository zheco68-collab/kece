<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { RouterLink } from 'vue-router';
import AdminSubmissionTrend from '@/components/admin/AdminSubmissionTrend.vue';
import { useAdminOverview } from '@/composables/useAdminOverview';
import type { ActivityTab, OverviewActivity } from '@/api/adminOverview';

const {
  isAdmin,
  activities,
  reloadActivities,
  catalog,
  catalogTotal,
  range,
  trend,
  loadTrend,
  changeRange,
  refresh,
  refreshing,
  serverNow,
} = useAdminOverview();
const tabs: { key: ActivityTab; label: string; empty: string }[] = [
  { key: 'active', label: '进行中', empty: '当前没有进行中的考务。' },
  { key: 'upcoming', label: '待开考', empty: '未来 7 天没有待开考的公开考试。' },
  { key: 'ended', label: '已结束', empty: '最近 7 天没有已结束的考务。' },
];
const tab = ref<ActivityTab>('active');
const shortcuts = [
  { route: 'admin-exam-new', label: '新建试卷', icon: 'plus' },
  { route: 'admin-practice-new', label: '发起课练', icon: 'screen' },
  { route: 'admin-question-new', label: '新建编程题', icon: 'code' },
  { route: 'admin-submissions', label: '提交监控', icon: 'monitor' },
];
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
const activeFocus = computed(() => snapshot.value?.active.items[0]);
const nextExam = computed(() => snapshot.value?.upcoming.items[0]);
const focus = computed(() => activeFocus.value ?? nextExam.value);
const focusTab = computed<ActivityTab>(() => (activeFocus.value ? 'active' : 'upcoming'));
const activeTab = computed(() => tabs.find((item) => item.key === tab.value));
const list = computed(() => {
  const items = snapshot.value?.[tab.value].items ?? [];
  const current = focus.value;
  if (tab.value !== focusTab.value || !current) return items;
  return items.filter((row) => row.id !== current.id || row.kind !== current.kind);
});
const catalogRows = computed(() => catalog.map((item) => ({ ...item, result: item.state.value })));

const countdown = computed(() => {
  const item = activeFocus.value;
  const now = serverNow.value;
  if (item?.kind !== 'PRACTICE' || !item.startAt || !item.endAt || now === null) return null;
  const start = Date.parse(item.startAt);
  const end = Date.parse(item.endAt);
  if (end <= start) return null;
  return {
    percent: Math.max(0, Math.min(100, ((now - start) / (end - start)) * 100)),
    elapsed: Math.max(0, Math.floor((Math.min(now, end) - start) / 60_000)),
    remaining: Math.max(0, Math.ceil((end - now) / 60_000)),
  };
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
</script>

<template>
  <div class="overview">
    <header class="overview-header">
      <div>
        <p class="overview-kicker">教学管理工作台</p>
        <h1>概况</h1>
        <p class="overview-description">查看正在进行的考务和接下来的安排。</p>
      </div>
      <div class="overview-tools">
        <span class="scope-label">{{ isAdmin ? '全站管理范围' : '我的管理范围' }}</span>
        <span v-if="snapshot" class="updated">
          {{ activities.failed ? '考务上次更新于' : '考务更新于' }}
          <time :datetime="snapshot.asOf">{{ fullDateTime(snapshot.asOf) }}</time>
        </span>
        <button class="btn btn-ghost refresh" type="button" :disabled="refreshing" @click="refresh">
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" aria-hidden="true">
            <path d="M13.7 6a6 6 0 1 0 .1 3.7M13.7 2.5V6h-3.5" />
          </svg>
          {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <section v-if="focus" class="focus" aria-labelledby="overview-focus-heading">
      <div class="focus-copy">
        <p class="focus-label">考务焦点</p>
        <h2 id="overview-focus-heading">{{ focus.title }}</h2>
        <p class="focus-meta">
          {{ focus.kind === 'PRACTICE' ? '随堂课练' : '考试' }}
          <template v-if="focus.startAt">
            · {{ dateTime(focus.startAt) }} {{ activeFocus ? '开始' : '开考' }}</template
          >
          <template v-if="focus.kind === 'PRACTICE' && !focus.endAt"> · 不限时</template>
        </p>
        <RouterLink
          class="focus-action"
          :to="{ name: action(focus, focusTab).name, params: { id: focus.id } }"
        >
          {{ focus.kind === 'PRACTICE' ? '查看考务总览' : action(focus, focusTab).label }}
        </RouterLink>
      </div>
      <div class="focus-facts">
        <div class="focus-facts-header">
          <span class="focus-label">{{ activeFocus ? '本场详情' : '下一场考试' }}</span>
          <span
            class="focus-status"
            :class="{ 'status-active': activeFocus && !activities.failed }"
          >
            {{ activities.failed ? '上次状态 · ' : '' }}{{ activeFocus ? '进行中' : '待开考' }}
          </span>
        </div>
        <div class="focus-values">
          <div>
            <span>{{ focus.kind === 'PRACTICE' ? '已加入人数' : '开考时间' }}</span>
            <strong v-if="focus.kind === 'PRACTICE' && focus.memberCount !== null">
              {{ number.format(focus.memberCount) }} <small>人</small>
            </strong>
            <strong v-else>{{ dateTime(focus.startAt) }}</strong>
          </div>
          <div>
            <span>{{ focus.kind === 'PRACTICE' ? '预计结束' : '考务窗口结束' }}</span>
            <strong>{{
              focus.endAt
                ? dateTime(focus.endAt)
                : focus.kind === 'PRACTICE'
                  ? '不限时'
                  : '未设时间'
            }}</strong>
          </div>
        </div>
        <div v-if="countdown" class="focus-progress">
          <div
            class="progress-track"
            role="progressbar"
            aria-label="课练时间进度"
            :aria-valuenow="Math.round(countdown.percent)"
          >
            <span :style="{ width: `${countdown.percent}%` }" />
          </div>
          <div class="progress-caption">
            <span>已进行 {{ countdown.elapsed }} 分钟</span>
            <strong>{{
              countdown.remaining ? `剩余 ${countdown.remaining} 分钟` : '已到计划结束时间'
            }}</strong>
          </div>
        </div>
      </div>
    </section>
    <section v-else class="focus-empty" aria-labelledby="overview-focus-heading">
      <p class="overview-kicker">考务焦点</p>
      <template v-if="snapshot">
        <h2 id="overview-focus-heading">
          {{ activities.failed ? '上次更新时没有进行中的考务' : '当前没有进行中的考务' }}
        </h2>
        <p v-if="!activities.failed">未来 7 天内暂无待开考的公开考试。</p>
        <div class="empty-actions">
          <RouterLink class="btn btn-primary" :to="{ name: 'admin-practice-new' }"
            >发起课练</RouterLink
          >
          <RouterLink class="btn btn-ghost" :to="{ name: 'admin-exam-new' }">新建试卷</RouterLink>
        </div>
      </template>
      <h2 v-else id="overview-focus-heading" role="status">
        {{ activities.failed ? '暂时无法读取考务' : '正在加载考务…' }}
      </h2>
    </section>

    <div v-if="activities.failed" class="overview-notice" role="status">
      <span>{{ snapshot ? '考务暂未刷新，显示上次结果。' : '考务加载失败，请重试。' }}</span>
      <button
        class="btn btn-ghost"
        type="button"
        :disabled="activities.pending"
        @click="reloadActivities"
      >
        重试考务
      </button>
    </div>

    <div class="overview-grid">
      <div class="overview-main">
        <section
          class="overview-panel"
          aria-labelledby="overview-activities-heading"
          :aria-busy="activities.pending"
        >
          <header class="panel-heading">
            <h2 id="overview-activities-heading">考务安排</h2>
            <div class="panel-links">
              <RouterLink :to="{ name: 'admin-exams' }"
                >考试管理 <span aria-hidden="true">↗</span></RouterLink
              >
              <RouterLink :to="{ name: 'admin-practices' }"
                >课练管理 <span aria-hidden="true">↗</span></RouterLink
              >
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
              {{ item.label }}
              <span v-if="snapshot">{{ number.format(snapshot[item.key].total) }}</span>
            </button>
          </div>
          <div
            id="overview-activity-panel"
            role="tabpanel"
            :aria-labelledby="tabId(tab)"
            tabindex="0"
          >
            <template v-if="list.length">
              <div class="activity-row activity-labels" aria-hidden="true">
                <span>场次</span><span>时间</span><span>操作</span>
              </div>
              <ul class="activity-list">
                <li v-for="row in list" :key="`${row.kind}-${row.id}`" class="activity-row">
                  <div class="activity-title">
                    <span class="activity-kind">{{ row.kind === 'EXAM' ? '考试' : '课练' }}</span>
                    <strong>{{ row.title }}</strong>
                  </div>
                  <span class="activity-time">{{ rowTiming(row, tab) }}</span>
                  <RouterLink
                    class="activity-link"
                    :to="{ name: action(row, tab).name, params: { id: row.id } }"
                    :aria-label="`${action(row, tab).label} · ${row.title}`"
                  >
                    {{ action(row, tab).label }} <span aria-hidden="true">→</span>
                  </RouterLink>
                </li>
              </ul>
            </template>
            <p v-else class="panel-empty">
              <template v-if="!snapshot">{{
                activities.failed ? '暂无可显示的考务数据。' : '正在加载场次…'
              }}</template>
              <template v-else-if="snapshot[tab].total && focus && tab === focusTab"
                >这一档的场次已在上方置顶。</template
              >
              <template v-else>{{ activeTab?.empty }}</template>
            </p>
            <footer v-if="snapshot && list.length" class="activity-foot">
              <span v-if="focus && tab === focusTab">焦点场次已在上方置顶。</span>
              <span v-else-if="tab === 'upcoming'">按开考时间排序 · 未来 7 天</span>
              <span v-else-if="tab === 'ended'">按结束时间倒序 · 最近 7 天</span>
              <span v-else>按预计结束时间排序</span>
              <span v-if="snapshot[tab].total > 5">显示部分场次</span>
            </footer>
          </div>
        </section>

        <button
          v-if="activeFocus && nextExam && tab !== 'upcoming'"
          class="next-exam"
          type="button"
          @click="selectTab('upcoming', true)"
        >
          <span>
            <small>下一场考试</small>
            <strong>{{ dateTime(nextExam.startAt) }}</strong>
          </span>
          <strong class="next-exam-title">{{ nextExam.title }}</strong>
          <span class="next-exam-action">查看安排 <span aria-hidden="true">→</span></span>
        </button>

        <AdminSubmissionTrend
          :range="range"
          :data="trend.data"
          :pending="trend.pending"
          :failed="trend.failed"
          @change-range="changeRange"
          @retry="loadTrend"
        />
      </div>

      <aside class="overview-aside" aria-label="快捷操作和题库">
        <section class="overview-panel side-panel" aria-labelledby="overview-shortcuts-heading">
          <header class="panel-heading"><h2 id="overview-shortcuts-heading">快捷操作</h2></header>
          <ul class="side-list">
            <li v-for="shortcut in shortcuts" :key="shortcut.route">
              <RouterLink class="side-link" :to="{ name: shortcut.route }">
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" aria-hidden="true">
                  <path v-if="shortcut.icon === 'plus'" d="M8 2v12M2 8h12" />
                  <path v-else-if="shortcut.icon === 'screen'" d="M2 2h12v9H2zM8 11v3m-3 0h6" />
                  <path v-else-if="shortcut.icon === 'code'" d="m5 4-4 4 4 4m6-8 4 4-4 4" />
                  <path v-else d="M1 9h3l2-5 3 9 2-5h4" />
                </svg>
                <span>{{ shortcut.label }}</span>
                <span class="side-arrow" aria-hidden="true">→</span>
              </RouterLink>
            </li>
          </ul>
        </section>

        <section class="overview-panel side-panel" aria-labelledby="overview-catalog-heading">
          <header class="panel-heading">
            <h2 id="overview-catalog-heading">题库资产</h2>
            <span class="subtle">{{ isAdmin ? '全站题库' : '我的题库' }}</span>
          </header>
          <ul class="side-list">
            <li v-for="item in catalogRows" :key="item.route" class="catalog-row">
              <RouterLink class="side-link" :to="{ name: item.route }">
                <span>{{ item.title }}</span>
                <strong v-if="item.result.data !== null" class="catalog-number">{{
                  number.format(item.result.data)
                }}</strong>
                <span v-else class="catalog-number subtle">{{
                  item.result.failed ? '暂缺' : '加载中…'
                }}</span>
                <span aria-hidden="true">→</span>
              </RouterLink>
              <div v-if="item.result.failed" class="catalog-notice" role="status">
                <span v-if="item.result.loadedAt !== null"
                  >上次更新 {{ fullDateTime(new Date(item.result.loadedAt).toISOString()) }}</span
                >
                <span v-else>{{ item.title }}数量暂未加载</span>
                <button type="button" :disabled="item.result.pending" @click="item.load">
                  重试
                </button>
              </div>
            </li>
          </ul>
          <footer v-if="catalogTotal !== null" class="catalog-total">
            <span>三类题目合计</span><strong>{{ number.format(catalogTotal) }} 道</strong>
          </footer>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.overview {
  --overview-ink: #202329;
  --overview-text-on-dark: #f3f5f7;
  --overview-muted-on-dark: #c3cbd5;
  width: 100%;
  max-width: 1500px;
  margin-inline: auto;
  container-type: inline-size;
}
.overview :is(h1, h2, p) {
  margin: 0;
}
.overview :is(a, button):focus-visible {
  outline: 2px solid var(--ink);
  outline-offset: 2px;
}
.overview-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px 20px;
  margin-bottom: 22px;
}
.overview-header h1 {
  margin-block: 3px;
  font-size: 40px;
  line-height: 1.2;
}
.overview-kicker {
  font-weight: 700;
  color: var(--ink-secondary);
  font-size: 13px;
}
.overview-description {
  color: var(--ink-secondary);
}
.overview-tools,
.panel-links,
.empty-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}
.scope-label,
.activity-kind {
  padding: 2px 8px;
  background: var(--surface-hover);
  color: var(--ink-secondary);
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}
.updated,
.subtle {
  color: var(--ink-secondary);
  font-size: 13px;
}
.updated,
.activity-time,
.focus-values,
.catalog-number,
.next-exam {
  font-variant-numeric: tabular-nums;
}
.refresh svg {
  width: 16px;
  height: 16px;
  stroke-width: 1.5;
}
.focus {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 24px;
  padding: 22px 26px;
  background: var(--overview-ink);
  color: var(--overview-text-on-dark);
}
.focus-copy {
  min-width: 0;
}
.focus h2 {
  margin: 14px 0 4px;
  font-size: 30px;
  line-height: 1.25;
  overflow-wrap: anywhere;
}
.focus-label,
.focus-meta,
.focus-values span,
.progress-caption {
  color: var(--overview-muted-on-dark);
  font-size: 13px;
}
.focus-label {
  font-weight: 650;
}
.focus-action {
  display: inline-flex;
  align-items: center;
  min-height: 38px;
  margin-top: 18px;
  padding: 0 14px;
  background: var(--overview-text-on-dark);
  color: var(--overview-ink);
  font-weight: 700;
  text-decoration: none;
}
.focus .focus-action:hover {
  background: #dce2e9;
}
.focus .focus-action:focus-visible {
  outline-color: var(--overview-text-on-dark);
}
.focus-facts {
  display: grid;
  align-content: start;
  min-width: 0;
  gap: 12px;
  padding-left: 24px;
  border-left: 1px solid #4a505b;
}
.focus-facts-header,
.progress-caption {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 4px 12px;
}
.focus-status {
  padding: 2px 8px;
  background: #41464f;
  font-size: 12px;
}
.focus-status.status-active {
  background: #303e38;
  color: #d3f0df;
}
.focus-values {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 14px;
}
.focus-values strong {
  display: block;
  font-size: clamp(22px, 2.15vw, 35px);
  line-height: 1.25;
  overflow-wrap: anywhere;
}
.focus-values small {
  font-size: 14px;
  font-weight: 500;
}
.progress-track {
  height: 5px;
  margin-bottom: 5px;
  background: #4a505b;
}
.progress-track span {
  display: block;
  height: 100%;
  background: #c6d1dc;
}
.progress-caption strong {
  color: var(--overview-text-on-dark);
}
.focus-empty {
  display: grid;
  gap: 7px;
  padding: 22px 26px;
  background: var(--surface-white);
  border: 1px solid var(--line);
}
.focus-empty h2 {
  font-size: 22px;
}
.focus-empty > p {
  color: var(--ink-secondary);
}
.empty-actions {
  margin-top: 5px;
}
.overview-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 12px;
  background: #f8ece7;
  color: #8b3c27;
  font-size: 13px;
}
.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(285px, 30%);
  align-items: start;
  gap: 18px;
  margin-top: 18px;
}
.overview-main,
.overview-aside {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.overview-panel {
  min-width: 0;
  border: 1px solid var(--line);
  background: var(--surface-white);
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 12px;
  padding: 16px 20px 8px;
}
.panel-heading h2 {
  font-size: 20px;
}
.panel-links a,
.activity-link,
.side-link {
  color: var(--ink);
  text-decoration: none;
}
.panel-links a {
  color: var(--ink-secondary);
  font-size: 13px;
}
.panel-links a:hover,
.activity-link:hover {
  text-decoration: underline;
}
.activity-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding-inline: 20px;
  border-bottom: 1px solid var(--line);
}
.activity-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 40px;
  padding: 0 4px;
  border: 0;
  border-bottom: 3px solid transparent;
  border-radius: 0;
  background: none;
  color: var(--ink-secondary);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
.activity-tabs button[aria-selected='true'] {
  border-bottom-color: var(--ink);
  color: var(--ink);
  font-weight: 700;
}
.activity-list,
.side-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.activity-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(112px, 25%) minmax(82px, auto);
  align-items: center;
  gap: 10px;
  padding: 11px 20px;
  border-bottom: 1px solid var(--line);
}
.activity-labels {
  background: var(--surface);
  color: var(--ink-secondary);
  font-size: 12px;
}
.activity-labels > :last-child,
.activity-link {
  justify-self: end;
}
.activity-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.activity-title strong {
  min-width: 0;
  overflow-wrap: anywhere;
}
.activity-time {
  color: var(--ink-secondary);
  font-size: 13px;
}
.activity-link {
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}
.panel-empty,
.activity-foot {
  padding: 12px 20px;
  color: var(--ink-secondary);
  font-size: 13px;
}
.activity-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 10px;
}
.next-exam {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  width: 100%;
  min-height: 66px;
  padding: 10px 16px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--surface-hover);
  color: var(--ink);
  text-align: left;
  font: inherit;
  cursor: pointer;
}
.next-exam:hover {
  background: #e1e6ec;
}
.next-exam small {
  display: block;
  color: var(--ink-secondary);
}
.next-exam-title {
  padding-left: 12px;
  border-left: 1px solid var(--line);
  overflow-wrap: anywhere;
}
.next-exam-action {
  font-size: 13px;
  white-space: nowrap;
}
.side-panel {
  padding-bottom: 8px;
}
.side-list {
  padding-inline: 15px;
}
.side-list > li {
  border-top: 1px solid var(--line);
}
.side-link {
  display: flex;
  min-height: 41px;
  align-items: center;
  gap: 8px;
  padding: 4px 4px;
  font-size: 14px;
}
.side-link:hover {
  background: var(--surface);
}
.side-link svg {
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  stroke-width: 1.25;
}
.side-arrow,
.catalog-number {
  margin-left: auto;
}
.catalog-number:not(.subtle) {
  font-size: 21px;
}
.catalog-total,
.catalog-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 8px;
  font-size: 13px;
}
.catalog-total {
  margin-inline: 15px;
  padding: 6px 4px 0;
  border-top: 1px solid var(--line);
  color: var(--ink-secondary);
}
.catalog-total strong {
  color: var(--ink);
}
.catalog-notice {
  padding: 0 4px 4px;
  color: #8b3c27;
}
.catalog-notice button {
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--surface-white);
  color: inherit;
  font: inherit;
  cursor: pointer;
}

@container (max-width: 920px) {
  .overview-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .overview-aside {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container (max-width: 570px) {
  .overview-header {
    margin-bottom: 15px;
  }
  .overview-header h1 {
    font-size: 32px;
  }
  .focus {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
    padding: 16px;
  }
  .focus h2 {
    font-size: 23px;
  }
  .focus-facts {
    padding: 14px 0 0;
    border-left: 0;
    border-top: 1px solid #4a505b;
  }
  .focus-action,
  .side-link,
  .catalog-notice button,
  .panel-links a,
  .activity-tabs button {
    min-height: 44px;
  }
  .panel-links a {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .overview-aside {
    grid-template-columns: minmax(0, 1fr);
  }
  .panel-heading,
  .activity-row,
  .panel-empty,
  .activity-foot {
    padding-inline: 12px;
  }
  .activity-tabs {
    padding-inline: 12px;
    gap: 4px 10px;
  }
  .activity-labels {
    display: none;
  }
  .activity-row {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 4px 8px;
  }
  .activity-time {
    grid-column: 1;
  }
  .activity-link {
    grid-column: 2;
    grid-row: 1 / 3;
    display: inline-flex;
    align-items: center;
    gap: 3px;
    min-height: 44px;
  }
  .next-exam {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 4px 8px;
  }
  .next-exam-title {
    grid-column: 1;
    padding-left: 0;
    border-left: 0;
  }
  .next-exam-action {
    grid-column: 2;
    grid-row: 1 / 3;
  }
}
</style>
