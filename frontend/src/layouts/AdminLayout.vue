<script setup lang="ts">
import { computed, ref, watch } from 'vue'
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

interface NavGroup {
  key: string
  label: string
  items: NavSection[]
}

const overviewItem: NavSection = { path: '/admin/overview', label: '概况', icon: 'overview' }

const collapsibleGroups: NavGroup[] = [
  {
    key: 'bank',
    label: '题库管理',
    items: [
      { path: '/admin/questions', label: '编程题', icon: 'code' },
      { path: '/admin/db-questions', label: '数据库题', icon: 'database' },
      { path: '/admin/objective-questions', label: '客观题', icon: 'checklist' },
      { path: '/admin/problem-sets', label: '题集', icon: 'problemset' },
    ],
  },
  {
    key: 'teaching',
    label: '教学与考试',
    items: [
      { path: '/admin/exams', label: '考试与组卷', icon: 'exam' },
      { path: '/admin/practices', label: '随堂课练', icon: 'practice' },
    ],
  },
  {
    key: 'ops',
    label: '评测与内容',
    items: [
      { path: '/admin/submissions', label: '提交监控', icon: 'monitor' },
      { path: '/admin/judgers', label: '评测机管理', icon: 'server', adminOnly: true },
      { path: '/admin/announcements', label: '公告管理', icon: 'announcement' },
      { path: '/admin/solutions', label: '题解管理', icon: 'solution', adminOnly: true },
    ],
  },
  {
    key: 'system',
    label: '系统管理',
    items: [
      { path: '/admin/users', label: '用户管理', icon: 'users', adminOnly: true },
    ],
  },
]

const visibleGroups = computed(() =>
  collapsibleGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.adminOnly || userStore.hasRole('ADMIN')),
    }))
    .filter((group) => group.items.length > 0),
)

const expandedGroups = ref<Record<string, boolean>>({})
const sidebarOpen = ref(false)
watch(() => route.fullPath, () => {
  sidebarOpen.value = false
})

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

function groupHasActive(group: NavGroup): boolean {
  return group.items.some((item) => isActive(item.path))
}

function isGroupOpen(key: string): boolean {
  return Boolean(expandedGroups.value[key])
}

function toggleGroup(key: string): void {
  expandedGroups.value[key] = !isGroupOpen(key)
}

watch(
  [() => route.path, visibleGroups],
  () => {
    for (const group of visibleGroups.value) {
      if (groupHasActive(group)) {
        expandedGroups.value[group.key] = true
      }
    }
  },
  { immediate: true },
)

const currentTitle = computed(() => {
  return (route.meta?.title as string) || '控制台'
})

function logout() {
  userStore.clearAuth()
  router.push('/login')
}
</script>

<template>
  <div class="admin-layout" @keydown.esc="sidebarOpen = false">
    <!-- 左侧独立侧边栏 -->
    <button v-if="sidebarOpen" class="sidebar-backdrop" aria-label="关闭导航菜单" @click="sidebarOpen = false"></button>
    <aside id="admin-navigation" class="admin-sidebar" :class="{ 'is-open': sidebarOpen }">
      <div class="sidebar-header">
        <RouterLink to="/" class="brand-link" title="返回前台首页">
          <img src="/kece-logo.png" alt="KECE 课测" class="brand-logo" />
        </RouterLink>
        <div class="console-tag">CONSOLE</div>
      </div>

      <nav class="sidebar-nav" aria-label="后台管理导航">
        <RouterLink
          :to="overviewItem.path"
          class="nav-item nav-item-flat"
          :class="{ active: isActive(overviewItem.path) }"
          :aria-current="isActive(overviewItem.path) ? 'page' : undefined"
        >
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z" />
          </svg>
          <span class="nav-label">{{ overviewItem.label }}</span>
        </RouterLink>

        <div class="nav-divider" aria-hidden="true"></div>

        <div v-for="group in visibleGroups" :key="group.key" class="nav-group">
          <button
            type="button"
            class="nav-group-trigger"
            :class="{ 'has-active': groupHasActive(group), 'is-open': isGroupOpen(group.key) }"
            :aria-expanded="isGroupOpen(group.key)"
            :aria-controls="`nav-group-${group.key}`"
            @click="toggleGroup(group.key)"
          >
            <span class="nav-group-label">{{ group.label }}</span>
            <svg
              class="nav-chevron"
              :class="{ 'is-open': isGroupOpen(group.key) }"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              aria-hidden="true"
            >
              <path d="m4 6 4 4 4-4" />
            </svg>
          </button>

          <div
            v-show="isGroupOpen(group.key)"
            :id="`nav-group-${group.key}`"
            class="nav-group-items"
          >
            <RouterLink
              v-for="s in group.items"
              :key="s.path"
              :to="s.path"
              class="nav-item"
              :class="{ active: isActive(s.path) }"
              :aria-current="isActive(s.path) ? 'page' : undefined"
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
              <svg v-else-if="s.icon === 'checklist'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="m3 6 2 2 4-4M12 6h9M12 12h9M12 18h9M4 10h4v4H4zM4 16h4v4H4z" />
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
              <!-- 评测机管理服务器图标 -->
              <svg v-else-if="s.icon === 'server'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="2" y="2" width="20" height="8" rx="0" ry="0" />
                <rect x="2" y="14" width="20" height="8" rx="0" ry="0" />
                <line x1="6" y1="6" x2="6.01" y2="6" />
                <line x1="6" y1="18" x2="6.01" y2="18" />
              </svg>
              <!-- 公告管理图标 -->
              <svg v-else-if="s.icon === 'announcement'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
                <path d="M13.73 21a2 2 0 0 1-3.46 0" />
              </svg>
              <svg v-else-if="s.icon === 'solution'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M9 18v-1c0-1.2-.7-2.2-1.5-3a6.5 6.5 0 1 1 9 0c-.8.8-1.5 1.8-1.5 3v1zM10 22h4" />
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
          </div>
        </div>
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
          <RouterLink to="/account/settings" class="action-btn">
            <svg class="action-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
            <span>账户设置</span>
          </RouterLink>
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
          <button class="menu-toggle" type="button" aria-controls="admin-navigation" :aria-expanded="sidebarOpen" :aria-label="sidebarOpen ? '关闭导航菜单' : '打开导航菜单'" @click="sidebarOpen = !sidebarOpen">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
          </button>
          <div class="breadcrumb">
            <span class="bc-root">控制台</span>
            <span class="bc-sep">/</span>
            <span class="bc-current">{{ currentTitle }}</span>
          </div>
        </div>
        <div class="topbar-right">
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
        KECE 课测 · 管理控制台
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
  min-height: 100dvh;
  background: var(--surface);
}

