<script setup lang="ts">
import { computed, ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useUserStore } from '@/stores/user';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

interface NavSubItem {
  path: string;
  label: string;
  code: string;
}

interface NavItem {
  path: string;
  label: string;
  matchPaths?: string[];
  children?: NavSubItem[];
}

const navLinks: NavItem[] = [
  { path: '/', label: '首页' },
  {
    path: '/questions',
    label: '题库',
    matchPaths: ['/questions', '/database', '/objective', '/submissions', '/database-submissions'],
    children: [
      { path: '/questions', label: '算法编程', code: 'ALGO' },
      { path: '/database', label: '数据库题', code: 'SQL' },
      { path: '/objective', label: '客观题库', code: 'QUIZ' },
    ],
  },
  { path: '/problem-sets', label: '题集' },
  { path: '/practice', label: '课练' },
  { path: '/exams', label: '考试' },
  { path: '/rankings', label: '排名' },
  { path: '/announcements', label: '公告栏' },
];

const navRef = ref<HTMLElement | null>(null);
const sliderVisible = ref(false);
const sliderAnimating = ref(false);
const sliderStyle = ref({
  left: '0px',
  width: '0px',
});

const openDropdown = ref<string | null>(null);
let closeTimer: ReturnType<typeof setTimeout> | null = null;

function onMouseEnterItem(e: MouseEvent, item: NavItem) {
  if (closeTimer) {
    clearTimeout(closeTimer);
    closeTimer = null;
  }
  const el = e.currentTarget as HTMLElement;
  const target = (el.classList.contains('nav-item-wrap') ? el : el.closest('.nav-item-wrap')) || el;
  if (!target || !navRef.value) return;
  const navRect = navRef.value.getBoundingClientRect();
  const targetRect = target.getBoundingClientRect();

  sliderStyle.value = {
    left: `${targetRect.left - navRect.left}px`,
    width: `${targetRect.width}px`,
  };

  if (!sliderVisible.value) {
    sliderAnimating.value = false;
    sliderVisible.value = true;
    requestAnimationFrame(() => {
      sliderAnimating.value = true;
    });
  } else {
    sliderAnimating.value = true;
  }

  if (item.children) {
    openDropdown.value = item.label;
  } else {
    openDropdown.value = null;
  }
}

function onMouseLeaveItem(item: NavItem) {
  if (item.children) {
    closeTimer = setTimeout(() => {
      if (openDropdown.value === item.label) {
        openDropdown.value = null;
      }
    }, 120);
  }
}

function onNavMouseLeave() {
  if (closeTimer) {
    clearTimeout(closeTimer);
    closeTimer = null;
  }
  openDropdown.value = null;
  sliderVisible.value = false;
  sliderAnimating.value = false;
}

function closeDropdown() {
  if (closeTimer) {
    clearTimeout(closeTimer);
    closeTimer = null;
  }
  openDropdown.value = null;
}

const roleInfo = computed(() => {
  if (userStore.hasRole('ADMIN')) return { label: '管理员', class: 'role-admin' };
  if (userStore.hasRole('TEACHER')) return { label: '教师', class: 'role-teacher' };
  return { label: '学生', class: 'role-student' };
});

function isItemActive(item: NavItem): boolean {
  if (item.matchPaths) {
    return item.matchPaths.some((p) => route.path === p || route.path.startsWith(p + '/'));
  }
  if (item.path === '/') return route.path === '/';
  return route.path === item.path || route.path.startsWith(item.path + '/');
}

function isSubActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/');
}

function isActive(path: string): boolean {
  if (path === '/') return route.path === '/';
  return route.path === path || route.path.startsWith(path + '/');
}

function logout() {
  userStore.clearAuth();
  router.push('/login');
}
</script>

