<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const STORAGE_KEY = 'kece_sidebar_collapsed'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const emit = defineEmits<{
  (e: 'collapse-change', collapsed: boolean): void
}>()

function getInitialCollapsed(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true'
  } catch {
    return false
  }
}

// 侧边栏折叠状态：读取 localStorage 记忆或默认为展开 (false)
const isCollapsed = ref(getInitialCollapsed())

// 题库手风琴折叠状态（展开模式下可用）
const isQuestionsAccordionOpen = ref(true)

// 悬浮气泡防抖与状态（折叠模式下题库子菜单）
const isFlyoutVisible = ref(false)
let flyoutTimer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  emit('collapse-change', isCollapsed.value)
})

function toggleCollapse() {
  isCollapsed.value = !isCollapsed.value
  try {
    localStorage.setItem(STORAGE_KEY, String(isCollapsed.value))
  } catch {
    // 忽略异常
  }
  emit('collapse-change', isCollapsed.value)
}

// 题库及其子路径深层匹配
function isQuestionsActive(): boolean {
  const p = route.path
  return (
    p === '/questions' ||
    p.startsWith('/questions/') ||
    p === '/database' ||
    p.startsWith('/database/') ||
    p === '/objective' ||
    p.startsWith('/objective/') ||
    p === '/submissions' ||
    p.startsWith('/submissions/') ||
    p === '/database-submissions' ||
    p.startsWith('/database-submissions/')
  )
}

// 路由监听：若路由属于题库任一模块，自动展开手风琴
watch(
  () => route.path,
  () => {
    if (isQuestionsActive() && !isCollapsed.value) {
      isQuestionsAccordionOpen.value = true
    }
  },
  { immediate: true }
)

// 各导航项激活规则判断
function isHomeActive(): boolean {
  return route.path === '/'
}

function isProblemSetsActive(): boolean {
  return route.path === '/problem-sets' || route.path.startsWith('/problem-sets/')
}

function isPracticeActive(): boolean {
  return route.path === '/practice' || route.path.startsWith('/practice/')
}

function isExamsActive(): boolean {
  return route.path === '/exams' || route.path.startsWith('/exams/')
}

function isRankingsActive(): boolean {
  return (
    route.path === '/rankings' ||
    route.path.startsWith('/rankings/') ||
    route.path === '/leaderboard' ||
    route.path === '/rank'
  )
}

function isAnnouncementsActive(): boolean {
  return route.path === '/announcements' || route.path.startsWith('/announcements/')
}

function isProfileActive(): boolean {
  return route.path === '/profile' || route.path.startsWith('/profile/')
}

function isAdminActive(): boolean {
  return route.path === '/admin' || route.path.startsWith('/admin/')
}

function isSubQuestionActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

function toggleQuestionsAccordion(event: Event) {
  event.stopPropagation()
  isQuestionsAccordionOpen.value = !isQuestionsAccordionOpen.value
}

function onQuestionsClick() {
  if (isCollapsed.value) {
    // 折叠模式下直接进入题库主页
    router.push('/questions')
  } else {
    // 展开模式下：确保展开手风琴
    isQuestionsAccordionOpen.value = true
  }
}

// 折叠态 68px 悬浮气泡进入与移出控制
function onFlyoutMouseEnter() {
  if (!isCollapsed.value) return
  if (flyoutTimer) {
    clearTimeout(flyoutTimer)
    flyoutTimer = null
  }
  isFlyoutVisible.value = true
}

function onFlyoutMouseLeave() {
  if (!isCollapsed.value) return
  flyoutTimer = setTimeout(() => {
    isFlyoutVisible.value = false
  }, 120)
}

// 身份标签计算
const roleInfo = computed(() => {
  if (userStore.hasRole('ADMIN')) return { label: '管理员', class: 'role-admin' }
  if (userStore.hasRole('TEACHER')) return { label: '教师', class: 'role-teacher' }
  return { label: '学生', class: 'role-student' }
})

// 用户头像占位符
const userAvatarChar = computed(() => {
  return userStore.userInfo?.username?.charAt(0)?.toUpperCase() || 'U'
})

// 退出登录
function handleLogout() {
  userStore.clearAuth()
  router.push('/login')
}

defineExpose({
  isCollapsed,
  toggleCollapse,
})
</script>

