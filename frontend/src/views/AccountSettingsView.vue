<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { profileApi, type UserProfile } from '@/api/profile'
import { notifyCrossTabLogout } from '@/api/request'
import AccountSecurityPanel from '@/components/settings/AccountSecurityPanel.vue'
import ProfileSettingsPanel from '@/components/settings/ProfileSettingsPanel.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'

type SettingsTab = 'profile' | 'security'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const toast = useToast()
const { confirm } = useConfirm()

const profile = ref<UserProfile | null>(null)
const loading = ref(true)
const loadError = ref('')
const profileDirty = ref(false)
const bypassLeaveGuard = ref(false)
const tablistRef = ref<HTMLElement | null>(null)

function normalizeTab(raw: unknown): SettingsTab {
  return raw === 'security' ? 'security' : 'profile'
}

const activeTab = ref<SettingsTab>(normalizeTab(route.query.tab))

watch(
  () => route.query.tab,
  (nextTab) => {
    activeTab.value = normalizeTab(nextTab)
  },
)

const primaryRoleInfo = computed(() => {
  const roles = profile.value?.roles || []
  if (roles.includes('ADMIN')) {
    return { label: '管理员', class: 'role-admin' }
  }
  if (roles.includes('TEACHER')) {
    return { label: '教师', class: 'role-teacher' }
  }
  return { label: '学生', class: 'role-student' }
})

const hudAvatarChar = computed(() => {
  return profile.value?.username?.charAt(0)?.toUpperCase() || 'U'
})

async function loadProfile() {
  loading.value = true
  loadError.value = ''
  try {
    profile.value = await profileApi.getProfile()
  } catch (err: unknown) {
    loadError.value = err instanceof Error ? err.message : '加载账户信息失败'
  } finally {
    loading.value = false
  }
}

async function confirmDiscardIfDirty(): Promise<boolean> {
  if (!profileDirty.value) return true
  return confirm({
    title: '放弃未保存的更改？',
    message: '个人资料有未保存的修改，切换或离开后草稿将丢失。',
    confirmText: '放弃修改',
    cancelText: '继续编辑',
    danger: true,
  })
}

async function selectTab(nextTab: SettingsTab) {
  if (activeTab.value === nextTab) return
  if (activeTab.value === 'profile') {
    const ok = await confirmDiscardIfDirty()
    if (!ok) return
    profileDirty.value = false
  }
  activeTab.value = nextTab
  await router.replace({
    query: {
      ...route.query,
      tab: nextTab,
    },
  })
}

async function onTabKeydown(e: KeyboardEvent) {
  const tabs: SettingsTab[] = ['profile', 'security']
  const currentIdx = tabs.indexOf(activeTab.value)
  if (currentIdx === -1) return
  let nextIdx = -1
  if (e.key === 'ArrowRight') nextIdx = (currentIdx + 1) % tabs.length
  else if (e.key === 'ArrowLeft') nextIdx = (currentIdx - 1 + tabs.length) % tabs.length
  else if (e.key === 'Home') nextIdx = 0
  else if (e.key === 'End') nextIdx = tabs.length - 1
  if (nextIdx === -1) return

  e.preventDefault()
  const nextTab = tabs[nextIdx]
  await selectTab(nextTab)
  if (activeTab.value === nextTab) {
    await nextTick()
    const targetBtn = tablistRef.value?.querySelector<HTMLButtonElement>(
      `#settings-tab-${nextTab}`,
    )
    targetBtn?.focus()
  }
}

function onProfileUpdated(updated: UserProfile) {
  profile.value = updated
  profileDirty.value = false
}

async function onForceLogout(message: string) {
  bypassLeaveGuard.value = true
  profileDirty.value = false
  userStore.clearAuth()
  notifyCrossTabLogout()
  toast.success(message)
  await router.push('/login')
}

onBeforeRouteLeave(async () => {
  if (bypassLeaveGuard.value) return true
  return confirmDiscardIfDirty()
})

