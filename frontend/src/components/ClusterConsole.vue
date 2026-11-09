<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useJudgeCluster } from '@/features/judging/composables/useJudgeCluster'
import type { PoolSummary } from '@/api/system'

const cluster = useJudgeCluster()

// ---- 3D 悬浮与光标倾斜追踪 ----
const cardRef = ref<HTMLElement | null>(null)
const rotateX = ref(0)
const rotateY = ref(0)
const glareX = ref(50)
const glareY = ref(50)
const isHovered = ref(false)
let rAF: number | null = null

function handleMouseMove(e: MouseEvent) {
  if (!cardRef.value) return
  const rect = cardRef.value.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  const centerX = rect.width / 2
  const centerY = rect.height / 2
  const normX = (x - centerX) / centerX
  const normY = (y - centerY) / centerY

  if (rAF) cancelAnimationFrame(rAF)
  rAF = requestAnimationFrame(() => {
    const maxTilt = 14 // 最大倾角 14 度，3D 倾斜反馈显著清晰
    rotateX.value = Number((-normY * maxTilt).toFixed(2))
    rotateY.value = Number((normX * maxTilt).toFixed(2))
    glareX.value = Number(((x / rect.width) * 100).toFixed(1))
    glareY.value = Number(((y / rect.height) * 100).toFixed(1))
  })
}

function handleMouseEnter() {
  isHovered.value = true
}

function handleMouseLeave() {
  isHovered.value = false
  if (rAF) cancelAnimationFrame(rAF)
  rotateX.value = 0
  rotateY.value = 0
  glareX.value = 50
  glareY.value = 50
}

const cardTransformStyle = computed(() => {
  if (!isHovered.value) {
    return {
      transform: 'perspective(1200px) rotateX(0deg) rotateY(0deg) translateY(0px) translateZ(0px) scale3d(1, 1, 1)',
      transition: 'transform 0.5s cubic-bezier(0.25, 1, 0.5, 1), box-shadow 0.5s ease, border-color 0.4s ease',
    }
  }
  return {
    transform: `perspective(1200px) rotateX(${rotateX.value}deg) rotateY(${rotateY.value}deg) translateY(-8px) translateZ(24px) scale3d(1.02, 1.02, 1.02)`,
    transition: 'transform 0.08s cubic-bezier(0.2, 0.8, 0.2, 1), box-shadow 0.2s ease, border-color 0.2s ease',
  }
})

const glareStyle = computed(() => {
  return {
    background: `radial-gradient(circle 380px at ${glareX.value}% ${glareY.value}%, rgba(255, 255, 255, 0.55), transparent 75%)`,
    opacity: isHovered.value ? '1' : '0',
    transition: 'opacity 0.25s ease',
  }
})

// ---- 绿色扫描线动画 ----
const isScanning = ref(false)
let scanTimer: ReturnType<typeof setTimeout> | null = null

function triggerScan() {
  isScanning.value = false
  if (scanTimer) clearTimeout(scanTimer)
  nextTick(() => {
    isScanning.value = true
    scanTimer = setTimeout(() => {
      isScanning.value = false
    }, 1400)
  })
}

function handleRefresh() {
  triggerScan()
  cluster.refresh(true)
}

watch(
  () => cluster.summary.value?.asOf,
  (newVal) => {
    if (newVal) {
      triggerScan()
    }
  }
)

onMounted(() => {
  setTimeout(() => {
    triggerScan()
  }, 350)
})

onBeforeUnmount(() => {
  if (scanTimer) clearTimeout(scanTimer)
  if (rAF) cancelAnimationFrame(rAF)
})

const totalPending = computed(() => {
  return cluster.pools.value.reduce((acc, p) => acc + (p.pendingJobs || 0), 0)
})

const totalActive = computed(() => {
  return cluster.pools.value.reduce((acc, p) => acc + (p.activeJobs || 0), 0)
})

