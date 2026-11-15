<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { adminApi, type AdminUser } from '@/api/admin';
import { ApiError } from '@/api/request';
import DataTable from '@/components/DataTable.vue';
import FormField from '@/components/FormField.vue';
import Pagination from '@/components/Pagination.vue';
import { useConfirm } from '@/composables/useConfirm';
import { useToast } from '@/composables/useToast';
import { useUserStore } from '@/stores/user';

type RoleFilter = '' | 'USER' | 'TEACHER' | 'ADMIN';
type StatusFilter = '' | '1' | '0';

const { confirm } = useConfirm();
const toast = useToast();
const userStore = useUserStore();

const keyword = ref('');
const roleFilter = ref<RoleFilter>('');
const statusFilter = ref<StatusFilter>('');
const page = ref(1);
const size = 20;

const rows = ref<AdminUser[]>([]);
const total = ref(0);
const loading = ref(false);
const loadError = ref('');

const ROLE_LABELS: Record<string, string> = {
  USER: '用户',
  TEACHER: '出题人',
  ADMIN: '管理员',
};

const roleOptions: Array<{ value: RoleFilter; label: string }> = [
  { value: '', label: '全部角色' },
  { value: 'USER', label: '用户' },
  { value: 'TEACHER', label: '出题人' },
  { value: 'ADMIN', label: '管理员' },
];

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: '', label: '全部状态' },
  { value: '1', label: '启用' },
  { value: '0', label: '禁用' },
];

const columns = [
  { key: 'user', label: '用户' },
  { key: 'email', label: '邮箱' },
  { key: 'roles', label: '角色', width: '110px' },
  { key: 'status', label: '状态', width: '90px' },
  { key: 'createdAt', label: '注册时间', width: '166px' },
];

const hasActiveFilters = computed(
  () => keyword.value.trim() !== '' || roleFilter.value !== '' || statusFilter.value !== '',
);

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const res = await adminApi.listUsers({
      keyword: keyword.value.trim() || undefined,
      role: roleFilter.value || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
      page: page.value,
      size,
    });
    rows.value = res.list;
    total.value = res.total;
    if (managedUser.value) {
      const updated = res.list.find((u) => u.id === managedUser.value?.id);
      if (updated) {
        managedUser.value = updated;
        draftRole.value = updated.roles[0] ?? 'USER';
      }
    }
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '请求失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

function resetAndLoad() {
  if (page.value === 1) {
    load();
  } else {
    page.value = 1;
  }
}

function resetFilters() {
  if (keywordTimer !== null) {
    clearTimeout(keywordTimer);
    keywordTimer = null;
  }
  keyword.value = '';
  roleFilter.value = '';
  statusFilter.value = '';
  resetAndLoad();
}

let keywordTimer: ReturnType<typeof setTimeout> | null = null;

watch(keyword, () => {
  if (keywordTimer !== null) clearTimeout(keywordTimer);
  keywordTimer = setTimeout(resetAndLoad, 300);
});

watch([roleFilter, statusFilter], resetAndLoad);

watch(page, () => {
  load();
});

function isSelf(row: AdminUser): boolean {
  return userStore.userInfo?.id === row.id;
}

function roleClass(role: string): string {
  if (role === 'ADMIN') return 'role-admin';
  if (role === 'TEACHER') return 'role-teacher';
  return 'role-user';
}

function userInitial(username: string): string {
  return (username.trim()[0] ?? '?').toUpperCase();
}

function formatTime(iso: string): string {
  return iso ? iso.slice(0, 19).replace('T', ' ') : '—';
}

// ---- 右侧用户管理抽屉 ----
const managedUser = ref<AdminUser | null>(null);
const draftRole = ref<string>('USER');
const roleSaving = ref(false);
const statusSaving = ref(false);
const pwdValue = ref('');
const pwdError = ref('');
const pwdSubmitting = ref(false);
const drawerCloseBtn = ref<HTMLButtonElement | null>(null);
let previousFocus: HTMLElement | null = null;

function openManageDrawer(row: AdminUser) {
  previousFocus = document.activeElement as HTMLElement | null;
  managedUser.value = row;
  draftRole.value = row.roles[0] ?? 'USER';
  pwdValue.value = '';
  pwdError.value = '';
  nextTick(() => {
    drawerCloseBtn.value?.focus();
  });
}

