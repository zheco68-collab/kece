<script setup lang="ts">
import { computed } from 'vue';
import { useJudgeCluster } from '@/features/judging/composables/useJudgeCluster';

const {
  summary,
  loading,
  initialLoading,
  isStale,
  isUnavailable,
  readyPools,
  totalPools,
  pools,
  refresh,
} = useJudgeCluster();

const totalActive = computed(() => pools.value.reduce((sum, pool) => sum + pool.activeJobs, 0));
const totalPending = computed(() => pools.value.reduce((sum, pool) => sum + pool.pendingJobs, 0));

const poolStates: Record<string, { label: string; tone: string }> = {
  READY: { label: '就绪', tone: 'ready' },
  BUSY: { label: '评测中', tone: 'busy' },
  DRAINING: { label: '排空中', tone: 'warning' },
  OFFLINE: { label: '离线', tone: 'offline' },
  PENDING_SWITCH: { label: '待切换', tone: 'neutral' },
};
const unknownState = { label: '未知', tone: 'neutral' };

const poolRows = computed(() =>
  pools.value.map((pool) => ({
    ...pool,
    status: poolStates[pool.state] ?? unknownState,
  })),
);

const snapshotDate = computed(() => (summary.value ? new Date(summary.value.asOf) : null));
const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});
</script>

<template>
  <section class="cluster-console" aria-labelledby="cluster-status-title">
    <header class="console-head">
      <h2 id="cluster-status-title">评测状态</h2>
      <button
        type="button"
        class="refresh-btn"
        aria-label="刷新评测状态"
        :disabled="initialLoading || loading"
        @click="refresh(true)"
      >
        <svg
          aria-hidden="true"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
        >
          <path d="M20 4v5h-5M20 9a8.5 8.5 0 1 0 .2 6" />
        </svg>
        <span>{{ loading ? '刷新中' : '刷新' }}</span>
      </button>
    </header>

    <dl class="console-metrics">
      <div>
        <dt>可用专池</dt>
        <dd
          :class="{
            'all-ready': summary && !isStale && totalPools > 0 && readyPools === totalPools,
          }"
        >
          <template v-if="summary"
            >{{ readyPools }}<span class="metric-total"> / {{ totalPools }}</span></template
          >
          <template v-else>—</template>
        </dd>
      </div>
      <div>
        <dt>评测中</dt>
        <dd>{{ summary ? totalActive : '—' }}</dd>
      </div>
      <div>
        <dt>排队中</dt>
        <dd>{{ summary ? totalPending : '—' }}</dd>
      </div>
    </dl>

    <table class="pool-table" aria-label="各评测专池状态" :aria-busy="initialLoading">
      <thead>
        <tr>
          <th scope="col">专池</th>
          <th scope="col">状态</th>
          <th scope="col" aria-label="就绪节点">节点</th>
          <th scope="col">评测</th>
          <th scope="col">排队</th>
        </tr>
      </thead>
      <tbody>
        <template v-if="initialLoading">
          <tr v-for="index in 5" :key="index" class="skeleton-row" aria-hidden="true">
            <td v-for="column in 5" :key="column"><span class="skeleton-cell"></span></td>
          </tr>
        </template>
        <tr v-else-if="isUnavailable || !poolRows.length">
          <td colspan="5" class="empty-state">
            {{ isUnavailable ? '暂时无法获取评测状态' : '暂无专池数据' }}
          </td>
        </tr>
        <tr v-for="pool in poolRows" v-else :key="pool.pool">
          <th scope="row">{{ pool.name }}</th>
          <td>
            <span class="pool-status" :class="pool.status.tone">{{ pool.status.label }}</span>
          </td>
          <td>
            {{ pool.readyNodes }}<span class="node-total">/{{ pool.totalNodes }}</span>
          </td>
          <td :class="{ 'zero-count': pool.activeJobs === 0 }">{{ pool.activeJobs }}</td>
          <td :class="{ 'zero-count': pool.pendingJobs === 0 }">{{ pool.pendingJobs }}</td>
        </tr>
      </tbody>
    </table>

    <footer class="console-foot">
      <span v-if="snapshotDate">
        更新于
        <time
          :datetime="snapshotDate.toISOString()"
          :title="snapshotDate.toLocaleString('zh-CN')"
          >{{ timeFormatter.format(snapshotDate) }}</time
        >
      </span>
      <span v-else>{{ initialLoading ? '正在读取评测状态' : '暂无更新时间' }}</span>
      <span v-if="isStale" class="stale-note">显示上次数据</span>
    </footer>
  </section>