onMounted(() => {
  loadProfile()
})
</script>

<template>
  <div class="settings-page">
    <div class="container settings-container">
      <header class="page-head">
        <div class="page-head-left">
          <span class="settings-eyebrow mono">// ACCOUNT.SETTINGS</span>
          <h1 class="settings-title">账户设置</h1>
          <p class="settings-subtitle">管理你的公开名片资料、绑定邮箱与账号安全凭据</p>
        </div>

        <aside
          v-if="profile"
          data-test="account-hud-card"
          class="account-hud-card"
          aria-label="当前账户概览"
        >
          <div class="hud-top-row">
            <span class="hud-code mono">// ACCOUNT.HUD</span>
            <span
              class="hud-pill mono"
              :class="profileDirty ? 'tone-warning' : 'tone-teal'"
            >
              {{ profileDirty ? 'DRAFT.UNSAVED' : 'SYS.VERIFIED' }}
            </span>
          </div>

          <div class="hud-identity-row">
            <div class="hud-avatar-box">
              <img
                v-if="profile.avatarUrl"
                :src="profile.avatarUrl"
                class="hud-avatar-img"
                :alt="`${profile.username} 的头像`"
              />
              <span v-else class="hud-avatar-fallback mono" aria-hidden="true">
                {{ hudAvatarChar }}
              </span>
            </div>

            <div class="hud-identity-meta">
              <div class="hud-name-line">
                <strong class="hud-username">{{ profile.username }}</strong>
                <span class="hud-role-tag" :class="primaryRoleInfo.class">
                  {{ primaryRoleInfo.label }}
                </span>
              </div>
              <div class="hud-detail-line mono tabular-nums">
                <span>UID: {{ profile.id }}</span>
                <span class="hud-sep" aria-hidden="true">·</span>
                <span class="hud-email">{{ profile.email || '未绑定邮箱' }}</span>
              </div>
            </div>
          </div>
        </aside>
      </header>

      <div v-if="loading" class="settings-state-card loading-box" role="status">
        <div class="loading-spinner" aria-hidden="true"></div>
        <span class="muted">正在加载账户信息…</span>
      </div>

      <div v-else-if="loadError" class="settings-state-card">
        <div class="state-banner is-error" role="alert">
          <span>{{ loadError }}</span>
          <button type="button" class="btn btn-ghost btn-sm btn-inline-retry" @click="loadProfile">
            重试
          </button>
        </div>
      </div>

      <template v-else-if="profile">
        <div class="settings-tabs-bar">
          <div
            ref="tablistRef"
            class="settings-tablist"
            role="tablist"
            aria-label="账户设置分类"
            @keydown="onTabKeydown"
          >
            <button
              id="settings-tab-profile"
              type="button"
              role="tab"
              data-test="tab-profile"
              class="settings-tab"
              :class="{ active: activeTab === 'profile' }"
              :tabindex="activeTab === 'profile' ? 0 : -1"
              :aria-selected="activeTab === 'profile' ? 'true' : 'false'"
              aria-controls="panel-profile"
              @click="selectTab('profile')"
            >
              <span class="tab-code mono">01</span>
              <span>个人资料</span>
            </button>
            <button
              id="settings-tab-security"
              type="button"
              role="tab"
              data-test="tab-security"
              class="settings-tab"
              :class="{ active: activeTab === 'security' }"
              :tabindex="activeTab === 'security' ? 0 : -1"
              :aria-selected="activeTab === 'security' ? 'true' : 'false'"
              aria-controls="panel-security"
              @click="selectTab('security')"
            >
              <span class="tab-code mono">02</span>
              <span>账号与安全</span>
            </button>
          </div>

          <span class="tablist-telemetry mono">
            ACTIVE // {{ activeTab === 'profile' ? 'PROFILE.CONFIG' : 'SECURITY.ACCESS' }}
          </span>
        </div>

        <ProfileSettingsPanel
          v-if="activeTab === 'profile'"
          :profile="profile"
          @updated="onProfileUpdated"
          @dirty-change="profileDirty = $event"
        />

        <AccountSecurityPanel
          v-else
          :profile="profile"
          @force-logout="onForceLogout"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  padding: 32px 0 60px;
}