function closeManageDrawer() {
  if (!managedUser.value) return;
  managedUser.value = null;
  pwdValue.value = '';
  pwdError.value = '';
  const target = previousFocus;
  previousFocus = null;
  nextTick(() => {
    target?.focus();
  });
}

const roleChanged = computed(() => {
  if (!managedUser.value) return false;
  return draftRole.value !== (managedUser.value.roles[0] ?? 'USER');
});

async function saveRole() {
  const target = managedUser.value;
  if (!target || isSelf(target) || roleSaving.value || !roleChanged.value) return;
  const next = draftRole.value;
  const ok = await confirm({
    title: '修改角色',
    message: `将 ${target.username} 的角色修改为「${ROLE_LABELS[next] ?? next}」？`,
    confirmText: '保存角色',
  });
  if (!ok) return;

  roleSaving.value = true;
  try {
    const updated = await adminApi.updateUserRoles(target.id, [next]);
    managedUser.value = updated;
    draftRole.value = updated.roles[0] ?? next;
    toast.success(`已更新 ${target.username} 的角色`);
    await load();
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '请求失败，请稍后重试');
  } finally {
    roleSaving.value = false;
  }
}

async function toggleStatus() {
  const target = managedUser.value;
  if (!target || isSelf(target) || statusSaving.value) return;
  const disabling = target.status === 1;
  const ok = await confirm(
    disabling
      ? {
          title: '禁用账号',
          message: `禁用后用户「${target.username}」将无法登录系统，确定继续？`,
          confirmText: '确认禁用',
          danger: true,
        }
      : {
          title: '启用账号',
          message: `确定重新启用用户「${target.username}」的登录权限吗？`,
          confirmText: '确认启用',
        },
  );
  if (!ok) return;

  statusSaving.value = true;
  try {
    const updated = await adminApi.updateUserStatus(target.id, disabling ? 0 : 1);
    managedUser.value = updated;
    toast.success(disabling ? `已禁用 ${target.username}` : `已启用 ${target.username}`);
    await load();
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '请求失败，请稍后重试');
  } finally {
    statusSaving.value = false;
  }
}

async function submitPassword() {
  const target = managedUser.value;
  if (!target || isSelf(target) || pwdSubmitting.value) return;
  const pwd = pwdValue.value;
  if (pwd.length < 6 || pwd.length > 64) {
    pwdError.value = '密码长度需为 6-64 位';
    return;
  }

  pwdError.value = '';
  const ok = await confirm({
    title: '重置登录密码',
    message: `将立即覆盖用户「${target.username}」的登录密码，原密码即刻失效。确认执行？`,
    confirmText: '确认重置',
    danger: true,
  });
  if (!ok) return;

  pwdSubmitting.value = true;
  try {
    await adminApi.resetUserPassword(target.id, pwd);
    pwdValue.value = '';
    toast.success(`已重置 ${target.username} 的密码`);
  } catch (e) {
    pwdError.value = e instanceof ApiError ? e.message : '请求失败，请稍后重试';
  } finally {
    pwdSubmitting.value = false;
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && managedUser.value) {
    closeManageDrawer();
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  load();
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown);
  if (keywordTimer !== null) clearTimeout(keywordTimer);
});
</script>

