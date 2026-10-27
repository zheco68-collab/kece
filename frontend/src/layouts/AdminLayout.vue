<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import ConfirmModal from '@/components/ConfirmModal.vue'
import ToastHost from '@/components/ToastHost.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

interface NavSection {
  path: string
  label: string
  icon: string
  adminOnly?: boolean
}

const navSections: NavSection[] = [
  { path: '/admin/questions', label: '编程题管理', icon: 'code' },
  { path: '/admin/db-questions', label: '数据库题管理', icon: 'database' },
  { path: '/admin/objective-questions', label: '客观题管理', icon: 'checklist' },
  { path: '/admin/problem-sets', label: '题集管理', icon: 'problemset' },
  { path: '/admin/exams', label: '考试与组卷', icon: 'exam' },
  { path: '/admin/practices', label: '随堂课练', icon: 'practice' },
  { path: '/admin/submissions', label: '提交监控', icon: 'monitor' },
  { path: '/admin/users', label: '用户管理', icon: 'users', adminOnly: true },
]

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

const currentTitle = computed(() => {
  return (route.meta?.title as string) || '控制台'
})

function logout() {
  userStore.clearAuth()
  router.push('/login')
}
</script>

<template>
  <div class="admin-layout">
    <!-- 左侧独立侧边栏 -->
    <aside class="admin-sidebar">
      <div class="sidebar-header">
        <RouterLink to="/" class="brand-link" title="返回前台首页">
          <img src="/kece-logo.png" alt="KECE 课测" class="brand-logo" />
        </RouterLink>
        <div class="console-tag">CONSOLE</div>
      </div>

      <nav class="sidebar-nav">
        <RouterLink
          v-for="s in navSections"
          v-show="!s.adminOnly || userStore.hasRole('ADMIN')"
          :key="s.path"
          :to="s.path"
          class="nav-item"
          :class="{ active: isActive(s.path) }"
        >
          <!-- 编程题图标 -->
          <svg v-if="s.icon === 'code'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="16 18 22 12 16 6" />
            <polyline points="8 6 2 12 8 18" />
          </svg>
          <!-- 数据库题图标 -->
          <svg v-else-if="s.icon === 'database'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <ellipse cx="12" cy="5" rx="9" ry="3" />
            <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
            <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
          </svg>
          <!-- 客观题图标 -->
          <svg v-else-if="s.icon === 'checklist'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
          </svg>
          <!-- 题集管理图标 -->
          <svg v-else-if="s.icon === 'problemset'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
          </svg>
          <!-- 考试管理图标 -->
          <svg v-else-if="s.icon === 'exam'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="9" />
            <polyline points="12 6 12 12 15 15" />
            <line x1="12" y1="1" x2="12" y2="3" />
          </svg>
          <!-- 随堂课练图标 -->
          <svg v-else-if="s.icon === 'practice'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M2 3h20" />
            <path d="M21 3v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V3" />
            <path d="m7 21 5-5 5 5" />
          </svg>
          <!-- 提交监控图标 -->
          <svg v-else-if="s.icon === 'monitor'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
          </svg>
          <!-- 用户管理图标 -->
          <svg v-else-if="s.icon === 'users'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
            <circle cx="9" cy="7" r="4" />
            <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
            <path d="M16 3.13a4 4 0 0 1 0 7.75" />
          </svg>
          <span class="nav-label">{{ s.label }}</span>
        </RouterLink>
      </nav>

      <!-- 侧边栏底部：用户信息与前台快捷导航 -->
      <div class="sidebar-footer">
        <div class="user-block">
          <div class="user-meta">
            <span class="user-name">{{ userStore.userInfo?.username }}</span>
            <span class="role-badge">
              {{ userStore.hasRole('ADMIN') ? '管理员' : '出题人' }}
            </span>
          </div>
        </div>

        <div class="sidebar-actions">
          <RouterLink to="/" class="action-btn back-front-btn">
            <svg class="action-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            <span>返回前台平台</span>
          </RouterLink>
          <button class="action-btn logout-btn" @click="logout">
            <svg class="action-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            <span>退出登录</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- 右侧主体管理工作区 -->
    <div class="admin-main-container">
      <header class="admin-topbar">
        <div class="topbar-left">
          <div class="breadcrumb">
            <span class="bc-root">控制台</span>
            <span class="bc-sep">/</span>
            <span class="bc-current">{{ currentTitle }}</span>
          </div>
        </div>
        <div class="topbar-right">
          <span class="system-status">
            <span class="status-indicator"></span>
            <span>评测集群在线</span>
          </span>
          <RouterLink to="/" class="front-link">
            <span>前台主站</span>
            <svg class="link-arrow" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 1h8v8M11 1 5 7" />
            </svg>
          </RouterLink>
        </div>
      </header>

      <main class="admin-content">
        <RouterView />
      </main>

      <footer class="admin-footer">
        KECE 课测 · 管理控制系统 (v0.2)
      </footer>
    </div>

    <!-- 全局提示与确认框单例宿主 -->
    <ToastHost />
    <ConfirmModal />
  </div>
