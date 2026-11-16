<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { authApi } from '@/api/auth'
import { profileApi, type UserProfile } from '@/api/profile'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  profile: UserProfile
}>()

const emit = defineEmits<{
  (e: 'updated', profile: UserProfile): void
  (e: 'dirty-change', dirty: boolean): void
}>()

const userStore = useUserStore()
const toast = useToast()

const username = ref('')
const bio = ref('')
const githubUrl = ref('')
const previewAvatarUrl = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const uploadingAvatar = ref(false)
const saving = ref(false)
const errorMessage = ref('')
let activeObjectUrl: string | null = null

const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
const MAX_AVATAR_BYTES = 5 * 1024 * 1024
const USERNAME_PATTERN = /^[\u4e00-\u9fa5a-zA-Z0-9_-]{3,32}$/

function syncFromProfile(p: UserProfile) {
  username.value = p.username || ''
  bio.value = p.bio || ''
  githubUrl.value = p.githubUrl || ''
}

watch(
  () => props.profile,
  (next) => {
    syncFromProfile(next)
  },
  { immediate: true },
)

const isDirty = computed(() => {
  const baseUsername = props.profile.username || ''
  const baseBio = props.profile.bio || ''
  const baseGithub = props.profile.githubUrl || ''
  return (
    username.value.trim() !== baseUsername.trim() ||
    bio.value.trim() !== baseBio.trim() ||
    githubUrl.value.trim() !== baseGithub.trim()
  )
})

watch(
  isDirty,
  (dirty) => {
    emit('dirty-change', dirty)
  },
  { immediate: true },
)

const displayedAvatarUrl = computed(() => previewAvatarUrl.value || props.profile.avatarUrl || null)

const previewUsername = computed(() => {
  return username.value.trim() || props.profile.username || '未命名用户'
})

const previewBio = computed(() => {
  return bio.value.trim() || '这位同学很低调，暂无个性签名~'
})

const previewGithub = computed(() => {
  return githubUrl.value.trim()
})

const avatarFallbackChar = computed(() => {
  return previewUsername.value.charAt(0)?.toUpperCase() || 'U'
})

const primaryRoleInfo = computed(() => {
  const roles = props.profile.roles || []
  if (roles.includes('ADMIN')) {
    return { label: '管理员', class: 'role-admin' }
  }
  if (roles.includes('TEACHER')) {
    return { label: '教师', class: 'role-teacher' }
  }
  return { label: '学生', class: 'role-student' }
})

function revokePreviewUrl() {
  if (activeObjectUrl) {
    URL.revokeObjectURL(activeObjectUrl)
    activeObjectUrl = null
  }
}

onBeforeUnmount(() => {
  revokePreviewUrl()
})

function triggerFileInput() {
  fileInput.value?.click()
}

function resetDraft() {
  errorMessage.value = ''
  syncFromProfile(props.profile)
}

async function onFileChange(e: Event) {
  errorMessage.value = ''
  const target = e.target as HTMLInputElement
  if (!target.files || target.files.length === 0) return

  const file = target.files[0]
  if (file.size > MAX_AVATAR_BYTES) {
    errorMessage.value = '图片大小不能超过 5MB'
    target.value = ''
    return
  }
  if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
    errorMessage.value = '仅支持 JPG、PNG、WEBP、GIF 格式的图片'
    target.value = ''
    return
  }

  revokePreviewUrl()
  activeObjectUrl = URL.createObjectURL(file)
  previewAvatarUrl.value = activeObjectUrl

  uploadingAvatar.value = true
  try {
    const res = await profileApi.uploadAvatar(file)
    userStore.updateUserInfo({ avatarUrl: res.avatarUrl })
    emit('updated', {
      ...props.profile,
      avatarUrl: res.avatarUrl,
    })
    previewAvatarUrl.value = null
    revokePreviewUrl()
    toast.success('头像已更新')
  } catch (err: unknown) {
    errorMessage.value = err instanceof Error ? err.message : '头像上传失败'
  } finally {
    uploadingAvatar.value = false
    target.value = ''
  }
}

function isValidGithubUrl(url: string): boolean {
  if (!url) return true
  return /^https?:\/\/.+/i.test(url)
}

