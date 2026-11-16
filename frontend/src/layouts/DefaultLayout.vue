<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import ToastHost from '@/components/ToastHost.vue'
import { provideJudgeCluster } from '@/features/judging/composables/useJudgeCluster'

const route = useRoute()
const cluster = provideJudgeCluster(30000)

// 动态面包屑导航计算：工业风等宽标签，如 KECE // PORTAL / RANKINGS
const currentSection = computed(() => {
  const path = route.path
  if (route.query.practiceCode) {
    return `PRACTICE // #${String(route.query.practiceCode).toUpperCase()}`
  }
  if (route.query.setId) {
    return 'PROBLEM SETS'
  }
  if (path === '/' || route.name === 'home') return 'HOME'
  if (path.startsWith('/rankings') || path === '/leaderboard' || path === '/rank') return 'RANKINGS'
  if (path.startsWith('/problem-sets')) {
    const cat = route.query.category
    if (cat === 'ALGO') return 'PROBLEM SETS // ALGO'
    if (cat === 'DB') return 'PROBLEM SETS // SQL'
    if (cat === 'OBJECTIVE') return 'PROBLEM SETS // QUIZ'
    return 'PROBLEM SETS'
  }
  if (path.startsWith('/practice')) return 'PRACTICE'
  if (path.startsWith('/exams')) return 'EXAMS'
  if (path.startsWith('/questions')) return 'QUESTIONS'
  if (path.startsWith('/database')) return 'DATABASE'
  if (path.startsWith('/objective')) return 'OBJECTIVE'
  if (path.startsWith('/announcements')) return 'ANNOUNCEMENTS'
  if (path.startsWith('/account/settings')) return 'ACCOUNT // SETTINGS'
  if (path.startsWith('/profile')) return 'PROFILE'
  if (route.meta?.title) return String(route.meta.title).toUpperCase()
  return String(route.name || 'PORTAL').toUpperCase()
})
</script>

<template>
  <div class="default-layout layout">
    <a href="#main-content" class="skip-link">跳到主要内容</a>

    <!-- 左侧粘性侧边栏 (由 AppSidebar 自带粘性与固定高度) -->
    <AppSidebar />

    <!-- 右侧自适应主内容区 -->
    <div class="layout-main-area">
      <!-- 50px 极简辅助顶栏 (直角工业风设计，底部 1px 细边框，高透背景) -->
      <header class="layout-topbar">
        <div class="breadcrumb-trail">
          <span class="bc-brand mono">KECE //</span>
          <span class="bc-module">PORTAL</span>
          <span class="bc-sep mono">/</span>
          <span class="bc-current mono">{{ currentSection }}</span>
        </div>
        <div class="topbar-right">
          <div
            class="telemetry-pill mono"
            role="status"
            aria-live="polite"
            :class="{
              'is-stale': cluster.isStale.value,
              'is-degraded': cluster.isUnavailable.value || cluster.readyPools.value === 0,
            }"
            :title="
              cluster.isUnavailable.value
                ? '评测集群状态暂不可用'
                : cluster.isStale.value
                  ? '评测集群数据已过期，正在尝试重新连接'
                  : `评测集群就绪：${cluster.readyPools.value}/${cluster.totalPools.value} 专池`
            "
          >
            <span
              class="dot-status"
              aria-hidden="true"
              :class="{
                'dot-online': !cluster.isStale.value && !cluster.isUnavailable.value && cluster.readyPools.value > 0,
                'dot-stale': cluster.isStale.value && !cluster.isUnavailable.value,
                'dot-offline': cluster.isUnavailable.value || cluster.readyPools.value === 0,
              }"
            ></span>
            <span v-if="cluster.initialLoading.value">CLUSTER: LOADING...</span>
            <span v-else-if="cluster.isUnavailable.value">CLUSTER: UNAVAILABLE</span>
            <span v-else>
              POOLS: {{ cluster.readyPools.value }}/{{ cluster.totalPools.value }} READY
              <span v-if="cluster.isStale.value" class="stale-label">(STALE)</span>
            </span>
          </div>
        </div>
      </header>

      <!-- 路由视图与主体工作区：保留原有 1200px 版心居中兼容性 -->
      <main id="main-content" class="main-content main" tabindex="-1">
        <RouterView />
      </main>

      <!-- 底部极简版权 footer -->
      <footer class="footer">KECE 课测 · 多维度编程与技能评测平台</footer>
    </div>

    <!-- 全局提示与确认框宿主：各页面共用同一份单例状态 -->
    <ToastHost />
    <ConfirmModal />
  </div>
</template>

<style scoped>
.default-layout {
  min-height: 100vh;
  display: flex;
  background: var(--bg, #ffffff);
}

.skip-link {
  position: fixed;
  top: 12px;
  left: 16px;
  z-index: 200;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  background: #ffffff;
  color: var(--kece-accent-ink, #0f5f53);
  border: 2px solid var(--kece-accent, #137969);
  border-radius: 0;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}

.skip-link:focus-visible {
  width: auto;
  height: auto;
  margin: 0;
  padding: 8px 16px;
  overflow: visible;
  clip-path: none;
  box-shadow: var(--shadow-md, 0 8px 24px rgba(17, 17, 17, 0.06));
  outline: 2px solid var(--kece-accent, #137969);
  outline-offset: 2px;
}

.layout-main-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.layout-topbar {
  height: 50px;
  min-height: 50px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line, #ced4da);
  padding: 0 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  position: sticky;
  top: 0;
  z-index: 40;
  box-sizing: border-box;
}

.breadcrumb-trail {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
  color: var(--ink-secondary, #555555);
  letter-spacing: 0.02em;
}

.bc-brand {
  color: var(--ink-tertiary, #5c6370);
  font-weight: 600;
}

.bc-module {
  color: var(--ink-secondary, #555555);
  font-weight: 600;
}

.bc-sep {
  color: var(--ink-tertiary, #5c6370);
}

.bc-current {
  color: var(--ink, #111111);
  font-weight: 700;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.telemetry-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
  background: var(--kece-accent-soft, #eef6f4);
  color: var(--kece-accent-ink, #0f5f53);
  border: 1px solid var(--kece-accent, #137969);
  padding: 3px 8px;
  border-radius: 0;
  font-weight: 600;
  letter-spacing: 0.04em;
  user-select: none;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    border-color 0.2s ease;
}

.telemetry-pill.is-stale {
  background: var(--kece-warning-soft, #fff7ed);
  color: #9a3412;
  border-color: #d97706;
}

.telemetry-pill.is-degraded {
  background: var(--kece-danger-soft, #fdf2f4);
  color: var(--kece-danger, #c4314b);
  border-color: var(--kece-danger, #c4314b);
}

.dot-status {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

.dot-status.dot-online {
  background: var(--kece-accent, #137969);
  box-shadow: 0 0 6px rgba(19, 121, 105, 0.45);
}

.dot-status.dot-stale {
  background: #d97706;
  box-shadow: 0 0 6px rgba(217, 119, 6, 0.45);
}

.dot-status.dot-offline {
  background: var(--kece-danger, #c4314b);
  box-shadow: 0 0 6px rgba(196, 49, 75, 0.45);
}

.stale-label {
  font-size: 11px;
  margin-left: 2px;
}

.main-content {
  flex: 1;
  width: 100%;
}

.footer {
  border-top: 1px solid var(--line, #ced4da);
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: var(--ink-tertiary, #5c6370);
}

@media (prefers-reduced-motion: reduce) {
  .telemetry-pill {
    transition: none;
  }
}
</style>