const formattedSnapshotTime = computed(() => {
  const asOf = cluster.summary.value?.asOf
  if (!asOf) return '--'
  const d = new Date(asOf)
  const pad = (n: number) => (n < 10 ? `0${n}` : String(n))
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

function poolBadgeText(pool: string): string {
  switch (pool.toLowerCase()) {
    case 'cpp':
      return 'C++'
    case 'java':
      return 'JAVA'
    case 'python':
      return 'PYTHON'
    case 'mysql':
      return 'MYSQL'
    case 'arkts':
      return 'ARKTS'
    default:
      return pool.toUpperCase()
  }
}

function statusLabel(state: string): string {
  switch (state) {
    case 'READY':
      return '就绪'
    case 'BUSY':
      return '评测中'
    case 'DRAINING':
      return '排空中'
    case 'OFFLINE':
      return '离线'
    case 'PENDING_SWITCH':
      return '待切换'
    default:
      return state || '未知'
  }
}

function statusToneClass(state: string): string {
  switch (state) {
    case 'READY':
      return 'tone-ready'
    case 'BUSY':
      return 'tone-busy'
    case 'DRAINING':
      return 'tone-draining'
    case 'OFFLINE':
      return 'tone-offline'
    case 'PENDING_SWITCH':
    default:
      return 'tone-pending'
  }
}

// 兜底 5 大专池骨架数据
const defaultPoolKeys: Array<{ pool: string; name: string }> = [
  { pool: 'cpp', name: 'C++ 独立专池' },
  { pool: 'java', name: 'Java 独立专池' },
  { pool: 'python', name: 'Python 独立专池' },
  { pool: 'mysql', name: 'MySQL 独立专池' },
  { pool: 'arkts', name: 'ArkTS 独立专池' },
]

const displayPools = computed<PoolSummary[]>(() => {
  if (cluster.pools.value.length > 0) {
    return cluster.pools.value
  }
  return defaultPoolKeys.map((p) => ({
    pool: p.pool,
    name: p.name,
    state: 'PENDING_SWITCH',
    readyNodes: 0,
    totalNodes: 0,
    pendingJobs: 0,
    activeJobs: 0,
  }))
})
</script>

<template>
  <div
    class="cluster-console-wrapper"
    @mousemove="handleMouseMove"
    @mouseenter="handleMouseEnter"
    @mouseleave="handleMouseLeave"
  >
    <div
      ref="cardRef"
      class="cluster-console"
      :class="{ 'is-tilting': isHovered, 'is-scanning': isScanning }"
      :style="cardTransformStyle"
    >
      <!-- 绿色扫描线动画 -->
      <div v-if="isScanning" class="scanline-track" aria-hidden="true">
        <div class="scanline-bar"></div>
        <div class="scanline-glow"></div>
      </div>

      <!-- 3D 视差微光反射层 -->
      <div class="glare-overlay" :style="glareStyle" aria-hidden="true"></div>

      <!-- 顶栏：系统指标与控制 -->
      <div class="console-head">
        <div class="head-left">
          <span class="head-brand mono">KECE // POOLS</span>
          <span class="head-title">评测机集群遥测</span>
        </div>
        <div class="head-right">
          <div
            class="status-pill mono"
            :class="{
              'pill-ready': !cluster.isStale.value && !cluster.isUnavailable.value && cluster.readyPools.value > 0,
              'pill-stale': cluster.isStale.value && !cluster.isUnavailable.value,
              'pill-offline': cluster.isUnavailable.value || cluster.readyPools.value === 0,
            }"
          >
            <span class="pulse-dot"></span>
            <span v-if="cluster.initialLoading.value">FETCHING...</span>
            <span v-else-if="cluster.isUnavailable.value">UNAVAILABLE</span>
            <span v-else-if="cluster.isStale.value">STALE ({{ cluster.readyPools.value }}/{{ cluster.totalPools.value }})</span>
            <span v-else>{{ cluster.readyPools.value }}/{{ cluster.totalPools.value }} POOLS READY</span>
          </div>

          <button
            class="refresh-btn"
            :class="{ 'is-loading': cluster.loading.value }"
            :disabled="cluster.loading.value"
            title="手动刷新集群状态"
            @click="handleRefresh"
          >
            <svg class="refresh-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
            </svg>
          </button>
        </div>
      </div>

      <!-- 快照时间与状态栏 -->
      <div class="console-subbar mono">
        <div class="subbar-time">
          <span class="subbar-label">SNAPSHOT:</span>
          <span>{{ formattedSnapshotTime }}</span>
          <span v-if="cluster.isStale.value" class="tag-stale">[已过期]</span>
        </div>
        <div class="subbar-metrics">
          <span>ACTIVE: {{ totalActive }}</span>
          <span class="sep">/</span>
          <span>QUEUED: {{ totalPending }}</span>
        </div>
      </div>

      <!-- 骨架屏：首屏加载态 -->
      <div v-if="cluster.initialLoading.value" class="skeleton-list">
        <div v-for="i in 5" :key="i" class="skeleton-row">
          <div class="sk-box sk-tag"></div>
          <div class="sk-box sk-name"></div>
          <div class="sk-box sk-status"></div>
          <div class="sk-box sk-metrics"></div>
        </div>
      </div>

      <!-- 异常状态：无数据且错误 -->
      <div v-else-if="cluster.isUnavailable.value" class="error-state">
        <div class="error-tag mono">ERR // DISCONNECTED</div>
        <div class="error-msg">评测集群状态暂不可用，无法建立遥测连接</div>
        <button class="retry-btn mono" @click="cluster.refresh(true)">RETRY CONNECT</button>
      </div>

      <!-- 五大专池条状列表 -->
      <div v-else class="pools-list">
        <div
          v-for="p in displayPools"
          :key="p.pool"
          class="pool-row"
          :class="`is-${p.state.toLowerCase()}`"
        >
          <div class="pool-col-main">
            <span class="pool-tag mono">{{ poolBadgeText(p.pool) }}</span>
            <div class="pool-meta">
              <span class="pool-name">{{ p.name }}</span>
              <span class="pool-sub mono">
                {{ p.readyNodes }}/{{ p.totalNodes }} 节点就绪
              </span>
            </div>
          </div>

          <div class="pool-col-status">
            <span class="pool-status-badge mono" :class="statusToneClass(p.state)">
              {{ statusLabel(p.state) }}
            </span>
          </div>

          <div class="pool-col-metrics mono">
            <div class="metric-item">
              <span class="metric-label">RUN:</span>
              <span class="metric-val" :class="{ 'has-val': p.activeJobs > 0 }">{{ p.activeJobs }}</span>
            </div>
            <div class="metric-item">
              <span class="metric-label">WAIT:</span>
              <span class="metric-val" :class="{ 'has-val': p.pendingJobs > 0 }">{{ p.pendingJobs }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部说明栏 -->
      <div class="console-foot mono">
        <div class="foot-left">
          <span class="foot-dot"></span>
          <span>CGROUPS V2 · 独立专池 · 故障自动收敛</span>
        </div>
        <div class="foot-right">
          <span>SPEC: PR-07 COMPLIANT</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cluster-console-wrapper {
  width: 100%;
  display: flex;
  position: relative;
  perspective: 1200px;
  transform-style: preserve-3d;
}

.cluster-console {
  width: 100%;
  background: #ffffff;
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  user-select: none;
  transform-style: preserve-3d;
  box-shadow:
    0 12px 32px -4px rgba(15, 23, 42, 0.08),
    0 4px 12px -2px rgba(15, 23, 42, 0.04),
    0 0 0 1px rgba(15, 23, 42, 0.02);
  will-change: transform, box-shadow, border-color;
  transition: border-color 0.3s ease;
}

.cluster-console.is-tilting {
  border-color: #0f172a;
  box-shadow:
    0 32px 70px -12px rgba(0, 0, 0, 0.36),
    0 16px 32px -6px rgba(0, 0, 0, 0.22),
    0 0 30px 4px rgba(0, 0, 0, 0.25),
    0 0 0 1px #0f172a;
}

.cluster-console.is-scanning {
  border-color: #0f172a;
}

/* 3D 视差微光反射层 */
.glare-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 10;
  mix-blend-mode: overlay;
}

/* 绿色扫描线动画层 */
.scanline-track {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 15;
  overflow: hidden;
}

.scanline-bar {
  position: absolute;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(34, 197, 94, 0.5) 15%,
    #4ade80 45%,
    #86efac 50%,
    #4ade80 55%,
    rgba(34, 197, 94, 0.5) 85%,
    transparent 100%
  );
  box-shadow:
    0 0 14px 3px #22c55e,
    0 0 28px 6px rgba(34, 197, 94, 0.7),
    0 0 45px 10px rgba(34, 197, 94, 0.4);
  animation: scanline-sweep 1.35s cubic-bezier(0.35, 0, 0.25, 1) forwards;
}

