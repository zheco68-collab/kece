<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { adminApi, type AdminUser } from '@/api/admin'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import FormField from '@/components/FormField.vue'
import Pagination from '@/components/Pagination.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'

type RoleFilter = '' | 'USER' | 'TEACHER' | 'ADMIN'
type StatusFilter = '' | '1' | '0'

const { confirm } = useConfirm()
const toast = useToast()
const userStore = useUserStore()

const keyword = ref('')
const roleFilter = ref<RoleFilter>('')
const statusFilter = ref<StatusFilter>('')
const page = ref(1)
const size = 20

const rows = ref<AdminUser[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')

/** 角色文案：改角色确认语与下拉框共用。 */
const ROLE_LABELS: Record<string, string> = {
  USER: '用户',
  TEACHER: '出题人',
  ADMIN: '管理员',
}

const roleOptions: Array<{ value: RoleFilter; label: string }> = [
  { value: '', label: '全部角色' },
  { value: 'USER', label: '用户' },
  { value: 'TEACHER', label: '出题人' },
  { value: 'ADMIN', label: '管理员' },
]

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: '', label: '全部状态' },
  { value: '1', label: '启用' },
  { value: '0', label: '禁用' },
]

const columns = [
  { key: 'id', label: 'ID', width: '64px' },
  { key: 'username', label: '用户名' },
  { key: 'email', label: '邮箱' },
  { key: 'roles', label: '角色' },
  { key: 'status', label: '状态' },
  { key: 'createdAt', label: '注册时间', width: '170px' },
]

// ---- 重置密码弹窗状态 ----
const pwdTarget = ref<AdminUser | null>(null)
const pwdValue = ref('')
const pwdError = ref('')
const pwdSubmitting = ref(false)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await adminApi.listUsers({
      keyword: keyword.value.trim() || undefined,
      role: roleFilter.value || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
      page: page.value,
      size,
    })
    rows.value = res.list
    total.value = res.total
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '请求失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 筛选变化时回到第一页再加载；已在第一页则手动触发。 */
function resetAndLoad() {
  if (page.value === 1) {
    load()
  } else {
    page.value = 1
  }
}

// 关键词输入防抖 300ms，避免每次击键都发请求。
let keywordTimer: ReturnType<typeof setTimeout> | null = null

watch(keyword, () => {
  if (keywordTimer !== null) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(resetAndLoad, 300)
})

watch([roleFilter, statusFilter], resetAndLoad)

watch(page, () => {
  load()
})

/** 自己那行不可操作：前端禁用，后端还会强制拦截（双保险）。 */
function isSelf(row: AdminUser): boolean {
  return userStore.userInfo?.id === row.id
}

/** 角色徽章配色沿用难度徽章的灰度强度：ADMIN 深底白字，其余浅底。 */
function roleClass(role: string): string {
  if (role === 'ADMIN') return 'role-admin'
  if (role === 'TEACHER') return 'role-teacher'
  return 'role-user'
}

/** 行内下拉改角色：三档互斥单选，确认后由后端整体覆盖。 */
async function onRoleChange(row: AdminUser, event: Event) {
  const select = event.target as HTMLSelectElement
  const next = select.value
  const prev = row.roles[0] ?? 'USER'
  const ok = await confirm({
    title: '修改角色',
    message: `将 ${row.username} 的角色改为 ${ROLE_LABELS[next] ?? next}`,
  })
  if (!ok) {
    // 取消后还原下拉框展示：数据未变不会触发重渲染，需手动改回。
    select.value = prev
    return
  }
  try {
    await adminApi.updateUserRoles(row.id, [next])
    toast.success(`已更新 ${row.username} 的角色`)
    load()
  } catch (e) {
    select.value = prev
    toast.error(e instanceof ApiError ? e.message : '请求失败，请稍后重试')
  }
}

async function toggleStatus(row: AdminUser) {
  const disabling = row.status === 1
  const ok = await confirm(
    disabling
      ? {
          title: '禁用用户',
          message: '禁用后该用户将无法登录，确定？',
          danger: true,
        }
      : {
          title: '启用用户',
          message: `确定启用 ${row.username} 吗？`,
        },
  )
  if (!ok) return
  try {
    await adminApi.updateUserStatus(row.id, disabling ? 0 : 1)
    toast.success(disabling ? `已禁用 ${row.username}` : `已启用 ${row.username}`)
    load()
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '请求失败，请稍后重试')
  }
}

function openResetPwd(row: AdminUser) {
  pwdTarget.value = row
  pwdValue.value = ''
  pwdError.value = ''
}

function closePwd() {
  pwdTarget.value = null
}

async function submitPassword() {
  const pwd = pwdValue.value
  if (pwd.length < 6 || pwd.length > 64) {
    pwdError.value = '密码长度需为 6-64 位'
    return
  }
  const target = pwdTarget.value
  if (!target) return
  pwdSubmitting.value = true
  pwdError.value = ''
  try {
    await adminApi.resetUserPassword(target.id, pwd)
    toast.success('密码已重置')
    closePwd()
  } catch (e) {
    pwdError.value = e instanceof ApiError ? e.message : '请求失败，请稍后重试'
  } finally {
    pwdSubmitting.value = false
  }
}