<template>
  <header class="app-header">
    <div class="header-inner container">
      <RouterLink to="/" class="brand" title="KECE 课测">
        <img class="brand-logo" src="/kece-logo.png" alt="KECE 课测" />
      </RouterLink>
      <nav ref="navRef" class="nav" @mouseleave="onNavMouseLeave">
        <div
          class="nav-slider"
          :class="{ 'is-visible': sliderVisible, 'is-animating': sliderAnimating }"
          :style="sliderStyle"
        ></div>
        <div
          v-for="item in navLinks"
          :key="item.path"
          class="nav-item-wrap"
          @mouseenter="onMouseEnterItem($event, item)"
          @mouseleave="onMouseLeaveItem(item)"
        >
          <RouterLink
            :to="item.path"
            class="nav-item"
            :class="{ active: isItemActive(item), 'has-children': !!item.children }"
            @click="closeDropdown"
          >
            <span>{{ item.label }}</span>
            <svg
              v-if="item.children"
              class="dropdown-chevron"
              :class="{ 'is-open': openDropdown === item.label }"
              viewBox="0 0 10 6"
              fill="none"
              stroke="currentColor"
            >
              <path d="M1 1l4 4 4-4" stroke-width="1.6" stroke-linecap="square" />
            </svg>
          </RouterLink>

          <!-- 工业风下拉子菜单 (直角、等宽代码标签、紧凑专业) -->
          <transition name="dropdown-fade">
            <div
              v-if="item.children && openDropdown === item.label"
              class="nav-dropdown"
            >
              <RouterLink
                v-for="sub in item.children"
                :key="sub.path"
                :to="sub.path"
                class="dropdown-item"
                :class="{ active: isSubActive(sub.path) }"
                @click="closeDropdown"
              >
                <span class="sub-code mono">{{ sub.code }}</span>
                <span class="sub-label">{{ sub.label }}</span>
                <svg class="sub-arrow" viewBox="0 0 12 12" fill="none" stroke="currentColor">
                  <path d="M4 2l4 4-4 4" stroke-width="1.6" stroke-linecap="square" />
                </svg>
              </RouterLink>
            </div>
          </transition>
        </div>
      </nav>
      <div class="header-right">
        <template v-if="userStore.isLoggedIn">
          <RouterLink
            to="/profile"
            class="user-profile-link"
            :class="{ active: isActive('/profile') }"
            title="个人主页"
          >
            <div class="user-avatar-wrap">
              <img
                v-if="userStore.userInfo?.avatarUrl"
                :src="userStore.userInfo.avatarUrl"
                class="user-avatar"
                alt="Avatar"
              />
              <span v-else class="user-avatar-placeholder mono">
                {{ userStore.userInfo?.username?.charAt(0)?.toUpperCase() || 'U' }}
              </span>
            </div>
            <span class="username">{{ userStore.userInfo?.username }}</span>
            <span class="role-tag" :class="roleInfo.class">
              {{ roleInfo.label }}
            </span>
          </RouterLink>
          <button class="btn btn-ghost btn-sm" @click="logout">退出</button>
          <RouterLink
            v-if="userStore.hasRole('ADMIN') || userStore.hasRole('TEACHER')"
            to="/admin"
            class="btn btn-primary btn-sm admin-entry-btn"
          >
            <span>后台管理</span>
            <svg class="nav-arrow-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M2 6h8M6 2l4 4-4 4" />
            </svg>
          </RouterLink>
        </template>
        <RouterLink v-else to="/login" class="btn btn-primary btn-sm">登录</RouterLink>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-bottom: 1px solid var(--line);
}
.header-inner {
  position: relative;
  height: 62px;
  display: flex;
  align-items: center;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  font-weight: 700;
  font-size: 18px;
  color: var(--ink);
  transition: opacity 0.15s ease;
  user-select: none;
}
.brand:hover {
  opacity: 0.85;
}
.brand-logo {
  height: 28px;
  width: auto;
  display: block;
}
.nav {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  height: 62px;
  flex-shrink: 0;
  margin-left: -14px;
}
.nav-slider {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  height: 34px;
  background: #ececec;
  border-radius: 0;
  pointer-events: none;
  z-index: 0;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.nav-slider.is-animating {
  transition:
    left 0.2s cubic-bezier(0.16, 1, 0.3, 1),
    width 0.2s cubic-bezier(0.16, 1, 0.3, 1),
    opacity 0.15s ease;
}
.nav-slider.is-visible {
  opacity: 1;
}
.nav-item {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 14px;
  height: 34px;
  color: var(--ink-secondary);
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  transition: color 0.15s ease;
  user-select: none;
  white-space: nowrap !important;
  flex-shrink: 0;
  letter-spacing: 0.3px;
}
.nav-item:hover {
  color: var(--ink);
}
.nav-item.active {
  color: var(--ink);
  font-weight: 700;
}
.nav-item-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
  height: 100%;
}
.dropdown-chevron {
  width: 8px;
  height: 5px;
  margin-left: 1px;
  opacity: 0.55;
  transition: transform 0.18s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.18s ease;
}
.nav-item:hover .dropdown-chevron,
.dropdown-chevron.is-open {
  opacity: 1;
}
.dropdown-chevron.is-open {
  transform: rotate(180deg);
}

