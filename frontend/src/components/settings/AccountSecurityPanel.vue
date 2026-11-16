<script setup lang="ts">
import { computed, ref } from 'vue'
import { profileApi, type UserProfile } from '@/api/profile'

const props = defineProps<{
  profile: UserProfile
}>()

const emit = defineEmits<{
  (e: 'force-logout', message: string): void
}>()

const newEmail = ref('')
const emailPassword = ref('')
const savingEmail = ref(false)
const emailError = ref('')

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const savingPassword = ref(false)
const passwordError = ref('')

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const primaryRoleLabel = computed(() => {
  const roles = props.profile.roles || []
  if (roles.includes('ADMIN')) return '管理员'
  if (roles.includes('TEACHER')) return '教师'
  return '学生'
})

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function handleChangeEmail() {
  emailError.value = ''
  const normalizedEmail = newEmail.value.trim().toLowerCase()

  if (!normalizedEmail) {
    emailError.value = '请输入新的登录邮箱'
    return
  }
  if (!EMAIL_PATTERN.test(normalizedEmail)) {
    emailError.value = '请输入有效的邮箱地址'
    return
  }
  if (props.profile.email && normalizedEmail === props.profile.email.trim().toLowerCase()) {
    emailError.value = '新邮箱不能与当前绑定邮箱相同'
    return
  }
  if (!emailPassword.value) {
    emailError.value = '请输入当前登录密码以验证身份'
    return
  }

  savingEmail.value = true
  try {
    await profileApi.changeEmail({
      currentPassword: emailPassword.value,
      newEmail: normalizedEmail,
    })
    emit('force-logout', '绑定邮箱已更换，所有设备登录已失效，请重新登录')
  } catch (err: unknown) {
    emailError.value = err instanceof Error ? err.message : '换绑邮箱失败'
  } finally {
    savingEmail.value = false
  }
}

async function handleChangePassword() {
  passwordError.value = ''

  if (!oldPassword.value) {
    passwordError.value = '请输入原密码'
    return
  }
  if (!newPassword.value) {
    passwordError.value = '请输入新密码'
    return
  }
  if (newPassword.value.length < 6 || newPassword.value.length > 32) {
    passwordError.value = '新密码长度须在 6~32 位之间'
    return
  }
  if (newPassword.value === oldPassword.value) {
    passwordError.value = '新密码不能与原密码相同'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }

  savingPassword.value = true
  try {
    await profileApi.changePassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
    })
    emit('force-logout', '密码已修改，所有设备登录已失效，请使用新密码重新登录')
  } catch (err: unknown) {
    passwordError.value = err instanceof Error ? err.message : '修改密码失败'
  } finally {
    savingPassword.value = false
  }
}
</script>

