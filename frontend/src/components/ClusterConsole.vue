<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { systemApi, type ClusterStatus } from '@/api/system'

const loading = ref(false)
const countdown = ref(6)
const latency = ref(12)
const isSweeping = ref(false)



const cluster = ref<ClusterStatus>({
  totalNodes: 2,
  onlineNodes: 2,
  busyNodes: 0,
  idleNodes: 2,
  offlineNodes: 0,
  pendingTasks: 0,
  nodes: [
    {
      id: 'node-algo-01',
      name: '算法评测机 #1',
      type: 'ALGO',
      typeLabel: 'Cgroups 沙箱 (C++/Java/Python/ArkTS)',
      status: 'IDLE',
      statusLabel: '空闲',
      currentTasks: 0,
      maxConcurrency: 8,
      lastHeartbeatTime: Date.now(),
      details: 'Docker Cgroups v2 强隔离 · 毫秒级反馈',
    },
    {
      id: 'node-sql-01',
      name: '数据库评测机 #1',
      type: 'DATABASE',
      typeLabel: 'MySQL 8.0 临时库对拍',
      status: 'IDLE',
      statusLabel: '空闲',
      currentTasks: 0,
      maxConcurrency: 8,
      lastHeartbeatTime: Date.now(),
      details: '独立端口 3307 临时库 · 物理强隔离',
    },
  ],
  timestamp: Date.now(),
})

let timer: number | null = null
let countdownTimer: number | null = null



function triggerSweep() {
  isSweeping.value = true
  setTimeout(() => {
    isSweeping.value = false
  }, 850)
}

async function fetchStatus(manual = false) {
  if (manual) loading.value = true
  const t0 = performance.now()
  try {
    const res = await systemApi.getClusterStatus()
    if (res && res.nodes) {
      cluster.value = res
    }
    latency.value = Math.max(8, Math.round(performance.now() - t0))
    triggerSweep()
  } catch {
    latency.value = Math.max(10, Math.round(performance.now() - t0))
  } finally {
    if (manual) {
      setTimeout(() => {
        loading.value = false
      }, 350)
    }
  }
}

function handleManualRefresh() {
  countdown.value = 6
  fetchStatus(true)
}