</template>

<style scoped>
.cluster-console {
  --panel-gutter: clamp(12px, 1.4vw, 20px);
  --pool-ready: #17613a;
  --pool-warning: #8a4b09;
  width: 100%;
  min-width: 0;
  background: var(--surface-white);
  border: 1px solid var(--line);
  border-top: 2px solid var(--line-strong);
  font-variant-numeric: tabular-nums;
}

.console-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px var(--panel-gutter);
}

.console-head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.3;
}

.refresh-btn {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  margin-inline-end: -8px;
  color: var(--ink-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.refresh-btn svg {
  width: 15px;
  height: 15px;
}

.refresh-btn:hover:not(:disabled) {
  background: var(--surface-hover);
  color: var(--ink);
}

.refresh-btn:focus-visible {
  outline: 2px solid var(--line-strong);
  outline-offset: -2px;
}

.refresh-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.console-metrics {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr;
  margin: 0;
  border-block: 1px solid var(--line);
  background: var(--surface);
}

.console-metrics > div {
  min-width: 0;
  padding: 10px var(--panel-gutter) 12px;
}

.console-metrics > div + div {
  border-inline-start: 1px solid var(--line);
}

.console-metrics dt {
  color: var(--ink-secondary);
  font-size: 12px;
}

.console-metrics dd {
  margin: 3px 0 0;
  color: var(--ink);
  font-size: clamp(21px, 2vw, 27px);
  font-weight: 700;
  line-height: 1.15;
  white-space: nowrap;
}

.console-metrics .all-ready {
  color: var(--pool-ready);
}

.metric-total {
  color: var(--ink-secondary);
  font-size: 0.62em;
  font-weight: 500;
}

.pool-table {
  width: 100%;
  border-collapse: collapse;
  text-align: right;
}

.pool-table :is(th, td) {
  padding: 0 6px;
}

.pool-table :is(th, td):first-child {
  padding-inline-start: var(--panel-gutter);
  text-align: left;
}

.pool-table :is(th, td):nth-child(2) {
  text-align: left;
}

.pool-table :is(th, td):last-child {
  padding-inline-end: var(--panel-gutter);
}

.pool-table thead th {
  height: 32px;
  color: var(--ink-secondary);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.pool-table tbody tr {
  border-top: 1px solid var(--line);
}

.pool-table tbody :is(th, td) {
  height: 40px;
  font-size: 13px;
}

.pool-table tbody th {
  font-weight: 650;
  overflow-wrap: anywhere;
}

.pool-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ink-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.pool-status::before {
  width: 5px;
  height: 5px;
  background: currentColor;
  content: '';
}

.pool-status.ready {
  color: var(--pool-ready);
}

.pool-status.busy {
  color: var(--ink);
}

.pool-status.warning,
.stale-note {
  color: var(--pool-warning);
}

.pool-status.offline {
  color: #a52c37;
}

.node-total,
.zero-count {
  color: var(--ink-secondary);
}

.skeleton-cell {
  display: inline-block;
  width: 1.35em;
  height: 0.65em;
  background: var(--surface-hover);
}

.skeleton-row td:first-child .skeleton-cell {
  width: 3.5em;
}

.pool-table tbody .empty-state {
  height: 120px;
  color: var(--ink-secondary);
  text-align: center;
}

.console-foot {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding: 9px var(--panel-gutter);
  border-top: 1px solid var(--line);
  color: var(--ink-secondary);
  font-size: 12px;
}

@media (max-width: 480px) {
  .cluster-console {
    --panel-gutter: 10px;
  }

  .pool-table :is(th, td) {
    padding-inline: 4px;
  }
}
</style>