/* =========================================
   独立左侧边栏
   ========================================= */
.admin-sidebar {
  width: 236px;
  background: #ffffff;
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100dvh;
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
  padding: 12px 10px 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
}

.nav-divider {
  height: 1px;
  background: var(--kece-border, #e4e7ec);
  margin: 4px 6px 6px;
}

.nav-group {
  display: grid;
  gap: 2px;
}

.nav-group-trigger {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 600;
  border-radius: 0;
  background: transparent;
  border: 0;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.nav-group-trigger:hover {
  background: #f4f5f7;
  color: var(--ink);
}

.nav-group-trigger.has-active {
  color: var(--kece-accent-ink, #0f5f53);
}

.nav-group-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-chevron {
  width: 14px;
  height: 14px;
  color: var(--ink-tertiary);
  flex-shrink: 0;
  transition: transform 0.15s ease, color 0.15s ease;
}

.nav-chevron.is-open {
  transform: rotate(180deg);
  color: var(--ink-secondary);
}

.nav-group-items {
  display: grid;
  gap: 2px;
  padding-bottom: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  color: var(--ink-secondary);
  font-size: 14px;
  font-weight: 500;
  border-radius: 0;
  transition: background-color 0.15s ease, color 0.15s ease;
  text-decoration: none;
  border-left: 3px solid transparent;
}

.nav-item:hover {
  background: #f4f5f7;
  color: var(--ink);
}

.nav-item.active {
  background: var(--kece-accent-soft, #eef6f4);
  color: var(--kece-accent-ink, #0f5f53);
  border-left-color: var(--kece-accent, #137969);
  font-weight: 600;
}

.nav-item-flat {
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
  overflow-x: clip;
}

.admin-topbar {
  min-height: 58px;
  background: #fff;
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
  font-size: 14px;
}

.topbar-left { display: flex; align-items: center; gap: 14px; min-width: 0; }
.menu-toggle { display: none; }
.sidebar-backdrop { display: none; }

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
  min-width: 0;
}

.admin-layout :is(a, button):focus-visible {
  outline: 2px solid var(--kece-accent, #137969);
  outline-offset: 2px;
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
    position: fixed;
    left: 0;
    top: 0;
    height: 100dvh;
    width: min(280px, 85vw);
    transform: translateX(-100%);
    visibility: hidden;
    transition: transform 0.18s ease, visibility 0.18s;
  }
  .admin-sidebar.is-open { transform: translateX(0); visibility: visible; }
  .sidebar-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 90;
    border: 0;
    background: rgba(0, 0, 0, 0.4);
  }
  .menu-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 38px;
    height: 38px;
    border: 1px solid var(--line);
    border-radius: 0;
    background: #fff;
    color: var(--ink);
    cursor: pointer;
  }
  .menu-toggle svg { width: 20px; height: 20px; }
  .admin-topbar { padding: 0 16px; }
  .admin-content {
    padding: 16px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .admin-sidebar,
  .nav-chevron { transition: none; }
}
</style>

