<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useJudgeCluster } from '@/features/judging/composables/useJudgeCluster';

const MAX_TILT_DEG = 1.5;
const ROW_DROP_DURATION_MS = 480;
const ROW_DROP_STAGGER_MS = 65;
const ROW_DROP_BUFFER_MS = 180;

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

const hudState = computed(() => {
  if (isUnavailable.value) {
    return { tone: 'offline', code: 'CONN.LOST' };
  }
  if (isStale.value) {
    return { tone: 'warning', code: 'STALE.CACHE' };
  }
  if (!summary.value) {
    return { tone: 'neutral', code: 'SYNCING' };
  }
  if (totalPools.value > 0 && readyPools.value < totalPools.value) {
    return { tone: 'warning', code: 'DEGRADED' };
  }
  if (totalActive.value > 0 || totalPending.value > 0) {
    return { tone: 'ready', code: 'ACTIVE.RUN' };
  }
  return { tone: 'ready', code: 'SYS.ONLINE' };
});

const readyRatioWidth = computed(() => {
  if (!summary.value || totalPools.value <= 0) return '0%';
  const ratio = Math.max(0, Math.min(100, (readyPools.value / totalPools.value) * 100));
  return `${Math.round(ratio)}%`;
});

const snapshotDate = computed(() => (summary.value ? new Date(summary.value.asOf) : null));
const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

const tiltX = ref(0);
const tiltY = ref(0);

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

const hasPoolRows = computed(() => !initialLoading.value && poolRows.value.length > 0);
const isRowDropping = ref(
  typeof window !== 'undefined' && !prefersReducedMotion() && hasPoolRows.value,
);

let isUnmounted = false;
let isManualRefreshing = false;
let dropRafId: number | null = null;
let dropRestartTimerId: ReturnType<typeof setTimeout> | null = null;
let dropFallbackTimerId: ReturnType<typeof setTimeout> | null = null;

function clearDropTimers() {
  if (
    dropRafId !== null &&
    typeof window !== 'undefined' &&
    typeof window.cancelAnimationFrame === 'function'
  ) {
    window.cancelAnimationFrame(dropRafId);
  }
  dropRafId = null;
  if (dropRestartTimerId !== null) {
    clearTimeout(dropRestartTimerId);
    dropRestartTimerId = null;
  }
  if (dropFallbackTimerId !== null) {
    clearTimeout(dropFallbackTimerId);
    dropFallbackTimerId = null;
  }
}

function scheduleRowDropFallback() {
  if (dropFallbackTimerId !== null) {
    clearTimeout(dropFallbackTimerId);
  }
  const totalMs =
    Math.max(poolRows.value.length - 1, 0) * ROW_DROP_STAGGER_MS +
    ROW_DROP_DURATION_MS +
    ROW_DROP_BUFFER_MS;
  dropFallbackTimerId = setTimeout(() => {
    dropFallbackTimerId = null;
    if (!isUnmounted) {
      isRowDropping.value = false;
    }
  }, totalMs);
}

function activateRowDrop() {
  if (isUnmounted || prefersReducedMotion() || !hasPoolRows.value) {
    isRowDropping.value = false;
    return;
  }
  isRowDropping.value = true;
  scheduleRowDropFallback();
}

function triggerRowDrop() {
  clearDropTimers();
  if (isUnmounted || prefersReducedMotion() || !hasPoolRows.value) {
    isRowDropping.value = false;
    return;
  }

  if (!isRowDropping.value) {
    activateRowDrop();
    return;
  }

  // Detach for one frame so a rapid refresh replays the staggered cascade cleanly.
  isRowDropping.value = false;
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    dropRafId = window.requestAnimationFrame(() => {
      dropRafId = null;
      activateRowDrop();
    });
  } else {
    dropRestartTimerId = setTimeout(() => {
      dropRestartTimerId = null;
      activateRowDrop();
    }, 16);
  }
}

function rowDropStyle(index: number) {
  if (!isRowDropping.value) return undefined;
  return { '--row-delay': `${index * ROW_DROP_STAGGER_MS}ms` };
}

function handleRowDropEnd(index: number, event: AnimationEvent) {
  if (event.target !== event.currentTarget) return;
  if (index < poolRows.value.length - 1) return;
  clearDropTimers();
  isRowDropping.value = false;
}

