<script setup lang="ts">
import { computed, ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useUserStore } from '@/stores/user';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const navLinks = [
  { path: '/', label: '首页' },
  { path: '/questions', label: '编程' },
  { path: '/database', label: '数据库' },
  { path: '/frontend', label: '前端' },
  { path: '/objective', label: '客观题' },
  { path: '/problem-sets', label: '题集' },
  { path: '/rankings', label: '排名' },
  { path: '/practice', label: '课练' },
  { path: '/exams', label: '考试' },
  { path: '/announcements', label: '公告栏' },
];

const navRef = ref<HTMLElement | null>(null);
const sliderVisible = ref(false);
const sliderAnimating = ref(false);
const sliderStyle = ref({
  left: '0px',
  width: '0px',
});

function onMouseEnterItem(e: MouseEvent) {
  const target = e.currentTarget as HTMLElement;
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
}

function onNavMouseLeave() {
  sliderVisible.value = false;
  sliderAnimating.value = false;
}

const roleInfo = computed(() => {
  if (userStore.hasRole('ADMIN')) return { label: '管理员', class: 'role-admin' };
  if (userStore.hasRole('TEACHER')) return { label: '教师', class: 'role-teacher' };
  return { label: '学生', class: 'role-student' };
});

// 按路径段匹配，避免 /database 与其详情页 /database/:id 同时高亮。
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
        <RouterLink
          v-for="item in navLinks"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
          @mouseenter="onMouseEnterItem"
        >
          {{ item.label }}
        </RouterLink>
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
          <div
            v-if="userStore.hasRole('ADMIN') || userStore.hasRole('TEACHER')"
            class="admin-dropdown-wrap"
          >
            <RouterLink
              to="/admin"
              class="btn btn-primary btn-sm admin-entry-btn"
            >
              <span>后台管理</span>
              <svg class="nav-arrow-icon" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M2 6h8M6 2l4 4-4 4" />
              </svg>
            </RouterLink>
            <div class="admin-dropdown-menu">
              <RouterLink to="/admin/questions" class="admin-menu-item">编程题管理</RouterLink>
              <RouterLink to="/admin/db-questions" class="admin-menu-item">数据库题管理</RouterLink>
              <RouterLink to="/admin/objective-questions" class="admin-menu-item">客观题管理</RouterLink>
              <RouterLink to="/admin/problem-sets" class="admin-menu-item">题集管理</RouterLink>
              <RouterLink to="/admin/exams" class="admin-menu-item">考试与组卷</RouterLink>
              <RouterLink to="/admin/practices" class="admin-menu-item">随堂课练</RouterLink>
              <RouterLink to="/admin/submissions" class="admin-menu-item">提交监控</RouterLink>
              <RouterLink to="/admin/announcements" class="admin-menu-item">公告管理</RouterLink>
              <RouterLink v-if="userStore.hasRole('ADMIN')" to="/admin/users" class="admin-menu-item">用户管理</RouterLink>
            </div>
          </div>
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
.admin-dropdown-wrap {
  position: relative;
  display: inline-flex;
}
.admin-dropdown-menu {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  min-width: 140px;
  background: #ffffff;
  border: 1px solid var(--line-strong);
  border-radius: 0;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12);
  display: none;
  flex-direction: column;
  z-index: 120;
  padding: 4px 0;
}
.admin-dropdown-wrap:hover .admin-dropdown-menu,
.admin-dropdown-wrap:focus-within .admin-dropdown-menu {
  display: flex;
}
.admin-menu-item {
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-secondary);
  text-decoration: none;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.admin-menu-item:hover {
  background: var(--surface);
  color: #1677ff;
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