onMounted(() => {
  fetchStatus()

  timer = window.setInterval(() => {
    countdown.value = 6
    fetchStatus()
  }, 6000)

  countdownTimer = window.setInterval(() => {
    if (countdown.value > 1) {
      countdown.value--
    } else {
      countdown.value = 6
    }
  }, 1000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (countdownTimer) clearInterval(countdownTimer)
  if (rafId) cancelAnimationFrame(rafId)
})

// =========================================
// 3D 俯仰微倾与跟随光标的径向镜面高光 (3D Tilt & Specular Glare)
// =========================================
const wrapperRef = ref<HTMLElement | null>(null)
const isTilting = ref(false)

const rotX = ref(0)
const rotY = ref(0)
const glareX = ref(0)
const glareY = ref(0)
const shadowX = ref(0)
const shadowY = ref(12)
const shadowBlur = ref(32)
const shadowAlpha = ref(0.05)

let rafId: number | null = null

function handleMouseEnter(e: MouseEvent) {
  isTilting.value = true
  updateTilt(e)
}

function handleMouseMove(e: MouseEvent) {
  if (!wrapperRef.value) return
  isTilting.value = true
  updateTilt(e)
}

function updateTilt(e: MouseEvent) {
  if (!wrapperRef.value) return
  const rect = wrapperRef.value.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top

  const normX = Math.min(Math.max((x / rect.width) * 2 - 1, -1), 1)
  const normY = Math.min(Math.max((y / rect.height) * 2 - 1, -1), 1)

  if (rafId) cancelAnimationFrame(rafId)
  rafId = requestAnimationFrame(() => {
    rotX.value = -normY * 6.5
    rotY.value = normX * 7.5

    glareX.value = x
    glareY.value = y

    shadowX.value = -normX * 18
    shadowY.value = -normY * 16 + 18
    shadowBlur.value = 38
    shadowAlpha.value = 0.12
  })
}

function handleMouseLeave() {
  if (rafId) cancelAnimationFrame(rafId)
  isTilting.value = false
  rotX.value = 0
  rotY.value = 0
  shadowX.value = 0
  shadowY.value = 12
  shadowBlur.value = 32
  shadowAlpha.value = 0.05
}

const consoleStyle = computed(() => {
  if (isTilting.value) {
    return {
      transform: `perspective(1000px) rotateX(${rotX.value.toFixed(2)}deg) rotateY(${rotY.value.toFixed(2)}deg) translateZ(8px) scale3d(1.012, 1.012, 1.012)`,
      boxShadow: `${shadowX.value.toFixed(1)}px ${shadowY.value.toFixed(1)}px ${shadowBlur.value}px rgba(17, 17, 17, ${shadowAlpha.value})`,
      transition: 'transform 0.08s ease-out, box-shadow 0.08s ease-out',
    }
  }
  return {
    transform: 'perspective(1000px) rotateX(0deg) rotateY(0deg) translateZ(0) scale3d(1, 1, 1)',
    boxShadow: '0 12px 32px rgba(17, 17, 17, 0.05)',
    transition: 'transform 0.85s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.85s cubic-bezier(0.34, 1.56, 0.64, 1)',
  }
})

const glareStyle = computed(() => {
  return {
    opacity: isTilting.value ? 1 : 0,
    background: `radial-gradient(circle 420px at ${glareX.value}px ${glareY.value}px, rgba(255, 255, 255, 0.72) 0%, rgba(255, 255, 255, 0.28) 26%, rgba(240, 248, 255, 0.08) 52%, transparent 72%)`,
    transition: isTilting.value ? 'opacity 0.2s ease-out' : 'opacity 0.65s cubic-bezier(0.25, 1, 0.5, 1)',
  }
})

const rimStyle = computed(() => {
  return {
    opacity: isTilting.value ? 1 : 0,
    background: `radial-gradient(circle 320px at ${glareX.value}px ${glareY.value}px, rgba(255, 255, 255, 0.95) 0%, rgba(180, 210, 240, 0.5) 35%, transparent 75%) border-box`,
    transition: isTilting.value ? 'opacity 0.2s ease-out' : 'opacity 0.65s cubic-bezier(0.25, 1, 0.5, 1)',
  }
})
</script>

<template>
  <div
    ref="wrapperRef"
    class="cluster-console-wrapper"
    @mouseenter="handleMouseEnter"
    @mousemove="handleMouseMove"
    @mouseleave="handleMouseLeave"
  >
    <div
      class="cluster-console"
      :class="{ 'is-tilting': isTilting }"
      :style="consoleStyle"
    >
      <!-- 轮询与刷新时的动态激光扫描线 (Scanline Sweep) -->
      <div v-if="isSweeping" class="console-scanline"></div>

      <!-- 表面掠过的径向镜面高光 (Radial Specular Glare) -->
      <div class="console-glare" :style="glareStyle"></div>

      <!-- 边缘镜面微光反射 (Specular Rim Glow) -->
      <div class="console-rim" :style="rimStyle"></div>

      <!-- 控制台顶栏：状态指示灯与全局遥测 -->
      <div class="console-head">
        <div class="head-left">
          <span class="live-dot" :class="{ 'is-online': cluster.onlineNodes > 0 }"></span>
          <span class="head-title mono">// CLUSTER TELEMETRY</span>
          <span class="cluster-pill mono" :class="{ 'has-online': cluster.onlineNodes > 0 }">
            {{ cluster.onlineNodes }}/{{ cluster.totalNodes }} 在线
          </span>
        </div>

        <div class="head-right mono">
          <span class="sync-sec">POLL: {{ countdown }}s · {{ latency }}ms</span>
          <button
            class="btn-refresh"
            type="button"
            :disabled="loading"
            title="刷新遥测数据"
            @click="handleManualRefresh"
          >
            <svg
              class="refresh-icon"
              :class="{ 'is-spinning': loading }"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <polyline points="23 4 23 10 17 10" />
              <polyline points="1 20 1 14 7 14" />
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
            </svg>
          </button>
        </div>
      </div>

      <!-- 3 大核心评测节点 -->
      <div class="nodes-list">
        <div
          v-for="node in cluster.nodes"
          :key="node.id"
          class="node-card"
          :class="`is-${node.status.toLowerCase()}`"
        >
          <!-- 节点头部：名称与状态徽标 -->
          <div class="node-row-top">
            <div class="node-name-wrap">
              <span class="node-tag mono">{{ node.type }}</span>
              <span class="node-name">{{ node.name }}</span>
            </div>

            <span class="status-badge mono">
              <span class="status-pip"></span>
              <span>{{ node.statusLabel }}</span>
            </span>
          </div>

          <!-- 节点底栏：引擎架构与并发负载槽 -->
          <div class="node-row-bottom">
            <span class="node-engine mono">{{ node.typeLabel }}</span>

            <div class="load-meter mono">
              <span class="load-label">负载:</span>
              <div class="load-bars">
                <span
                  v-for="i in node.maxConcurrency"
                  :key="i"
                  class="load-pip"
                  :class="{ 'is-filled': i <= (node.currentTasks || 0) }"
                ></span>
              </div>
              <span class="load-count">{{ node.currentTasks || 0 }}/{{ node.maxConcurrency }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 底栏指标：状态注记与真实队列绑定 -->
      <div class="console-foot mono">
        <div class="foot-item">
          <span class="foot-dot"></span>
          <span>CGROUPS V2 · 独立临时库 · 自动容错</span>
        </div>
        <span class="foot-queue" :class="{ 'has-queue': (cluster.pendingTasks || 0) > 0 }">
          QUEUE: {{ cluster.pendingTasks || 0 }} PENDING
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cluster-console-wrapper {
  width: 100%;
  perspective: 1200px;
  display: flex;
  position: relative;
}

.cluster-console {
  width: 100%;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 0;
  display: flex;
  flex-direction: column;
  box-shadow: 0 12px 32px rgba(17, 17, 17, 0.05);
  user-select: none;
  position: relative;
  overflow: hidden;
  transform-style: preserve-3d;
  will-change: transform, box-shadow;
}

/* 动态激光扫描线 (Scanline Sweep) */
.console-scanline {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(64, 192, 87, 0.8), rgba(56, 217, 169, 0.95), transparent);
  box-shadow: 0 0 8px rgba(64, 192, 87, 0.6);
  pointer-events: none;
  z-index: 30;
  animation: scan-down 0.85s cubic-bezier(0.4, 0, 0.2, 1) forwards;
}

@keyframes scan-down {
  0% { top: 0; opacity: 0; }
  15% { opacity: 1; }
  85% { opacity: 0.8; }
  100% { top: 100%; opacity: 0; }
}

/* 表面掠过的径向镜面高光 */
.console-glare {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 25;
  border-radius: 0;
}

/* 边缘镜面微光反射 */
.console-rim {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 26;
  border-radius: 0;
  padding: 1px;
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, 'PingFang SC', 'Microsoft YaHei', monospace;
}

/* 顶栏 */
.console-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  gap: 12px;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 0;
  background: #adb5bd;
  transition: all 0.25s ease;
}