async function handleSave() {
  errorMessage.value = ''
  const trimmedUsername = username.value.trim()
  const trimmedBio = bio.value.trim()
  const trimmedGithub = githubUrl.value.trim()

  if (!USERNAME_PATTERN.test(trimmedUsername)) {
    errorMessage.value = '用户名须为 3~32 位，仅支持中英文、数字、下划线和连字符，不能包含 @ 或空白'
    return
  }
  if (trimmedBio.length > 200) {
    errorMessage.value = '个人简介最多 200 个字符'
    return
  }
  if (trimmedGithub.length > 200) {
    errorMessage.value = 'GitHub 链接最多 200 个字符'
    return
  }
  if (!isValidGithubUrl(trimmedGithub)) {
    errorMessage.value = 'GitHub 链接必须以 http:// 或 https:// 开头'
    return
  }

  saving.value = true
  try {
    const prevUsername = props.profile.username
    const updated = await profileApi.updateProfile({
      username: trimmedUsername,
      bio: trimmedBio,
      githubUrl: trimmedGithub,
    })
    userStore.updateUserInfo({
      username: updated.username,
      bio: updated.bio,
      githubUrl: updated.githubUrl,
    })
    if (updated.username !== prevUsername && userStore.refreshToken) {
      try {
        const refreshed = await authApi.refresh(userStore.refreshToken)
        userStore.setAuth(refreshed)
      } catch {
        // 静默刷新失败不阻断已保存的资料更新
      }
    }
    emit('updated', updated)
    syncFromProfile(updated)
    toast.success('个人资料已保存')
  } catch (err: unknown) {
    errorMessage.value = err instanceof Error ? err.message : '保存个人资料失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section id="panel-profile" class="profile-panel" aria-labelledby="profile-section-heading">
    <div v-if="errorMessage" class="state-banner is-error profile-error-banner" role="alert">
      <span>{{ errorMessage }}</span>
    </div>

    <div class="profile-grid">
      <!-- 左栏：实时名片预览 + 头像上传控制 -->
      <aside class="preview-card" data-test="live-profile-card" aria-label="公开名片实时预览">
        <div class="preview-head">
          <span class="preview-eyebrow mono">// LIVE.CARD.PREVIEW</span>
          <span class="hud-pill mono" :class="isDirty ? 'tone-warning' : 'tone-teal'">
            {{ isDirty ? 'MODIFIED' : 'SYNCED' }}
          </span>
        </div>

        <div class="preview-avatar-section">
          <div class="preview-avatar-box">
            <img
              v-if="displayedAvatarUrl"
              :src="displayedAvatarUrl"
              class="preview-avatar-img"
              :alt="`${previewUsername} 的头像预览`"
            />
            <span v-else class="preview-avatar-placeholder mono" aria-hidden="true">
              {{ avatarFallbackChar }}
            </span>
          </div>

          <div class="preview-avatar-controls">
            <span class="preview-avatar-label">个人头像</span>
            <input
              id="settings-avatar-input"
              ref="fileInput"
              type="file"
              class="sr-only"
              accept="image/png, image/jpeg, image/jpg, image/webp, image/gif"
              @change="onFileChange"
            />
            <button
              type="button"
              class="btn-settings-ghost btn-upload-avatar"
              :disabled="uploadingAvatar || saving"
              @click="triggerFileInput"
            >
              {{ uploadingAvatar ? '正在上传…' : '选择并上传头像' }}
            </button>
            <p class="preview-avatar-hint mono">PNG / JPG / WEBP / GIF · ≤ 5MB</p>
          </div>
        </div>

        <div class="preview-identity">
          <div class="preview-name-row">
            <strong class="preview-name">{{ previewUsername }}</strong>
            <span class="preview-role" :class="primaryRoleInfo.class">
              {{ primaryRoleInfo.label }}
            </span>
          </div>

          <div class="preview-uid-row mono tabular-nums">UID: {{ profile.id }}</div>

          <div class="preview-slogan">
            <span class="preview-slogan-label mono">// SLOGAN</span>
            <p class="preview-slogan-text">{{ previewBio }}</p>
          </div>

          <dl class="preview-meta-list">
            <div class="preview-meta-item">
              <dt class="mono">EMAIL</dt>
              <dd class="mono">{{ profile.email || '未绑定邮箱' }}</dd>
            </div>
            <div class="preview-meta-item">
              <dt class="mono">GITHUB</dt>
              <dd class="mono" :class="{ 'is-empty': !previewGithub }">
                {{ previewGithub || '未设置 GitHub 主页链接' }}
              </dd>
            </div>
          </dl>
        </div>
      </aside>

      <!-- 右栏：个人资料表单卡片 -->
      <div class="editor-card">
        <div class="editor-head">
          <div>
            <span class="section-eyebrow mono">// PROFILE.CONFIG</span>
            <h2 id="profile-section-heading" class="section-title">个人资料</h2>
          </div>
          <span class="hud-pill mono" :class="isDirty ? 'tone-warning' : 'tone-neutral'">
            {{ isDirty ? 'UNSAVED' : 'CLEAN' }}
          </span>
        </div>

        <form data-test="profile-form" class="profile-form" @submit.prevent="handleSave">
          <div class="form-field">
            <div class="label-row">
              <label for="settings-username" class="field-label">用户名</label>
              <span class="char-counter mono tabular-nums">
                {{ username.trim().length }} / 32
              </span>
            </div>
            <input
              id="settings-username"
              v-model="username"
              type="text"
              class="settings-input"
              maxlength="32"
              placeholder="3~32 位中英文、数字、下划线或连字符（不含 @ 与空格）"
            />
            <p class="field-caption">修改用户名后系统将自动刷新登录凭证，无需手动退出重新登录。</p>
          </div>

          <div class="form-field">
            <div class="label-row">
              <label for="settings-bio" class="field-label">个人简介 / Slogan</label>
              <span class="char-counter mono tabular-nums">{{ bio.length }} / 200</span>
            </div>
            <textarea
              id="settings-bio"
              v-model="bio"
              class="settings-textarea"
              maxlength="200"
              rows="4"
              placeholder="一句话介绍自己，支持写技术栈或格言…"
            ></textarea>
          </div>

          <div class="form-field">
            <div class="label-row">
              <label for="settings-github" class="field-label">GitHub 主页链接</label>
              <span class="char-counter mono tabular-nums">
                {{ githubUrl.trim().length }} / 200
              </span>
            </div>
            <input
              id="settings-github"
              v-model="githubUrl"
              type="text"
              class="settings-input mono"
              maxlength="200"
              placeholder="https://github.com/your-username"
            />
          </div>

          <div class="form-actions">
            <button
              type="button"
              data-test="reset-draft-btn"
              class="btn-settings-ghost"
              :disabled="!isDirty || saving || uploadingAvatar"
              @click="resetDraft"
            >
              重置草稿
            </button>
            <button
              type="submit"
              data-test="save-profile-btn"
              class="btn-teal-primary"
              :disabled="saving || uploadingAvatar"
            >
              {{ saving ? '保存中…' : '保存资料' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.profile-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile-error-banner {
  margin-bottom: 0;
}

.profile-grid {
  display: grid;
  grid-template-columns: minmax(300px, 340px) minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}

/* 左栏：实时名片预览卡（Paper White + Instrument Slate 顶条 + 2px 青绿顶线） */
.preview-card {
  background: var(--surface-white, #ffffff);
  border: 1px solid var(--kece-border, #e4e7ec);
  border-top: 2px solid var(--kece-accent, #137969);
  border-radius: 0;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  background: var(--kece-surface-muted, #f8fafc);
  border-bottom: 1px solid var(--kece-border, #e4e7ec);
}

.preview-eyebrow {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--kece-accent-ink, #0f5f53);
}

.preview-avatar-section {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border-bottom: 1px solid var(--kece-border, #e4e7ec);
}

.preview-avatar-box {
  position: relative;
  width: 96px;
  height: 96px;
  flex-shrink: 0;
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--line, #ced4da);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-avatar-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  display: block;
}

.preview-avatar-placeholder {
  font-size: 36px;
  font-weight: 800;
  color: var(--ink-secondary, #555555);
}

.preview-avatar-controls {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.preview-avatar-label {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink, #111111);
}

.btn-upload-avatar {
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
}

.preview-avatar-hint {
  margin: 0;
  font-size: 11px;
  color: var(--ink-secondary, #555555);
  line-height: 1.4;
}

.preview-identity {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.preview-name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.preview-name {
  font-size: 20px;
  font-weight: 800;
  color: var(--ink, #111111);
  letter-spacing: -0.02em;
  word-break: break-word;
}

.preview-role {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  line-height: 1.4;
  white-space: nowrap;
}

.preview-role.role-admin {
  background: var(--ink, #111111);
  color: #ffffff;
}

.preview-role.role-teacher {
  background: var(--kece-accent, #137969);
  color: #ffffff;
}

.preview-role.role-student {
  background: #334155;
  color: #ffffff;
}

.preview-uid-row {
  display: inline-flex;
  align-self: flex-start;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary, #555555);
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--kece-border, #e4e7ec);
  padding: 2px 8px;
}

.preview-slogan {
  padding: 12px 14px;
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--kece-border, #e4e7ec);
  border-left: 3px solid var(--kece-accent, #137969);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preview-slogan-label {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--kece-accent-ink, #0f5f53);
}

.preview-slogan-text {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink, #111111);
  line-height: 1.5;
  word-break: break-word;
}

.preview-meta-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--kece-border, #e4e7ec);
}

.preview-meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.preview-meta-item dt {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--ink-secondary, #555555);
}

.preview-meta-item dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink, #111111);
  word-break: break-all;
}

.preview-meta-item dd.is-empty {
  color: var(--ink-secondary, #555555);
}

/* 右栏：全边框资料编辑卡 */
.editor-card {
  background: var(--surface-white, #ffffff);
  border: 1px solid var(--kece-border, #e4e7ec);
  border-radius: 0;
  box-shadow: var(--shadow-sm);
  display: flex;
  flex-direction: column;
}

.editor-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 24px;
  background: var(--kece-surface-muted, #f8fafc);
  border-bottom: 1px solid var(--kece-border, #e4e7ec);
}

.section-eyebrow {
  display: block;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-secondary, #555555);
  margin-bottom: 4px;
}

.section-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--ink, #111111);
  letter-spacing: -0.01em;
}

.profile-form {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.field-label {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink, #111111);
}

.char-counter {
  font-size: 12px;
  color: var(--ink-secondary, #555555);
}

/* 使用独立命名避免与全局 .field-input { border: none; background: transparent } 冲突 */
.settings-input,
.settings-textarea {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--line, #ced4da);
  border-radius: 0;
  background: var(--surface-white, #ffffff);
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
  color: var(--ink, #111111);
  outline: none;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.settings-input {
  height: 40px;
}

.settings-textarea {
  min-height: 104px;
  resize: vertical;
}

.settings-input::placeholder,
.settings-textarea::placeholder {
  color: #64748b;
}

.settings-input:hover,
.settings-textarea:hover {
  border-color: #94a3b8;
}

.settings-input:focus,
.settings-textarea:focus {
  border-color: var(--kece-accent, #137969);
  box-shadow: 0 0 0 2px rgba(19, 121, 105, 0.14);
}

.field-caption {
  margin: 0;
  font-size: 12px;
  color: var(--ink-secondary, #555555);
  line-height: 1.45;
}

.form-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 16px;
  border-top: 1px solid var(--kece-border, #e4e7ec);
}

.btn-settings-ghost {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid var(--line, #ced4da);
  border-radius: 0;
  background: var(--surface-white, #ffffff);
  color: var(--ink, #111111);
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.btn-settings-ghost:hover:not(:disabled) {
  background: var(--kece-accent-soft, #eef6f4);
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
}

.btn-settings-ghost:disabled {
  background: var(--kece-surface-muted, #f8fafc);
  border-color: var(--kece-border, #e4e7ec);
  color: #5c6370;
  cursor: not-allowed;
}

.btn-teal-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 0 18px;
  border: 1px solid var(--kece-accent, #137969);
  border-radius: 0;
  background: var(--kece-accent, #137969);
  color: #ffffff;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.btn-teal-primary:hover:not(:disabled) {
  background: var(--kece-accent-hover, #0e6053);
  border-color: var(--kece-accent-hover, #0e6053);
}

.btn-teal-primary:disabled {
  background: #e2e8f0;
  border-color: #cbd5e1;
  color: #475569;
  cursor: not-allowed;
}

@media (max-width: 860px) {
  .profile-grid {
    grid-template-columns: 1fr;
    gap: 20px;
  }
}

@media (max-width: 520px) {
  .editor-head,
  .profile-form {
    padding-left: 16px;
    padding-right: 16px;
  }

  .preview-avatar-section {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .settings-input,
  .settings-textarea,
  .btn-settings-ghost,
  .btn-teal-primary {
    transition: none;
  }
}
</style>