<template>
  <div class="admin container users-page">
    <header class="page-header">
      <div class="header-copy">
        <h1 class="page-title">用户管理</h1>
        <p>检索平台账号，在管理抽屉内调整角色权限、启停状态或重置登录密码。</p>
      </div>
      <div class="header-actions">
        <button
          class="btn btn-outline btn-sm refresh-btn"
          type="button"
          :disabled="loading"
          @click="load"
        >
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <div class="filter-bar card">
      <input
        v-model="keyword"
        class="input keyword-input"
        type="search"
        aria-label="搜索用户名或邮箱"
        placeholder="搜索用户名 / 邮箱"
      />
      <label class="filter">
        <span class="filter-label">角色</span>
        <select v-model="roleFilter" class="input">
          <option v-for="r in roleOptions" :key="r.value || 'all'" :value="r.value">
            {{ r.label }}
          </option>
        </select>
      </label>
      <label class="filter">
        <span class="filter-label">状态</span>
        <select v-model="statusFilter" class="input">
          <option v-for="s in statusOptions" :key="s.value || 'all'" :value="s.value">
            {{ s.label }}
          </option>
        </select>
      </label>
      <button
        v-if="hasActiveFilters"
        class="btn btn-ghost btn-sm reset-btn"
        type="button"
        @click="resetFilters"
      >
        重置
      </button>
    </div>

    <p v-if="loadError" class="submit-error" role="alert">
      {{ loadError }} <button type="button" @click="load">重试</button>
    </p>

    <div class="results-heading">
      <strong>用户列表</strong>
      <span>共 {{ total }} 条</span>
    </div>

    <DataTable :columns="columns" :rows="rows" :loading="loading" empty-text="暂无符合条件的用户">
      <template #cell-user="{ row }">
        <div class="user-cell">
          <span class="user-avatar" aria-hidden="true">{{ userInitial(row.username) }}</span>
          <div class="user-info">
            <div class="user-title-row">
              <span class="user-name">{{ row.username }}</span>
              <span v-if="isSelf(row)" class="self-tag">当前账号</span>
            </div>
            <span class="user-id mono">ID #{{ row.id }}</span>
          </div>
        </div>
      </template>
      <template #cell-email="{ row }">
        <span v-if="row.email" class="email-cell">{{ row.email }}</span>
        <span v-else class="muted">—</span>
      </template>
      <template #cell-roles="{ row }">
        <div class="role-cell">
          <span v-for="role in row.roles" :key="role" class="badge" :class="roleClass(role)">
            {{ ROLE_LABELS[role] ?? role }}
          </span>
        </div>
      </template>
      <template #cell-status="{ row }">
        <span class="badge" :class="row.status === 1 ? 'status-enabled' : 'status-disabled'">
          {{ row.status === 1 ? '启用' : '禁用' }}
        </span>
      </template>
      <template #cell-createdAt="{ row }">
        <span class="time mono">{{ formatTime(row.createdAt) }}</span>
      </template>
      <template #actions="{ row }">
        <button
          class="btn btn-outline btn-sm manage-btn"
          type="button"
          @click="openManageDrawer(row)"
        >
          管理
        </button>
      </template>
    </DataTable>

    <Pagination v-model:page="page" :size="size" :total="total" />

    <!-- 右侧账号管理抽屉 -->
    <Teleport to="body">
      <div
        v-if="managedUser"
        class="drawer-backdrop"
        @click.self="closeManageDrawer"
      >
        <aside
          class="manage-drawer"
          role="dialog"
          aria-modal="true"
          aria-labelledby="user-drawer-title"
        >
          <div class="drawer-header">
            <div class="drawer-user-head">
              <span class="user-avatar lg" aria-hidden="true">{{ userInitial(managedUser.username) }}</span>
              <div class="drawer-title-block">
                <div class="drawer-title-row">
                  <h2 id="user-drawer-title" class="drawer-title">{{ managedUser.username }}</h2>
                  <span v-if="isSelf(managedUser)" class="self-tag">当前账号</span>
                </div>
                <span class="mono drawer-sub">ID #{{ managedUser.id }}</span>
              </div>
            </div>
            <button
              ref="drawerCloseBtn"
              class="drawer-close-btn"
              type="button"
              aria-label="关闭管理抽屉"
              @click="closeManageDrawer"
            >
              ✕
            </button>
          </div>

          <div class="drawer-body">
            <p v-if="isSelf(managedUser)" class="self-notice" role="status">
              当前登录账号受安全保护，无法在此修改自身角色、禁用账号或重置密码。
            </p>

            <!-- 账号资料 -->
            <section class="drawer-section">
              <h3 class="section-heading">基本信息</h3>
              <dl class="info-list">
                <div class="info-row">
                  <dt>邮箱</dt>
                  <dd>{{ managedUser.email || '未绑定邮箱' }}</dd>
                </div>
                <div class="info-row">
                  <dt>当前角色</dt>
                  <dd class="badge-group">
                    <span
                      v-for="role in managedUser.roles"
                      :key="role"
                      class="badge"
                      :class="roleClass(role)"
                    >
                      {{ ROLE_LABELS[role] ?? role }}
                    </span>
                  </dd>
                </div>
                <div class="info-row">
                  <dt>账号状态</dt>
                  <dd>
                    <span
                      class="badge"
                      :class="managedUser.status === 1 ? 'status-enabled' : 'status-disabled'"
                    >
                      {{ managedUser.status === 1 ? '启用' : '禁用' }}
                    </span>
                  </dd>
                </div>
                <div class="info-row">
                  <dt>注册时间</dt>
                  <dd class="mono">{{ formatTime(managedUser.createdAt) }}</dd>
                </div>
              </dl>
            </section>

            <!-- 角色调整 -->
            <section class="drawer-section">
              <h3 class="section-heading">角色权限</h3>
              <p class="section-desc">变更角色将立即调整该账号在管理后台与题库模块的访问权限。</p>
              <div class="role-edit-row">
                <select
                  v-model="draftRole"
                  class="input drawer-select"
                  :disabled="isSelf(managedUser) || roleSaving"
                  :aria-label="`设置 ${managedUser.username} 的角色`"
                >
                  <option value="USER">用户</option>
                  <option value="TEACHER">出题人</option>
                  <option value="ADMIN">管理员</option>
                </select>
                <button
                  class="btn btn-primary btn-sm accent-btn save-role-btn"
                  type="button"
                  :disabled="isSelf(managedUser) || roleSaving || !roleChanged"
                  @click="saveRole"
                >
                  {{ roleSaving ? '保存中…' : '保存角色' }}
                </button>
              </div>
            </section>

            <!-- 状态启停 -->
            <section class="drawer-section">
              <h3 class="section-heading">账号状态</h3>
              <p class="section-desc">
                {{
                  managedUser.status === 1
                    ? '当前账号处于启用状态，可正常登录并参与做题与考试；禁用后将立即拦截登录。'
                    : '当前账号已被禁用，无法登录平台；恢复启用后可重新访问。'
                }}
              </p>
              <div>
                <button
                  class="btn btn-sm status-toggle-btn"
                  :class="managedUser.status === 1 ? 'btn-danger-outline' : 'btn-outline'"
                  type="button"
                  :disabled="isSelf(managedUser) || statusSaving"
                  @click="toggleStatus"
                >
                  {{
                    statusSaving
                      ? '处理中…'
                      : managedUser.status === 1
                        ? '禁用账号'
                        : '启用账号'
                  }}
                </button>
              </div>
            </section>

            <!-- 重置密码 -->
            <section class="drawer-section">
              <h3 class="section-heading">重置密码</h3>
              <p class="section-desc">直接覆盖设置该账号的新登录密码，保存后原密码立即失效。</p>
              <form class="pwd-form" @submit.prevent="submitPassword">
                <FormField label="新密码" required hint="6-64 位字符" :error="pwdError">
                  <input
                    v-model="pwdValue"
                    class="input"
                    type="password"
                    placeholder="输入新密码"
                    autocomplete="new-password"
                    :disabled="isSelf(managedUser) || pwdSubmitting"
                  />
                </FormField>
                <div class="pwd-submit-row">
                  <button
                    class="btn btn-outline btn-sm reset-pwd-btn"
                    type="submit"
                    :disabled="isSelf(managedUser) || pwdSubmitting || !pwdValue"
                  >
                    {{ pwdSubmitting ? '提交中…' : '重置密码' }}
                  </button>
                </div>
              </form>
            </section>
          </div>
        </aside>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.admin {
  padding-top: 8px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin: 0 0 18px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink);
}