.live-dot.is-online {
  background: var(--success);
  box-shadow: 0 0 6px rgba(47, 158, 68, 0.7);
  animation: live-dot-pulse 2s ease-in-out infinite;
}

@keyframes live-dot-pulse {
  0%, 100% {
    opacity: 1;
    box-shadow: 0 0 8px rgba(47, 158, 68, 0.85);
  }
  50% {
    opacity: 0.5;
    box-shadow: 0 0 2px rgba(47, 158, 68, 0.3);
  }
}

.head-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: 0.6px;
}

.cluster-pill {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink-secondary);
  transition: all 0.25s ease;
}

.cluster-pill.has-online {
  border-color: #b2f2bb;
  color: var(--success);
  background: var(--success-bg);
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sync-sec {
  font-size: 11px;
  color: var(--ink-tertiary);
}

.btn-refresh {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 0;
  color: var(--ink-secondary);
  cursor: pointer;
  padding: 0;
  transition: all 0.15s ease;
}

.btn-refresh:hover:not(:disabled) {
  border-color: var(--ink);
  color: var(--ink);
  background: var(--surface-hover);
}

.btn-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.refresh-icon {
  width: 13px;
  height: 13px;
}

.refresh-icon.is-spinning {
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 节点列表 */
.nodes-list {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.node-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 15px 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  transition: all 0.2s ease;
}

.node-card:hover {
  border-color: var(--line-strong);
  background: #ffffff;
  box-shadow: 0 4px 14px rgba(17, 17, 17, 0.05);
}

.node-row-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.node-name-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.node-tag {
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 6px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink);
}

.node-name {
  font-size: 14.5px;
  font-weight: 700;
  color: var(--ink);
}

/* 状态徽标与心跳呼吸 */
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink-tertiary);
  transition: all 0.2s ease;
}