<template>
  <aside
    class="app-sidebar"
    :class="{ 'is-collapsed': isCollapsed }"
    aria-label="前台主导航侧边栏"
  >
    <!-- 顶部品牌区 -->
    <div class="sidebar-header">
      <RouterLink to="/" class="brand-link" title="返回 KECE 课测首页">
        <img :src="'/kece-logo.png'" alt="KECE" class="brand-logo" />
        <span v-if="!isCollapsed" class="brand-title mono">KECE 课测</span>
      </RouterLink>
      <button
        class="collapse-toggle-btn"
        :title="isCollapsed ? '展开侧边栏' : '折叠侧边栏'"
        aria-label="折叠或展开侧边栏"
        @click="toggleCollapse"
      >
        <svg
          v-if="!isCollapsed"
          class="toggle-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
        >
          <polyline points="11 17 6 12 11 7" />
          <polyline points="18 17 13 12 18 7" />
        </svg>
        <svg
          v-else
          class="toggle-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
        >
          <polyline points="13 17 18 12 13 7" />
          <polyline points="6 17 11 12 6 7" />
        </svg>
      </button>
    </div>

    <!-- 中部 8 大导航项列表 -->
    <nav class="sidebar-nav">
      <!-- 1. 首页 (精准匹配 /) -->
      <RouterLink
        to="/"
        class="nav-item"
        :class="{ active: isHomeActive() }"
        :title="isCollapsed ? '首页' : undefined"
      >
        <div class="nav-icon-wrap">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M3 9.5L12 3l9 6.5V20a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9.5z" />
            <polyline points="9 21 9 12 15 12 15 21" />
          </svg>
        </div>
        <span v-if="!isCollapsed" class="nav-label">首页</span>
      </RouterLink>

      <!-- 2. 题库 (垂直手风琴子菜单 / 68px 悬浮气泡) -->
      <div
        class="nav-accordion-group"
        :class="{ 'has-flyout': isCollapsed }"
        @mouseenter="onFlyoutMouseEnter"
        @mouseleave="onFlyoutMouseLeave"
      >
        <RouterLink
          to="/questions"
          class="nav-item nav-accordion-trigger"
          :class="{ active: isQuestionsActive(), 'is-open': isQuestionsAccordionOpen && !isCollapsed }"
          :title="isCollapsed ? '题库' : undefined"
          @click="onQuestionsClick"
        >
          <div class="nav-icon-wrap">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <polyline points="16 18 22 12 16 6" />
              <polyline points="8 6 2 12 8 18" />
            </svg>
          </div>
          <span v-if="!isCollapsed" class="nav-label">题库</span>
          <button
            v-if="!isCollapsed"
            class="accordion-toggle-btn"
            :title="isQuestionsAccordionOpen ? '收起子菜单' : '展开子菜单'"
            aria-label="切换题库子菜单"
            @click.prevent="toggleQuestionsAccordion"
          >
            <svg
              class="accordion-arrow"
              :class="{ 'is-rotated': isQuestionsAccordionOpen }"
              viewBox="0 0 10 6"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
            >
              <path d="M1 1l4 4 4-4" />
            </svg>
          </button>
        </RouterLink>

        <!-- 展开模式：垂直手风琴折叠子菜单 -->
        <div
          v-if="!isCollapsed && isQuestionsAccordionOpen"
          class="accordion-content"
        >
          <RouterLink
            to="/questions"
            class="sub-nav-item"
            :class="{ active: isSubQuestionActive('/questions') }"
          >
            <span class="sub-code mono">ALGO</span>
            <span class="sub-label">算法编程</span>
          </RouterLink>
          <RouterLink
            to="/database"
            class="sub-nav-item"
            :class="{ active: isSubQuestionActive('/database') }"
          >
            <span class="sub-code mono">SQL</span>
            <span class="sub-label">数据库题</span>
          </RouterLink>
          <RouterLink
            to="/objective"
            class="sub-nav-item"
            :class="{ active: isSubQuestionActive('/objective') }"
          >
            <span class="sub-code mono">QUIZ</span>
            <span class="sub-label">客观题库</span>
          </RouterLink>
        </div>

        <!-- 折叠模式：68px 悬浮直角气泡菜单 (Flyout Popover) -->
        <div
          v-if="isCollapsed && isFlyoutVisible"
          class="flyout-popover"
        >
          <div class="flyout-header mono">// 题库分类</div>
          <RouterLink
            to="/questions"
            class="flyout-item"
            :class="{ active: isSubQuestionActive('/questions') }"
            @click="isFlyoutVisible = false"
          >
            <span class="sub-code mono">ALGO</span>
            <span class="flyout-label">算法编程</span>
          </RouterLink>
          <RouterLink
            to="/database"
            class="flyout-item"
            :class="{ active: isSubQuestionActive('/database') }"
            @click="isFlyoutVisible = false"
          >
            <span class="sub-code mono">SQL</span>
            <span class="flyout-label">数据库题</span>
          </RouterLink>
          <RouterLink
            to="/objective"
            class="flyout-item"
            :class="{ active: isSubQuestionActive('/objective') }"
            @click="isFlyoutVisible = false"
          >
            <span class="sub-code mono">QUIZ</span>
            <span class="flyout-label">客观题库</span>
          </RouterLink>
        </div>
      </div>

      <!-- 3. 题集 -->
      <RouterLink
        to="/problem-sets"
        class="nav-item"
        :class="{ active: isProblemSetsActive() }"
        :title="isCollapsed ? '题集' : undefined"
      >
        <div class="nav-icon-wrap">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
          </svg>
        </div>
        <span v-if="!isCollapsed" class="nav-label">题集</span>
      </RouterLink>

      <!-- 4. 课练 -->
      <RouterLink
        to="/practice"
        class="nav-item"
        :class="{ active: isPracticeActive() }"
        :title="isCollapsed ? '课练' : undefined"
      >
        <div class="nav-icon-wrap">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M2 3h20" />
            <path d="M21 3v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V3" />
            <path d="m7 21 5-5 5 5" />
          </svg>
        </div>
        <span v-if="!isCollapsed" class="nav-label">课练</span>
      </RouterLink>

      <!-- 5. 考试 -->
      <RouterLink
        to="/exams"
        class="nav-item"
        :class="{ active: isExamsActive() }"
        :title="isCollapsed ? '考试' : undefined"
      >
        <div class="nav-icon-wrap">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="12" cy="12" r="9" />
            <polyline points="12 6 12 12 15 15" />
            <line x1="12" y1="1" x2="12" y2="3" />
          </svg>
        </div>
        <span v-if="!isCollapsed" class="nav-label">考试</span>
      </RouterLink>

      <!-- 6. 排名 -->
      <RouterLink
        to="/rankings"
        class="nav-item"
        :class="{ active: isRankingsActive() }"
        :title="isCollapsed ? '排名' : undefined"
      >
        <div class="nav-icon-wrap">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <line x1="18" y1="20" x2="18" y2="10" />
            <line x1="12" y1="20" x2="12" y2="4" />
            <line x1="6" y1="20" x2="6" y2="14" />
            <line x1="2" y1="20" x2="22" y2="20" />
          </svg>
        </div>
        <span v-if="!isCollapsed" class="nav-label">排名</span>
      </RouterLink>

      <!-- 7. 公告栏 -->
      <RouterLink
        to="/announcements"
        class="nav-item"
        :class="{ active: isAnnouncementsActive() }"
        :title="isCollapsed ? '公告栏' : undefined"
      >
        <div class="nav-icon-wrap">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
            <path d="M13.73 21a2 2 0 0 1-3.46 0" />
          </svg>
        </div>
        <span v-if="!isCollapsed" class="nav-label">公告栏</span>
      </RouterLink>

      <!-- 8. 个人主页 -->
      <RouterLink
        to="/profile"
        class="nav-item"
        :class="{ active: isProfileActive() }"
        :title="isCollapsed ? '个人主页' : undefined"
      >
        <div class="nav-icon-wrap">
          <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
            <circle cx="12" cy="7" r="4" />
          </svg>
        </div>
        <span v-if="!isCollapsed" class="nav-label">个人主页</span>
      </RouterLink>

      <!-- 方案 C 特权管理控制台入口 (ADMINISTRATION) -->
      <!-- 核心鉴权安全：必须使用 v-if 严格节点级条件渲染，未登录游客与普通学生在 DOM 树中绝对不可渲染 -->
      <div
        v-if="userStore.isLoggedIn && (userStore.hasRole('ADMIN') || userStore.hasRole('TEACHER'))"
        class="admin-section"
      >
        <div class="admin-divider">
          <span v-if="!isCollapsed" class="admin-label mono">// ADMINISTRATION</span>
          <span v-else class="admin-divider-line"></span>
        </div>
        <RouterLink
          to="/admin"
          class="nav-item admin-nav-item"
          :class="{ active: isAdminActive() }"
          :title="isCollapsed ? '管理控制台' : undefined"
        >
          <div class="nav-icon-wrap">
            <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <rect x="2" y="3" width="20" height="14" />
              <line x1="8" y1="21" x2="16" y2="21" />
              <line x1="12" y1="17" x2="12" y2="21" />
              <path d="m7 8 3 3-3 3" />
              <path d="M12 14h5" />
            </svg>
          </div>
          <span v-if="!isCollapsed" class="nav-label">管理控制台</span>
          <svg
            v-if="!isCollapsed"
            class="admin-arrow-icon"
            viewBox="0 0 12 12"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
          >
            <path d="M2 6h8M6 2l4 4-4 4" />
          </svg>
        </RouterLink>
      </div>
    </nav>

    <!-- 底部用户卡片与直达主页交互 -->
    <div class="sidebar-footer" :class="{ 'is-collapsed': isCollapsed }">
      <!-- 已登录状态 -->
      <template v-if="userStore.isLoggedIn">
        <div class="user-card" :class="{ 'is-collapsed': isCollapsed }">
          <RouterLink
            to="/profile"
            class="user-avatar-link"
            title="查看个人主页"
          >
            <img
              v-if="userStore.userInfo?.avatarUrl"
              :src="userStore.userInfo.avatarUrl"
              class="user-avatar"
              alt="用户头像"
            />
            <span v-else class="user-avatar-placeholder mono">
              {{ userAvatarChar }}
            </span>
          </RouterLink>

          <!-- 展开态：展示用户名、身份标签与独立退出按钮 -->
          <div v-if="!isCollapsed" class="user-meta">
            <RouterLink
              to="/profile"
              class="user-name-link"
              :title="userStore.userInfo?.username || '个人主页'"
            >
              <span class="user-name">{{ userStore.userInfo?.username }}</span>
            </RouterLink>
            <span class="role-tag mono" :class="roleInfo.class">
              {{ roleInfo.label }}
            </span>
          </div>

          <button
            v-if="!isCollapsed"
            class="logout-btn mono"
            title="退出登录"
            @click="handleLogout"
          >
            退出
          </button>

          <!-- 折叠态：紧凑退出小图标 -->
          <button
            v-else
            class="logout-icon-btn"
            title="退出登录"
            aria-label="退出登录"
            @click="handleLogout"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
          </button>
        </div>
      </template>

      <!-- 未登录状态：展示直角登录/注册按钮 -->
      <template v-else>
        <RouterLink
          v-if="!isCollapsed"
          to="/login"
          class="auth-action-btn mono"
        >
          [登录 / 注册]
        </RouterLink>
        <RouterLink
          v-else
          to="/login"
          class="auth-icon-btn"
          title="登录 / 注册"
          aria-label="登录 / 注册"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
            <polyline points="10 17 15 12 10 7" />
            <line x1="15" y1="12" x2="3" y2="12" />
          </svg>
        </RouterLink>
      </template>
    </div>
  </aside>