/* 工业风紧凑直角下拉菜单 */
.nav-dropdown {
  position: absolute;
  top: 54px;
  left: 0;
  min-width: 176px;
  background: #ffffff;
  border: 1px solid var(--line);
  border-top: 2px solid var(--ink);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.08);
  padding: 4px 0;
  z-index: 120;
}
.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  color: var(--ink-secondary);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  transition: background 0.12s ease, color 0.12s ease;
}
.dropdown-item:hover {
  background: #f8f9fa;
  color: var(--ink);
}
.dropdown-item.active {
  background: #f1f3f5;
  color: var(--ink);
  font-weight: 700;
  border-left: 2px solid var(--ink);
  padding-left: 12px;
}
.sub-code {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  background: #e2e8f0;
  color: #334155;
  line-height: 1.2;
  letter-spacing: 0.4px;
}
.dropdown-item.active .sub-code {
  background: var(--ink);
  color: #ffffff;
}
.sub-label {
  flex: 1;
  white-space: nowrap;
}
.sub-arrow {
  width: 9px;
  height: 9px;
  opacity: 0;
  transition: opacity 0.12s ease, transform 0.12s ease;
}
.dropdown-item:hover .sub-arrow {
  opacity: 0.65;
  transform: translateX(2px);
}

.dropdown-fade-enter-active,
.dropdown-fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s cubic-bezier(0.16, 1, 0.3, 1);
}
.dropdown-fade-enter-from,
.dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
.header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  min-width: 0;
}
.user-profile-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px 0 0;
  border: 1px solid var(--line);
  background: var(--surface);
  transition: all 0.15s ease;
  color: var(--ink);
  height: 34px;
  box-sizing: border-box;
  overflow: hidden;
  text-decoration: none;
}
.user-profile-link:hover,
.user-profile-link.active {
  border-color: var(--ink);
  background: var(--bg);
}
.user-profile-link:hover .user-avatar-wrap {
  border-right-color: var(--ink);
}
.user-avatar-wrap {
  width: 32px;
  min-width: 32px;
  height: 100%;
  align-self: stretch;
  flex-shrink: 0;
  border: none;
  border-right: 1px solid var(--line);
  background: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  transition: border-color 0.15s ease;
}
.user-avatar {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.user-avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-secondary);
  background: #f0f0f0;
}
.username {
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-tag {
  align-self: center;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  border-radius: 0;
  padding: 1px 6px;
  line-height: 1.4;
  white-space: nowrap;
  letter-spacing: 0.2px;
}
.role-tag.role-admin {
  background: #7048e8;
  color: #fff;
  border: 1px solid #5f3dc4;
}
.role-tag.role-teacher {
  background: #2b8a3e;
  color: #fff;
  border: 1px solid #237032;
}
.role-tag.role-student {
  background: #1e1e1e;
  color: #fff;
  border: 1px solid #111;
}
.admin-entry-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.external-icon,
.nav-arrow-icon {
  width: 10px;
  height: 10px;
  stroke-width: 2;
}
@media (min-width: 1400px) {
  .brand {
    position: absolute;
    right: calc(100% + 12px);
    top: 50%;
    transform: translateY(-50%);
  }
}
@media (max-width: 1399px) {
  .brand {
    position: static;
    transform: none;
    margin-right: 14px;
  }
  .brand-logo {
    height: 26px;
  }
  .nav {
    margin-left: 0;
  }
}
@media (max-width: 1160px) {
  .nav-item {
    padding: 0 10px;
    font-size: 14px;
  }
  .brand-logo {
    height: 24px;
  }
}
@media (max-width: 960px) {
  .header-inner {
    padding: 0 16px;
  }
  .nav-item {
    padding: 0 6px;
    font-size: 13px;
  }
  .username {
    max-width: 80px;
  }
}
@media (max-width: 840px) {
  .nav {
    display: none;
  }
}
</style>