.scanline-glow {
  position: absolute;
  left: 0;
  right: 0;
  height: 85px;
  background: linear-gradient(
    to top,
    rgba(34, 197, 94, 0.35) 0%,
    rgba(34, 197, 94, 0.15) 40%,
    rgba(34, 197, 94, 0.04) 80%,
    transparent 100%
  );
  animation: scanline-glow-sweep 1.35s cubic-bezier(0.35, 0, 0.25, 1) forwards;
}

@keyframes scanline-sweep {
  0% {
    top: -4px;
    opacity: 0;
  }
  8% {
    opacity: 1;
  }
  88% {
    opacity: 1;
  }
  100% {
    top: calc(100% + 4px);
    opacity: 0;
  }
}

@keyframes scanline-glow-sweep {
  0% {
    top: -64px;
    opacity: 0;
  }
  8% {
    opacity: 1;
  }
  88% {
    opacity: 0.85;
  }
  100% {
    top: 100%;
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .cluster-console {
    transform: none !important;
    transition: none !important;
  }
  .scanline-track {
    display: none !important;
  }
}

.console-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line, #e2e8f0);
  background: #fafafa;
}

.head-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.head-brand {
  font-size: 11px;
  color: var(--ink-tertiary, #94a3b8);
  font-weight: 700;
  letter-spacing: 0.04em;
}

.head-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink, #0f172a);
}