</template>

<style scoped>
/* =========================================
   AdminLayout 整体框架（纯直角工程美学）
   ========================================= */
.admin-layout {
  display: flex;
  min-height: 100vh;
  background: var(--bg);
}

/* =========================================
   独立左侧边栏
   ========================================= */
.admin-sidebar {
  width: 230px;
  background: #ffffff;
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  flex-shrink: 0;
  z-index: 100;
}

.sidebar-header {
  padding: 16px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
  height: 58px;
}

.brand-link {
  display: flex;
  align-items: center;
}

.brand-logo {
  height: 28px;
  width: auto;
  display: block;
}

.console-tag {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  background: #111111;
  color: #ffffff;
  border-radius: 0;
  letter-spacing: 0.8px;
}

/* 导航项目列表 */
.sidebar-nav {
  flex: 1;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  color: var(--ink-secondary);
  font-size: 14px;
  font-weight: 500;
  border-radius: 0;
  transition: all 0.15s ease;
  text-decoration: none;
  border-left: 3px solid transparent;
}

.nav-item:hover {
  background: #f4f5f7;
  color: var(--ink);
}

.nav-item.active {
  background: #18181b;
  color: #ffffff;
  border-left-color: #000000;
  font-weight: 600;
}

.nav-icon {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}

.nav-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 侧边栏底部 */
.sidebar-footer {
  padding: 16px 14px;
  border-top: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: #fafafa;
}

.user-block {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.user-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}

.user-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-badge {
  font-size: 11px;
  padding: 1px 7px;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink-secondary);
  border-radius: 0;
  white-space: nowrap;
}

.sidebar-actions {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  font-size: 13px;
  color: var(--ink-secondary);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0;
  cursor: pointer;
  text-decoration: none;
  transition: all 0.15s ease;
}

.action-btn:hover {
  background: #ffffff;
  border-color: var(--line);
  color: var(--ink);
}

.action-icon {
  width: 15px;
  height: 15px;
}

.back-front-btn {
  font-weight: 600;
}

.logout-btn:hover {
  color: var(--danger);
}

/* =========================================
   右侧主体区域
   ========================================= */
.admin-main-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow-x: hidden;
}

.admin-topbar {
  height: 58px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--line);
  padding: 0 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  position: sticky;
  top: 0;
  z-index: 50;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
}

.bc-root {
  color: var(--ink-tertiary);
}

.bc-sep {
  color: var(--line);
}

.bc-current {
  color: var(--ink);
  font-weight: 600;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 20px;
  font-size: 13px;
}

.system-status {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.status-indicator {
  width: 6px;
  height: 6px;
  background: var(--success);
  border-radius: 0;
  box-shadow: 0 0 6px rgba(47, 158, 68, 0.6);
}

.front-link {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-secondary);
  text-decoration: none;
  padding: 4px 10px;
  border: 1px solid var(--line);
  background: #ffffff;
  border-radius: 0;
  transition: all 0.15s ease;
}

.front-link:hover {
  color: var(--ink);
  border-color: #999999;
}

.link-arrow {
  width: 10px;
  height: 10px;
}

/* 页面内容区 */
.admin-content {
  flex: 1;
  padding: 24px 28px 40px;
}

/* 优化管理端内部的 container 布局，使其充满主工作区 */
:deep(.admin.container),
:deep(.pset-edit),
:deep(.pset-edit.container),
:deep(.admin-problem-sets.container),
:deep(.admin-practices.container),
:deep(.admin-exams.container) {
  max-width: 100% !important;
  padding: 0 !important;
  margin: 0 !important;
}

.admin-footer {
  border-top: 1px solid var(--line);
  padding: 16px 28px;
  font-size: 12px;
  color: var(--ink-tertiary);
  text-align: right;
  background: #ffffff;
}

/* 移动端/窄屏响应式折叠 */
@media (max-width: 860px) {
  .admin-sidebar {
    width: 64px;
  }
  .sidebar-header .brand-logo,
  .console-tag,
  .nav-label,
  .user-block,
  .action-btn span {
    display: none;
  }
  .sidebar-header {
    justify-content: center;
    padding: 12px 0;
  }
  .nav-item {
    justify-content: center;
    padding: 12px 0;
    border-left: none;
    border-bottom: 3px solid transparent;
  }
  .nav-item.active {
    border-bottom-color: #000000;
  }
  .action-btn {
    justify-content: center;
  }
  .admin-content {
    padding: 16px;
  }
}
</style>