<template>
  <section
    id="panel-security"
    class="security-panel"
    aria-labelledby="security-section-heading"
  >
    <!-- 顶部 4 列只读凭据概览条 -->
    <div class="credential-strip">
      <div class="strip-head">
        <div class="strip-title-group">
          <span class="section-eyebrow mono">// SECURITY.ACCESS</span>
          <h2 id="security-section-heading" class="section-title">凭据与会话安全</h2>
        </div>
        <span class="hud-pill tone-teal mono">TOKEN.VERSIONING</span>
      </div>

      <div class="account-meta-grid">
        <div class="meta-cell">
          <span class="meta-label mono">UID</span>
          <span class="meta-value mono tabular-nums">UID: {{ profile.id }}</span>
        </div>
        <div class="meta-cell">
          <span class="meta-label mono">ROLE</span>
          <span class="meta-value">{{ primaryRoleLabel }}</span>
        </div>
        <div class="meta-cell">
          <span class="meta-label mono">EMAIL</span>
          <span class="meta-value mono">{{ profile.email || '未绑定邮箱' }}</span>
        </div>
        <div class="meta-cell">
          <span class="meta-label mono">JOINED</span>
          <span class="meta-value mono tabular-nums">{{ formatDate(profile.createdAt) }}</span>
        </div>
      </div>
    </div>

    <!-- 下方双列安全操作卡片 -->
    <div class="security-actions-grid">
      <div class="security-card">
        <div class="security-card-head">
          <div class="card-head-title-group">
            <span class="card-eyebrow mono">// SECURITY.EMAIL</span>
            <h3 class="block-title">换绑登录邮箱</h3>
          </div>
          <span class="hud-pill tone-warning mono">REVOKE SESSIONS</span>
        </div>

        <form data-test="email-form" class="security-form" @submit.prevent="handleChangeEmail">
          <p class="block-desc">
            需验证当前登录密码；换绑成功后所有旧设备令牌将立即失效，需使用新邮箱重新登录。
          </p>

          <div v-if="emailError" class="state-banner is-error security-error-banner" role="alert">
            <span>{{ emailError }}</span>
          </div>

          <div class="form-field">
            <label for="sec-new-email" class="field-label">新邮箱地址</label>
            <input
              id="sec-new-email"
              v-model="newEmail"
              type="email"
              class="settings-input mono"
              maxlength="128"
              placeholder="name@example.com"
            />
          </div>

          <div class="form-field">
            <label for="sec-email-pwd" class="field-label">当前登录密码</label>
            <input
              id="sec-email-pwd"
              v-model="emailPassword"
              type="password"
              class="settings-input"
              autocomplete="current-password"
              placeholder="输入当前登录密码以确认换绑"
            />
          </div>

          <div class="form-actions">
            <button
              type="submit"
              class="btn-graphite-primary"
              :disabled="savingEmail || savingPassword"
            >
              {{ savingEmail ? '提交中…' : '确认换绑邮箱' }}
            </button>
          </div>
        </form>
      </div>

      <div class="security-card">
        <div class="security-card-head">
          <div class="card-head-title-group">
            <span class="card-eyebrow mono">// SECURITY.PASSWORD</span>
            <h3 class="block-title">修改登录密码</h3>
          </div>
          <span class="hud-pill tone-warning mono">TOKEN VERSION +1</span>
        </div>

        <form
          data-test="password-form"
          class="security-form"
          @submit.prevent="handleChangePassword"
        >
          <p class="block-desc">
            修改成功后会递增令牌版本，立即注销当前及所有已登录设备会话，需使用新密码重新登录。
          </p>

          <div v-if="passwordError" class="state-banner is-error security-error-banner" role="alert">
            <span>{{ passwordError }}</span>
          </div>

          <div class="form-field">
            <label for="sec-old-pwd" class="field-label">原密码</label>
            <input
              id="sec-old-pwd"
              v-model="oldPassword"
              type="password"
              class="settings-input"
              autocomplete="current-password"
              placeholder="请输入当前使用的密码"
            />
          </div>

          <div class="form-grid-2">
            <div class="form-field">
              <label for="sec-new-pwd" class="field-label">新密码</label>
              <input
                id="sec-new-pwd"
                v-model="newPassword"
                type="password"
                class="settings-input"
                autocomplete="new-password"
                placeholder="6~32 位新密码"
              />
            </div>

            <div class="form-field">
              <label for="sec-confirm-pwd" class="field-label">确认新密码</label>
              <input
                id="sec-confirm-pwd"
                v-model="confirmPassword"
                type="password"
                class="settings-input"
                autocomplete="new-password"
                placeholder="再次输入新密码"
              />
            </div>
          </div>

          <div class="form-actions">
            <button
              type="submit"
              class="btn-graphite-primary"
              :disabled="savingPassword || savingEmail"
            >
              {{ savingPassword ? '提交中…' : '更新密码并重新登录' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.security-panel {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* 顶部 4 列只读凭据条 */
.credential-strip {
  background: var(--surface-white, #ffffff);
  border: 1px solid var(--kece-border, #e4e7ec);
  border-radius: 0;
  box-shadow: var(--shadow-sm);
}

.strip-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 14px 24px;
  background: var(--kece-surface-muted, #f8fafc);
  border-bottom: 1px solid var(--kece-border, #e4e7ec);
}

.strip-title-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.section-eyebrow {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-secondary, #555555);
}

.section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink, #111111);
}

.account-meta-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.meta-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 20px;
  border-right: 1px solid var(--kece-border, #e4e7ec);
  min-width: 0;
}

.meta-cell:last-child {
  border-right: none;
}

.meta-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--ink-secondary, #555555);
}

.meta-value {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink, #111111);
  word-break: break-word;
}

/* 下方双列高影响安全操作网格 */
.security-actions-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  align-items: stretch;
}

.security-card {
  background: var(--surface-white, #ffffff);
  border: 1px solid var(--kece-border, #e4e7ec);
  border-radius: 0;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
}

.security-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px 24px;
  background: var(--kece-surface-muted, #f8fafc);
  border-bottom: 1px solid var(--kece-border, #e4e7ec);
}

.card-head-title-group {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.card-eyebrow {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-secondary, #555555);
}

.block-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink, #111111);
}

.security-form {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px 24px 24px;
}

.block-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-secondary, #555555);
}

.security-error-banner {
  margin-bottom: 0;
}

.form-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.field-label {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink, #111111);
}

/* 使用独立命名避免与全局 .field-input { border: none; background: transparent } 冲突 */
.settings-input {
  width: 100%;
  height: 40px;
  padding: 8px 12px;
  border: 1px solid var(--line, #ced4da);
  border-radius: 0;
  background: var(--surface-white, #ffffff);
  font-family: inherit;
  font-size: 14px;
  color: var(--ink, #111111);
  outline: none;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.settings-input::placeholder {
  color: #64748b;
}

.settings-input:hover {
  border-color: #94a3b8;
}

.settings-input:focus {
  border-color: var(--kece-accent, #137969);
  box-shadow: 0 0 0 2px rgba(19, 121, 105, 0.14);
}

.form-actions {
  margin-top: auto;
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--kece-border, #e4e7ec);
}

.btn-graphite-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 0 18px;
  border: 1px solid var(--button, #1e1e1e);
  border-radius: 0;
  background: var(--button, #1e1e1e);
  color: #ffffff;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.btn-graphite-primary:hover:not(:disabled) {
  background: #333333;
  border-color: #333333;
}

.btn-graphite-primary:disabled {
  background: #e2e8f0;
  border-color: #cbd5e1;
  color: #475569;
  cursor: not-allowed;
}

@media (max-width: 860px) {
  .account-meta-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .meta-cell {
    border-bottom: 1px solid var(--kece-border, #e4e7ec);
  }

  .meta-cell:nth-child(2n) {
    border-right: none;
  }

  .meta-cell:nth-last-child(-n + 2) {
    border-bottom: none;
  }

  .security-actions-grid {
    grid-template-columns: 1fr;
    gap: 20px;
  }
}

@media (max-width: 560px) {
  .account-meta-grid,
  .form-grid-2 {
    grid-template-columns: 1fr;
  }

  .meta-cell {
    border-right: none;
    border-bottom: 1px solid var(--kece-border, #e4e7ec);
  }

  .meta-cell:last-child {
    border-bottom: none;
  }

  .strip-head,
  .security-card-head,
  .security-form {
    padding-left: 16px;
    padding-right: 16px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .settings-input,
  .btn-graphite-primary {
    transition: none;
  }
}
</style>