</template>

<style scoped>
/* ==========================================================================
   KECE 工业风极简侧边栏全局变量与基础容器
   ========================================================================== */
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.app-sidebar {
  width: 236px;
  min-width: 236px;
  max-width: 236px;
  height: 100vh;
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-right: 1px solid var(--line, #ced4da);
  border-radius: 0;
  user-select: none;
  z-index: 90;
  box-sizing: border-box;
  transition: width 0.2s cubic-bezier(0.16, 1, 0.3, 1),
    min-width 0.2s cubic-bezier(0.16, 1, 0.3, 1),
    max-width 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.app-sidebar.is-collapsed {
  width: 68px;
  min-width: 68px;
  max-width: 68px;
}

/* ==========================================================================
   1. 顶部品牌区 (Brand Header)
   ========================================================================== */
.sidebar-header {
  height: 58px;
  min-height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px 0 16px;
  border-bottom: 1px solid var(--line, #ced4da);
  border-radius: 0;
  box-sizing: border-box;
}

.is-collapsed .sidebar-header {
  padding: 0 8px;
  justify-content: center;
  gap: 6px;
}

.brand-link {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: #111111;
  overflow: hidden;
  border-radius: 0;
}

.brand-logo {
  width: 28px;
  height: 28px;
  object-fit: contain;
  display: block;
  flex-shrink: 0;
}

.brand-title {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: #111111;
  white-space: nowrap;
}

.collapse-toggle-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: #ffffff;
  border: 1px solid var(--line, #ced4da);
  border-radius: 0;
  color: #555555;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
  padding: 0;
}

.collapse-toggle-btn:hover {
  background: #f1f3f5;
  color: #111111;
  border-color: #111111;
}

.toggle-icon {
  width: 14px;
  height: 14px;
}

/* ==========================================================================
   2. 导航项列表 (Nav List)
   ========================================================================== */
.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* 隐藏细微滚动条但保留平滑滚动 */
.sidebar-nav::-webkit-scrollbar {
  width: 4px;
}
.sidebar-nav::-webkit-scrollbar-thumb {
  background: #dee2e6;
}

.nav-item {
  position: relative;
  height: 42px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  gap: 12px;
  color: #555555;
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  border-left: 3px solid transparent;
  border-radius: 0;
  box-sizing: border-box;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.is-collapsed .nav-item {
  padding: 0;
  justify-content: center;
  gap: 0;
}

.nav-item:hover {
  background: #f8f9fa;
  color: #111111;
}

/* 高亮激活规范：左侧 3px 纯黑高亮条，浅灰背景 */
.nav-item.active {
  border-left: 3px solid #111111;
  background: #f1f3f5;
  font-weight: 700;
  color: #111111;
}

.nav-icon-wrap {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.nav-icon {
  width: 18px;
  height: 18px;
}

.nav-label {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1;
}

/* ==========================================================================
   3. 题库手风琴与折叠悬浮气泡 (Accordion & Flyout)
   ========================================================================== */
.nav-accordion-group {
  position: relative;
  display: flex;
  flex-direction: column;
}

.nav-accordion-trigger {
  cursor: pointer;
}

.accordion-toggle-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 0;
  color: #8a8a8a;
  cursor: pointer;
  transition: color 0.15s ease;
}

.accordion-toggle-btn:hover {
  color: #111111;
}

.accordion-arrow {
  width: 10px;
  height: 6px;
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.accordion-arrow.is-rotated {
  transform: rotate(180deg);
}

/* 垂直手风琴展开子菜单 */
.accordion-content {
  display: flex;
  flex-direction: column;
  background: #fafafa;
  border-left: 3px solid transparent;
  padding: 2px 0;
}

.sub-nav-item {
  height: 36px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 42px;
  padding-right: 16px;
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  color: #555555;
  border-left: 3px solid transparent;
  border-radius: 0;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.sub-nav-item:hover {
  background: #f1f3f5;
  color: #111111;
}

.sub-nav-item.active {
  border-left: 3px solid #111111;
  background: #ebeef2;
  font-weight: 700;
  color: #111111;
}

.sub-code {
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  padding: 2px 4px;
  background: #ffffff;
  border: 1px solid var(--line, #ced4da);
  color: #111111;
  border-radius: 0;
  flex-shrink: 0;
}

.sub-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 68px 折叠模式悬浮直角气泡菜单 (Flyout Popover) */
.has-flyout {
  position: relative;
}

.flyout-popover {
  position: absolute;
  left: 100%;
  top: 0;
  min-width: 176px;
  background: #ffffff;
  border: 1px solid #111111;
  border-radius: 0;
  box-shadow: 4px 4px 0 rgba(0, 0, 0, 0.08);
  padding: 6px 0;
  z-index: 1000;
}

/* 弥补鼠标在 68px 侧边栏与气泡间的微隙，防止 hover 闪退 */
.flyout-popover::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -10px;
  width: 10px;
}

.flyout-header {
  padding: 6px 14px 4px 14px;
  font-size: 11px;
  font-weight: 600;
  color: #8a8a8a;
  border-bottom: 1px solid #f1f3f5;
  letter-spacing: 0.5px;
}

.flyout-item {
  height: 38px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  text-decoration: none;
  font-size: 13px;
  color: #333333;
  border-left: 3px solid transparent;
  border-radius: 0;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.flyout-item:hover {
  background: #f8f9fa;
  color: #111111;
}

.flyout-item.active {
  border-left: 3px solid #111111;
  background: #f1f3f5;
  font-weight: 700;
  color: #111111;
}

.flyout-label {
  white-space: nowrap;
}

/* ==========================================================================
   4. 方案 C 特权管理控制台入口 (ADMINISTRATION)
   ========================================================================== */
.admin-section {
  margin-top: 10px;
  padding-top: 8px;
  display: flex;
  flex-direction: column;
}

.admin-divider {
  padding: 6px 16px 8px 16px;
  display: flex;
  align-items: center;
  border-top: 1px solid var(--line, #ced4da);
}

.is-collapsed .admin-divider {
  padding: 8px 12px;
  justify-content: center;
}

.admin-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #8a8a8a;
  white-space: nowrap;
}

.admin-divider-line {
  width: 100%;
  height: 1px;
  background: transparent;
}

.admin-nav-item {
  color: #111111;
}

.admin-arrow-icon {
  width: 12px;
  height: 12px;
  margin-left: auto;
  color: #8a8a8a;
  transition: transform 0.15s ease, color 0.15s ease;
}

.admin-nav-item:hover .admin-arrow-icon {
  color: #111111;
  transform: translateX(2px);
}

/* ==========================================================================
   5. 底部用户卡片 (Footer User Card)
   ========================================================================== */
.sidebar-footer {
  border-top: 1px solid var(--line, #ced4da);
  padding: 12px 14px;
  background: #ffffff;
  border-radius: 0;
  box-sizing: border-box;
}

.sidebar-footer.is-collapsed {
  padding: 12px 8px;
  display: flex;
  justify-content: center;
}

.user-card {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-card.is-collapsed {
  flex-direction: column;
  gap: 8px;
}

.user-avatar-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  position: relative;
  border: 1px solid var(--line, #ced4da);
  border-radius: 0;
  flex-shrink: 0;
  text-decoration: none;
  background: #f8f9fa;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

/* 头像悬浮动效：纯黑细线框 */
.user-avatar-link:hover {
  border-color: #111111;
  box-shadow: 0 0 0 1px #111111;
}

.user-avatar {
  width: 32px;
  height: 32px;
  object-fit: cover;
  display: block;
  border-radius: 0;
}

.user-avatar-placeholder {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #111111;
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  border-radius: 0;
}

.user-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-name-link {
  text-decoration: none;
  color: #111111;
  line-height: 1.2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 用户名悬浮动效：下划线 */
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #111111;
  transition: text-decoration 0.15s ease;
}

.user-name-link:hover .user-name {
  text-decoration: underline;
}

.role-tag {
  align-self: flex-start;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 0;
  letter-spacing: 0.3px;
}

.role-admin {
  background: #111111;
  color: #ffffff;
  border: 1px solid #111111;
}

.role-teacher {
  background: #f1f3f5;
  color: #111111;
  border: 1px solid #111111;
}

.role-student {
  background: #f8f9fa;
  color: #555555;
  border: 1px solid var(--line, #ced4da);
}

.logout-btn {
  padding: 4px 8px;
  font-size: 12px;
  background: #ffffff;
  border: 1px solid var(--line, #ced4da);
  color: #555555;
  border-radius: 0;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.logout-btn:hover {
  background: #111111;
  color: #ffffff;
  border-color: #111111;
}

.logout-icon-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  background: #ffffff;
  border: 1px solid var(--line, #ced4da);
  border-radius: 0;
  color: #555555;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.logout-icon-btn:hover {
  background: #111111;
  color: #ffffff;
  border-color: #111111;
}

.logout-icon-btn svg {
  width: 14px;
  height: 14px;
}

/* 未登录：登录/注册直角按钮 */
.auth-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 8px 10px;
  background: #111111;
  color: #ffffff;
  border: 1px solid #111111;
  border-radius: 0;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  box-sizing: border-box;
  transition: background 0.15s ease, opacity 0.15s ease;
}

.auth-action-btn:hover {
  background: #2a2a2a;
}

.auth-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: #111111;
  color: #ffffff;
  border: 1px solid #111111;
  border-radius: 0;
  text-decoration: none;
  transition: background 0.15s ease;
}

.auth-icon-btn:hover {
  background: #2a2a2a;
}

.auth-icon-btn svg {
  width: 16px;
  height: 16px;
}
</style>