.status-pip {
  width: 5px;
  height: 5px;
  border-radius: 0;
  background: #ced4da;
  transition: all 0.2s ease;
}

/* 掉线 */
.node-card.is-offline .status-badge {
  border-color: var(--line);
  color: var(--ink-tertiary);
}

/* 空闲存活（带柔和心跳呼吸） */
.node-card.is-idle .status-badge {
  border-color: #b2f2bb;
  background: var(--success-bg);
  color: var(--success);
  font-weight: 600;
}
.node-card.is-idle .status-pip {
  background: var(--success);
  box-shadow: 0 0 4px rgba(47, 158, 68, 0.7);
  animation: pip-breathe 2s ease-in-out infinite;
}

@keyframes pip-breathe {
  0%, 100% {
    transform: scale(1);
    opacity: 1;
  }
  50% {
    transform: scale(0.85);
    opacity: 0.55;
  }
}

/* 测评中 */
.node-card.is-judging .status-badge {
  border-color: #ffd8a8;
  background: #fff4e6;
  color: #e67700;
  font-weight: 700;
}
.node-card.is-judging .status-pip {
  background: #e67700;
  box-shadow: 0 0 6px rgba(230, 119, 0, 0.85);
  animation: pip-judging 0.9s ease-in-out infinite alternate;
}

@keyframes pip-judging {
  from { opacity: 0.6; transform: scale(0.9); }
  to { opacity: 1; transform: scale(1.15); }
}

/* 节点第二行：引擎与负载 */
.node-row-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.node-engine {
  font-size: 11.5px;
  color: var(--ink-secondary);
}

.load-meter {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}

.load-label {
  color: var(--ink-tertiary);
}

.load-bars {
  display: flex;
  gap: 3px;
}

.load-pip {
  width: 10px;
  height: 6px;
  border-radius: 0;
  background: #ffffff;
  border: 1px solid var(--line);
  transition: all 0.25s ease;
}

/* 真实任务点亮 */
.load-pip.is-filled {
  background: var(--success);
  border-color: var(--success);
  box-shadow: 0 0 4px rgba(47, 158, 68, 0.6);
}



.load-count {
  font-weight: 600;
  color: var(--ink);
  min-width: 24px;
}

/* 底栏注记 */
.console-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px;
  background: var(--surface);
  border-top: 1px solid var(--line);
  font-size: 11px;
  color: var(--ink-tertiary);
}

.foot-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.foot-dot {
  width: 4px;
  height: 4px;
  border-radius: 0;
  background: #adb5bd;
}

.foot-queue {
  color: var(--ink-secondary);
  font-weight: 600;
  transition: all 0.2s ease;
}

.foot-queue.has-queue {
  color: #e67700;
  font-weight: 700;
}
</style>