.page-header p {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13.5px;
}

.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 16px;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: var(--kece-card-radius, 0px);
}

.keyword-input {
  max-width: 300px;
  min-width: 180px;
  height: 38px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  color: var(--ink-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.filter select {
  width: 120px;
  height: 38px;
}

.reset-btn {
  margin-left: auto;
}

.results-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-bottom: 0;
  font-size: 14px;
}

.results-heading span {
  color: var(--ink-secondary);
  font-size: 12.5px;
}

.submit-error {
  margin: 0 0 14px;
  padding: 10px 12px;
  color: var(--kece-danger, #c4314b);
  border: 1px solid var(--kece-danger, #c4314b);
  background: var(--kece-danger-soft, #fdf2f4);
  font-size: 13px;
}

.submit-error button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.admin :is(input, select, button):focus-visible,
.drawer-backdrop :is(input, select, button):focus-visible {
  outline: 2px solid var(--kece-accent, #137969);
  outline-offset: 2px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-avatar {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--kece-border, var(--line));
  color: var(--ink);
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
  border-radius: 0;
}

.user-avatar.lg {
  width: 38px;
  height: 38px;
  font-size: 15px;
}

.user-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.user-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.user-name {
  font-weight: 600;
  color: var(--ink);
  font-size: 14px;
}

.user-id {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.self-tag {
  display: inline-block;
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 600;
  background: var(--kece-accent-soft, #eef6f4);
  color: var(--kece-accent-ink, #0f5f53);
  border: 1px solid var(--kece-accent, #137969);
}

.email-cell {
  font-size: 13.5px;
  color: var(--ink-secondary);
}

.role-cell,
.badge-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.role-admin {
  background: #111827;
  color: #ffffff;
  border-color: #111827;
}

.role-teacher {
  background: var(--kece-accent-soft, #eef6f4);
  color: var(--kece-accent-ink, #0f5f53);
  border-color: var(--kece-accent, #137969);
}

.role-user {
  background: #ffffff;
  color: var(--ink-secondary);
}

.status-enabled {
  background: var(--kece-success-soft, #eefbf3);
  color: var(--kece-success, #16794f);
  border-color: #a7f3d0;
}

.status-disabled {
  background: var(--kece-danger-soft, #fdf2f4);
  color: var(--kece-danger, #c4314b);
  border-color: #fecdd3;
}

.time {
  color: var(--ink-tertiary);
  font-size: 12.5px;
}

.manage-btn:hover {
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
}

/* 右侧抽屉 */
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.35);
  display: flex;
  justify-content: flex-end;
  z-index: 9999;
}

.manage-drawer {
  width: min(460px, 100vw);
  height: 100dvh;
  background: #ffffff;
  border-left: 1px solid var(--kece-border, var(--line));
  border-radius: 0;
  display: flex;
  flex-direction: column;
  box-shadow: -8px 0 32px rgba(15, 23, 42, 0.12);
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--kece-border, var(--line));
  background: var(--kece-surface-muted, #f8fafc);
}

.drawer-user-head {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.drawer-title-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.drawer-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.drawer-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
}

.drawer-sub {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.drawer-close-btn {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--kece-border, var(--line));
  background: #ffffff;
  color: var(--ink-secondary);
  cursor: pointer;
}

.drawer-close-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px 28px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.self-notice {
  margin: 0;
  padding: 10px 12px;
  font-size: 12.5px;
  color: var(--kece-accent-ink, #0f5f53);
  background: var(--kece-accent-soft, #eef6f4);
  border: 1px solid var(--kece-accent, #137969);
}

.drawer-section {
  padding-bottom: 18px;
  border-bottom: 1px solid var(--kece-border, var(--line));
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.drawer-section:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.section-heading {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

.section-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-secondary);
  line-height: 1.5;
}

.info-list {
  margin: 0;
  display: grid;
  gap: 8px;
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}

.info-row dt {
  color: var(--ink-tertiary);
}

.info-row dd {
  margin: 0;
  color: var(--ink);
  text-align: right;
}

.role-edit-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.drawer-select {
  flex: 1;
  height: 36px;
}

.accent-btn {
  background: var(--kece-accent, #137969);
  border-color: var(--kece-accent, #137969);
  color: #ffffff;
}

.accent-btn:hover:not(:disabled) {
  background: var(--kece-accent-hover, #0e6053);
  border-color: var(--kece-accent-hover, #0e6053);
}

.btn-danger-outline {
  border: 1px solid var(--kece-danger, #c4314b);
  color: var(--kece-danger, #c4314b);
  background: #ffffff;
}

.btn-danger-outline:hover:not(:disabled) {
  background: var(--kece-danger-soft, #fdf2f4);
}

.pwd-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pwd-submit-row {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 720px) {
  .reset-btn {
    margin-left: 0;
  }
}
</style>