async function handleRefresh() {
  isManualRefreshing = true;
  try {
    await refresh(true);
  } finally {
    isManualRefreshing = false;
    if (!isUnmounted && hasPoolRows.value) {
      triggerRowDrop();
    }
  }
}

watch(hasPoolRows, (ready, prevReady) => {
  if (ready && !prevReady && !isManualRefreshing) {
    triggerRowDrop();
  } else if (!ready) {
    clearDropTimers();
    isRowDropping.value = false;
  }
});

function canAnimateTilt(): boolean {
  if (typeof window === 'undefined' || prefersReducedMotion()) return false;
  if (typeof window.matchMedia === 'function' && window.matchMedia('(hover: none)').matches) {
    return false;
  }
  return window.innerWidth > 480;
}

const tiltStyle = computed(() => {
  if (tiltX.value === 0 && tiltY.value === 0) return undefined;
  return {
    transform: `perspective(900px) rotateX(${tiltX.value}deg) rotateY(${tiltY.value}deg)`,
  };
});

function handleMouseMove(event: MouseEvent) {
  if (!canAnimateTilt()) return;
  const el = event.currentTarget as HTMLElement | null;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return;

  const normX = Math.max(-1, Math.min(1, ((event.clientX - rect.left) / rect.width - 0.5) * 2));
  const normY = Math.max(-1, Math.min(1, ((event.clientY - rect.top) / rect.height - 0.5) * 2));

  tiltX.value = Number((-normY * MAX_TILT_DEG).toFixed(2));
  tiltY.value = Number((normX * MAX_TILT_DEG).toFixed(2));
}

function resetTilt() {
  tiltX.value = 0;
  tiltY.value = 0;
}

onMounted(() => {
  if (prefersReducedMotion()) {
    isRowDropping.value = false;
    return;
  }
  if (isRowDropping.value) {
    scheduleRowDropFallback();
  }
});

onBeforeUnmount(() => {
  isUnmounted = true;
  clearDropTimers();
});
</script>

