<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import ToastHost from '@/components/ToastHost.vue'

const route = useRoute()

// 动态面包屑导航计算：工业风等宽标签，如 KECE // PORTAL / RANKINGS
const currentSection = computed(() => {
  const path = route.path
  if (path === '/' || route.name === 'home') return 'HOME'
  if (path.startsWith('/rankings') || path === '/leaderboard' || path === '/rank') return 'RANKINGS'
  if (path.startsWith('/problem-sets')) return 'PROBLEM SETS'
  if (path.startsWith('/practice')) return 'PRACTICE'
  if (path.startsWith('/exams')) return 'EXAMS'
  if (path.startsWith('/questions')) return 'QUESTIONS'
  if (path.startsWith('/database')) return 'DATABASE'
  if (path.startsWith('/objective')) return 'OBJECTIVE'
  if (path.startsWith('/announcements')) return 'ANNOUNCEMENTS'
  if (path.startsWith('/profile')) return 'PROFILE'
  if (route.meta?.title) return String(route.meta.title).toUpperCase()
  return String(route.name || 'PORTAL').toUpperCase()
})
</script>

<template>
  <div class="default-layout layout">
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
          <div class="telemetry-pill mono" title="集群系统遥测状态：全部节点正常运行">
            <span class="dot-online"></span>
            <span>CLUSTER: 2/2 ONLINE</span>
          </div>
        </div>
      </header>

      <!-- 路由视图与主体工作区：保留原有 1200px 版心居中兼容性 -->
      <main class="main-content main">
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
  background: var(--bg, #fbfbfb);
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
  border-bottom: 1px solid var(--line, #e2e8f0);
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
  color: var(--ink-secondary, #64748b);
  letter-spacing: 0.02em;
}

.bc-brand {
  color: var(--ink-tertiary, #94a3b8);
  font-weight: 600;
}

.bc-module {
  color: var(--ink-secondary, #64748b);
  font-weight: 600;
}

.bc-sep {
  color: var(--ink-tertiary, #94a3b8);
}

.bc-current {
  color: var(--ink, #0f172a);
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
  background: #ecfdf5;
  color: #047857;
  border: 1px solid #a7f3d0;
  padding: 3px 8px;
  border-radius: 0;
  font-weight: 600;
  letter-spacing: 0.04em;
  user-select: none;
}

.dot-online {
  width: 6px;
  height: 6px;
  background: #10b981;
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.6);
  border-radius: 50%;
  display: inline-block;
}

.main-content {
  flex: 1;
  width: 100%;
}

.footer {
  border-top: 1px solid var(--line, #e2e8f0);
  padding: 24px;
  text-align: center;
  font-size: 13px;
  color: var(--ink-tertiary, #94a3b8);
}
</style>