/** 注册时间：ISO 串截取前 19 位并把 T 换成空格，得到 "YYYY-MM-DD HH:mm:ss"。 */
function formatTime(iso: string): string {
  return iso ? iso.slice(0, 19).replace('T', ' ') : '—'
}

// Esc 关闭重置密码弹窗。
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && pwdTarget.value) closePwd()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  load()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (keywordTimer !== null) clearTimeout(keywordTimer)
})
</script>

<template>
  <div class="admin container">
    <h1 class="page-title">用户管理</h1>

    <div class="filter-bar card">
      <input v-model="keyword" class="input keyword-input" type="search" placeholder="用户名 / 邮箱" />
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
    </div>

    <p v-if="loadError" class="submit-error">{{ loadError }}</p>

    <DataTable :columns="columns" :rows="rows" :loading="loading">
      <template #cell-email="{ row }">
        <span v-if="row.email">{{ row.email }}</span>
        <span v-else class="muted">—</span>
      </template>
      <template #cell-roles="{ row }">
        <div class="role-cell">
          <span v-for="role in row.roles" :key="role" class="badge" :class="roleClass(role)">
            {{ ROLE_LABELS[role] ?? role }}
          </span>
          <select
            class="input role-select"
            :value="row.roles[0] ?? 'USER'"
            :disabled="isSelf(row)"
            @change="onRoleChange(row, $event)"
          >
            <option value="USER">用户</option>
            <option value="TEACHER">出题人</option>
            <option value="ADMIN">管理员</option>
          </select>
        </div>
      </template>
      <template #cell-status="{ row }">
        <span class="badge" :class="row.status === 1 ? 'status-enabled' : 'status-disabled'">
          {{ row.status === 1 ? '启用' : '禁用' }}
        </span>
      </template>
      <template #cell-createdAt="{ row }">
        <span class="time">{{ formatTime(row.createdAt) }}</span>
      </template>
      <template #actions="{ row }">
        <div class="row-actions">
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            :disabled="isSelf(row)"
            :title="isSelf(row) ? '不能对自己执行该操作' : undefined"
            @click="toggleStatus(row)"
          >
            {{ row.status === 1 ? '禁用' : '启用' }}
          </button>
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            :disabled="isSelf(row)"
            :title="isSelf(row) ? '不能对自己执行该操作' : undefined"
            @click="openResetPwd(row)"
          >
            重置密码
          </button>
        </div>
      </template>
    </DataTable>

    <Pagination v-model:page="page" :size="size" :total="total" />

    <!-- 重置密码弹窗：复用 ConfirmModal 的遮罩风格，页内自持表单与错误文案。 -->
    <Teleport to="body">
      <div v-if="pwdTarget" class="pwd-overlay" @click.self="closePwd">
        <div class="pwd-modal" role="dialog" aria-modal="true">
          <h3 class="pwd-title">重置密码</h3>
          <p class="pwd-sub">将重置用户 {{ pwdTarget.username }} 的登录密码</p>
          <div class="pwd-form">
            <FormField label="新密码" required hint="6-64 位" :error="pwdError">
              <input
                v-model="pwdValue"
                class="input"
                type="password"
                placeholder="输入新密码"
                autocomplete="new-password"
              />
            </FormField>
          </div>
          <div class="pwd-actions">
            <button class="btn btn-ghost btn-sm" type="button" @click="closePwd">取消</button>
            <button
              class="btn btn-primary btn-sm"
              type="button"
              :disabled="pwdSubmitting"
              @click="submitPassword"
            >
              确定
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.admin {
  padding-top: 32px;
}
.page-title {
  margin: 28px 0 16px;
  font-size: 22px;
  font-weight: 600;
}
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
  padding: 16px;
  margin-bottom: 24px;
}
.keyword-input {
  max-width: 280px;
}
.filter {
  display: flex;
  align-items: center;
  gap: 10px;
}
.filter-label {
  color: var(--ink-secondary);
  font-size: 14px;
  white-space: nowrap;
}
.filter select {
  width: 120px;
}
.submit-error {
  margin: 0 0 16px;
}
.role-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.role-select {
  width: 110px;
  height: 34px;
  padding: 0 8px;
  font-size: 13px;
  flex: none;
}
/* 角色徽章沿用难度徽章的灰度强度：管理员深底白字，其余浅底。 */
.role-user {
  background: #f0f0f0;
  color: var(--ink-secondary);
}
.role-teacher {
  background: #2b8a3e;
  color: #fff;
  border: 1px solid #237032;
}
.role-admin {
  background: #7048e8;
  color: #fff;
  border: 1px solid #5f3dc4;
}
.status-enabled {
  background: var(--success-bg);
  color: var(--success);
}
.status-disabled {
  background: var(--danger-bg);
  color: var(--danger);
}
.time {
  color: var(--ink-tertiary);
  font-size: 13px;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pwd-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 17, 17, 0.45);
}
.pwd-modal {
  width: 100%;
  min-width: 320px;
  max-width: 440px;
  padding: 20px 22px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  box-shadow: var(--shadow-md);
}
.pwd-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
}
.pwd-sub {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-secondary);
}
.pwd-form {
  margin-top: 18px;
}
.pwd-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}
/* 极窄屏放开最小宽度，避免浮层撑破视口。 */
@media (max-width: 400px) {
  .pwd-overlay {
    padding: 16px;
  }
  .pwd-modal {
    min-width: 0;
  }
}
</style>