.settings-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
  font-variant-numeric: tabular-nums;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.page-head-left {
  min-width: 0;
  flex: 1 1 320px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.settings-eyebrow {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--kece-accent-ink, #0f5f53);
}

.settings-title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.25;
  color: var(--ink, #111111);
  letter-spacing: -0.025em;
}

.settings-subtitle {
  margin: 0;
  font-size: 14px;
  color: var(--ink-secondary, #555555);
}

/* 右侧 Account HUD 摘要卡：Paper White + 2px 青绿顶线 */
.account-hud-card {
  min-width: 320px;
  max-width: 440px;
  flex: 0 1 380px;
  padding: 12px 16px;
  background: var(--surface-white, #ffffff);
  border: 1px solid var(--kece-border, #e4e7ec);
  border-top: 2px solid var(--kece-accent, #137969);
  border-radius: 0;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.hud-top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.hud-code {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-secondary, #555555);
}

.hud-identity-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.hud-avatar-box {
  position: relative;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--line, #ced4da);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.hud-avatar-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  display: block;
}

.hud-avatar-fallback {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-secondary, #555555);
}

.hud-identity-meta {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.hud-name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.hud-username {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink, #111111);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hud-role-tag {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 7px;
  line-height: 1.4;
  white-space: nowrap;
  flex-shrink: 0;
}

.hud-role-tag.role-admin {
  background: var(--ink, #111111);
  color: #ffffff;
}

.hud-role-tag.role-teacher {
  background: var(--kece-accent, #137969);
  color: #ffffff;
}

.hud-role-tag.role-student {
  background: #334155;
  color: #ffffff;
}

.hud-detail-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-secondary, #555555);
  min-width: 0;
}

.hud-sep {
  color: var(--line, #ced4da);
}

.hud-email {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 加载 / 错误卡片 */
.settings-state-card {
  background: #ffffff;
  border: 1px solid var(--kece-border, #e4e7ec);
  border-top: 2px solid var(--kece-accent, #137969);
  border-radius: 0;
  padding: 24px;
  box-shadow: var(--shadow-sm);
}

.loading-box {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 顶部横向分段 Tab 栏 */
.settings-tabs-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--kece-border, #e4e7ec);
}

.settings-tablist {
  display: flex;
  align-items: stretch;
  gap: 4px;
}

.settings-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 11px 20px;
  margin-bottom: -1px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-secondary, #555555);
  background: transparent;
  border: 1px solid transparent;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  white-space: nowrap;
  transition:
    color 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.settings-tab:hover {
  color: var(--ink, #111111);
  background: var(--kece-surface-muted, #f8fafc);
}

.settings-tab.active {
  background: var(--kece-accent-soft, #eef6f4);
  color: var(--kece-accent-ink, #0f5f53);
  border-color: var(--kece-border, #e4e7ec);
  border-bottom: 2px solid var(--kece-accent, #137969);
}

.tab-code {
  font-size: 11px;
  font-weight: 700;
  color: inherit;
  opacity: 0.85;
}

.tablist-telemetry {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--ink-secondary, #555555);
  white-space: nowrap;
}

@media (max-width: 860px) {
  .account-hud-card {
    max-width: none;
    width: 100%;
    flex-basis: 100%;
  }
}

@media (max-width: 640px) {
  .tablist-telemetry {
    display: none;
  }

  .settings-tablist {
    width: 100%;
  }

  .settings-tab {
    flex: 1;
    justify-content: center;
    padding: 11px 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .settings-tab {
    transition: none;
  }
}
</style>