.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  padding: 3px 8px;
  font-weight: 600;
  letter-spacing: 0.03em;
  border: 1px solid transparent;
}

.status-pill.pill-ready {
  background: #ecfdf5;
  color: #047857;
  border-color: #a7f3d0;
}

.status-pill.pill-stale {
  background: #fffbeb;
  color: #b45309;
  border-color: #fde68a;
}

.status-pill.pill-offline {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}

.pulse-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.refresh-btn {
  background: transparent;
  border: 1px solid var(--line, #e2e8f0);
  padding: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--ink-secondary, #64748b);
  transition: all 0.15s ease;
}

.refresh-btn:hover:not(:disabled) {
  background: #f1f5f9;
  color: var(--ink, #0f172a);
}

.refresh-btn.is-loading .refresh-icon {
  animation: spin 0.8s linear infinite;
}

.refresh-icon {
  width: 14px;
  height: 14px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.console-subbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 16px;
  font-size: 11px;
  background: #f8fafc;
  border-bottom: 1px solid var(--line, #e2e8f0);
  color: var(--ink-tertiary, #94a3b8);
}

.subbar-time {
  display: flex;
  align-items: center;
  gap: 6px;
}

.subbar-label {
  font-weight: 600;
}

.tag-stale {
  color: #b45309;
  font-weight: 700;
}

.subbar-metrics {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--ink-secondary, #64748b);
}

.subbar-metrics .sep {
  color: var(--line, #e2e8f0);
}

/* 列表区 */
.pools-list {
  display: flex;
  flex-direction: column;
}

.pool-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line, #f1f5f9);
  transition: background 0.12s ease;
}

.pool-row:last-child {
  border-bottom: none;
}

.pool-row:hover {
  background: #f8fafc;
}

.pool-col-main {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.pool-tag {
  display: inline-block;
  min-width: 54px;
  text-align: center;
  padding: 2px 6px;
  background: #0f172a;
  color: #ffffff;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.pool-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.pool-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink, #0f172a);
}

.pool-sub {
  font-size: 11px;
  color: var(--ink-tertiary, #94a3b8);
}

.pool-col-status {
  padding: 0 12px;
}

.pool-status-badge {
  display: inline-block;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid transparent;
}

.pool-status-badge.tone-ready {
  background: #ecfdf5;
  color: #047857;
  border-color: #a7f3d0;
}

.pool-status-badge.tone-busy {
  background: #eff6ff;
  color: #1d4ed8;
  border-color: #bfdbfe;
}

.pool-status-badge.tone-draining {
  background: #fffbeb;
  color: #b45309;
  border-color: #fde68a;
}

.pool-status-badge.tone-offline {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}

.pool-status-badge.tone-pending {
  background: #f1f5f9;
  color: #64748b;
  border-color: #e2e8f0;
}

.pool-col-metrics {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
}

.metric-item {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.metric-label {
  color: var(--ink-tertiary, #94a3b8);
}

.metric-val {
  font-weight: 700;
  color: var(--ink-secondary, #64748b);
}

.metric-val.has-val {
  color: var(--ink, #0f172a);
}

/* 骨架屏 */
.skeleton-list {
  display: flex;
  flex-direction: column;
}

.skeleton-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line, #f1f5f9);
}

.sk-box {
  background: #f1f5f9;
  height: 16px;
  animation: pulse 1.5s ease-in-out infinite;
}

.sk-tag {
  width: 50px;
  height: 20px;
}

.sk-name {
  flex: 1;
}

.sk-status {
  width: 48px;
}

.sk-metrics {
  width: 70px;
}

@keyframes pulse {
  0%, 100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
}

/* 异常状态 */
.error-state {
  padding: 32px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  background: #fffafa;
}

.error-tag {
  font-size: 11px;
  font-weight: 700;
  color: #b91c1c;
  letter-spacing: 0.05em;
  margin-bottom: 6px;
}

.error-msg {
  font-size: 12px;
  color: var(--ink-secondary, #64748b);
  margin-bottom: 14px;
}

.retry-btn {
  background: #0f172a;
  color: #ffffff;
  border: none;
  font-size: 11px;
  padding: 6px 14px;
  cursor: pointer;
  letter-spacing: 0.04em;
  font-weight: 600;
}

.retry-btn:hover {
  background: #1e293b;
}

/* 底部栏 */
.console-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #fafafa;
  border-top: 1px solid var(--line, #e2e8f0);
  font-size: 11px;
  color: var(--ink-tertiary, #94a3b8);
}

.foot-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.foot-dot {
  width: 5px;
  height: 5px;
  background: #10b981;
  border-radius: 50%;
}
</style>