<template>
  <section
    class="cluster-console"
    aria-labelledby="cluster-status-title"
    :style="tiltStyle"
    @mousemove="handleMouseMove"
    @mouseleave="resetTilt"
  >
    <div class="hud-scanline" aria-hidden="true"></div>

    <header class="console-head">
      <div class="console-title-group">
        <span class="hud-dot" :class="hudState.tone" aria-hidden="true"></span>
        <h2 id="cluster-status-title">评测状态</h2>
        <span class="hud-tag">CLUSTER HUD</span>
      </div>
      <button
        type="button"
        class="refresh-btn"
        aria-label="刷新评测状态"
        :disabled="initialLoading || loading"
        @click="handleRefresh"
      >
        <svg
          :class="{ 'is-spinning': loading }"
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
      <div class="metric-primary">
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
        <div class="metric-meter" aria-hidden="true">
          <span
            class="metric-meter-fill"
            :class="hudState.tone"
            :style="{ width: readyRatioWidth }"
          ></span>
        </div>
      </div>
      <div>
        <dt>评测中</dt>
        <dd :class="{ 'metric-active': summary && totalActive > 0 }">
          {{ summary ? totalActive : '—' }}
        </dd>
      </div>
      <div>
        <dt>排队中</dt>
        <dd :class="{ 'metric-pending': summary && totalPending > 0 }">
          {{ summary ? totalPending : '—' }}
        </dd>
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
        <tr v-else-if="isUnavailable || !poolRows.length" class="empty-row">
          <td colspan="5" class="empty-state">
            {{ isUnavailable ? '暂时无法获取评测状态' : '暂无专池数据' }}
          </td>
        </tr>
        <tr
          v-for="(pool, index) in poolRows"
          v-else
          :key="pool.pool"
          class="pool-row"
          :class="{ 'is-dropping': isRowDropping }"
          :style="rowDropStyle(index)"
          @animationend="handleRowDropEnd(index, $event)"
        >
          <th scope="row">{{ pool.name }}</th>
          <td>
            <span class="pool-status" :class="pool.status.tone">{{ pool.status.label }}</span>
          </td>
          <td>
            {{ pool.readyNodes }}<span class="node-total">/{{ pool.totalNodes }}</span>
          </td>
          <td :class="{ 'zero-count': pool.activeJobs === 0, 'active-count': pool.activeJobs > 0 }">
            {{ pool.activeJobs }}
          </td>
          <td
            :class="{ 'zero-count': pool.pendingJobs === 0, 'pending-count': pool.pendingJobs > 0 }"
          >
            {{ pool.pendingJobs }}
          </td>
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
      <div class="foot-status">
        <span v-if="isStale" class="stale-note">显示上次数据</span>
        <span class="hud-telemetry" :class="hudState.tone">{{ hudState.code }}</span>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.cluster-console {
  --panel-gutter: clamp(12px, 1.4vw, 20px);
  --hud-accent: var(--kece-accent, #137969);
  --pool-ready: var(--hud-accent);
  --pool-warning: #b45309;
  --pool-offline: #b91c1c;
  position: relative;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  background-color: var(--surface-white, #ffffff);
  border: 1px solid var(--line, #e2e8f0);
  border-top: 2px solid var(--hud-accent);
  box-shadow:
    0 12px 28px rgba(0, 0, 0, 0.09),
    0 2px 6px rgba(0, 0, 0, 0.05);
  font-variant-numeric: tabular-nums;
  transition:
    transform 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.22s ease;
}

.cluster-console:hover {
  box-shadow:
    0 18px 36px rgba(0, 0, 0, 0.14),
    0 4px 10px rgba(0, 0, 0, 0.07);
}

.hud-scanline {
  position: absolute;
  inset-inline: 0;
  top: 0;
  height: 1px;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(19, 121, 105, 0.55) 50%,
    transparent 100%
  );
  pointer-events: none;
  z-index: 1;
  opacity: 0;
  animation: hud-scan 6s cubic-bezier(0.4, 0, 0.2, 1) infinite;
}

.console-head {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px var(--panel-gutter);
}

.console-title-group {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.hud-dot {
  position: relative;
  flex-shrink: 0;
  width: 7px;
  height: 7px;
  background: var(--ink-secondary, #64748b);
}

.hud-dot.ready {
  background: var(--pool-ready);
}

.hud-dot.ready::after {
  position: absolute;
  inset: -3px;
  border: 1px solid var(--pool-ready);
  content: '';
  opacity: 0;
  animation: hud-pulse 2.2s ease-out infinite;
}

.hud-dot.warning {
  background: var(--pool-warning);
}

.hud-dot.offline {
  background: var(--pool-offline);
}

.console-head h2 {
  margin: 0;
  color: var(--ink, #111111);
  font-size: 18px;
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: -0.01em;
}

.hud-tag {
  padding: 2px 6px;
  border: 1px solid var(--line, #e2e8f0);
  background: #f8fafc;
  color: var(--ink-secondary, #64748b);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  line-height: 1.2;
  white-space: nowrap;
}

.refresh-btn {
  position: relative;
  z-index: 10;
  pointer-events: auto;
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 6px;
  padding: 0 11px;
  border: 1px solid var(--line, #e2e8f0);
  background: var(--surface-white, #ffffff);
  color: var(--ink-secondary, #52525b);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    color 0.16s ease;
}

.refresh-btn svg {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.refresh-btn svg.is-spinning {
  animation: hud-spin 0.8s linear infinite;
}

.refresh-btn:hover:not(:disabled) {
  background: #f8fafc;
  border-color: var(--ink, #111111);
  color: var(--ink, #111111);
}

.refresh-btn:focus-visible {
  outline: 2px solid var(--hud-accent);
  outline-offset: -2px;
}

.refresh-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.console-metrics {
  display: grid;
  grid-template-columns: 1.25fr 1fr 1fr;
  margin: 0;
  border-block: 1px solid var(--line, #e2e8f0);
  background: #f8fafc;
}

.console-metrics > div {
  position: relative;
  min-width: 0;
  padding: 10px var(--panel-gutter) 12px;
}

.console-metrics > div + div {
  border-inline-start: 1px solid var(--line, #e2e8f0);
}

.console-metrics dt {
  color: var(--ink-secondary, #52525b);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
}

.console-metrics dd {
  margin: 4px 0 0;
  color: var(--ink, #111111);
  font-size: clamp(21px, 2vw, 27px);
  font-weight: 700;
  line-height: 1.15;
  white-space: nowrap;
}

.console-metrics .all-ready,
.console-metrics .metric-active {
  color: var(--pool-ready);
}

.console-metrics .metric-pending {
  color: var(--pool-warning);
}

.metric-total {
  color: var(--ink-secondary, #64748b);
  font-size: 0.6em;
  font-weight: 500;
}

.metric-meter {
  position: absolute;
  inset-inline: 0;
  bottom: 0;
  height: 2px;
  background: rgba(17, 17, 17, 0.06);
}

.metric-meter-fill {
  display: block;
  height: 100%;
  background: var(--pool-ready);
  transition: width 0.35s cubic-bezier(0.22, 1, 0.36, 1);
}

.metric-meter-fill.warning {
  background: var(--pool-warning);
}

.metric-meter-fill.offline {
  background: var(--pool-offline);
}

.metric-meter-fill.neutral {
  background: var(--ink-secondary, #64748b);
}

.pool-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
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

.pool-table thead {
  position: relative;
  z-index: 2;
}

.pool-table thead th {
  height: 32px;
  background: #f8fafc;
  color: var(--ink-secondary, #64748b);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.pool-table tbody tr {
  position: relative;
  z-index: 1;
  transition: background-color 0.14s ease;
}

.pool-row.is-dropping {
  animation: pool-row-drop 260ms var(--row-delay, 0ms) cubic-bezier(0.22, 1, 0.36, 1) both;
}

.pool-table tbody tr:not(.skeleton-row):not(.empty-row):hover {
  background: #eef6f4;
}

.pool-table tbody :is(th, td) {
  height: 40px;
  border-top: 1px solid var(--line, #e2e8f0);
  font-size: 13px;
}

.pool-table tbody th {
  color: var(--ink, #111111);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.pool-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-secondary, #64748b);
  font-size: 12px;
  font-weight: 500;
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
  color: var(--ink, #111111);
}

.pool-status.warning,
.stale-note,
.pending-count {
  color: var(--pool-warning);
}

.active-count {
  color: var(--pool-ready);
  font-weight: 600;
}

.pending-count {
  font-weight: 600;
}

.pool-status.offline {
  color: var(--pool-offline);
}

.node-total,
.zero-count {
  color: var(--ink-secondary, #64748b);
}

.skeleton-cell {
  display: inline-block;
  width: 1.35em;
  height: 0.65em;
  background: var(--surface-hover, #f1f5f9);
}

.skeleton-row td:first-child .skeleton-cell {
  width: 3.5em;
}

.pool-table tbody .empty-state {
  height: 120px;
  color: var(--ink-secondary, #64748b);
  text-align: center;
}

.console-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 12px;
  padding: 9px var(--panel-gutter);
  border-top: 1px solid var(--line, #e2e8f0);
  background: #f8fafc;
  color: var(--ink-secondary, #64748b);
  font-size: 12px;
}

.foot-status {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.hud-telemetry {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-secondary, #64748b);
}

.hud-telemetry.ready {
  color: var(--pool-ready);
}

.hud-telemetry.warning {
  color: var(--pool-warning);
}

.hud-telemetry.offline {
  color: var(--pool-offline);
}

@keyframes hud-scan {
  0% {
    top: 0%;
    opacity: 0;
  }
  8% {
    opacity: 0.9;
  }
  88% {
    opacity: 0.75;
  }
  100% {
    top: 100%;
    opacity: 0;
  }
}

@keyframes hud-pulse {
  0% {
    opacity: 0.65;
    transform: scale(0.95);
  }
  100% {
    opacity: 0;
    transform: scale(1.9);
  }
}

@keyframes hud-spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes pool-row-drop {
  0% {
    opacity: 0;
    transform: translate3d(0, -10px, 0);
  }
  100% {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

@media (max-width: 480px) {
  .cluster-console {
    --panel-gutter: 10px;
    transform: none !important;
  }

  .hud-tag {
    display: none;
  }

  .pool-table :is(th, td) {
    padding-inline: 4px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pool-row.is-dropping {
    animation: none !important;
    will-change: auto;
    opacity: 1;
    transform: none;
  }

  .cluster-console {
    transition: none;
    transform: none !important;
  }

  .hud-scanline {
    display: none;
  }

  .hud-dot.ready::after,
  .refresh-btn svg.is-spinning,
  .metric-meter-fill {
    animation: none;
    transition: none;
  }
}
</style>
