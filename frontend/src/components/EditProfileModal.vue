<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { profileApi } from '@/api/profile';
import { useUserStore } from '@/stores/user';

const props = defineProps<{
  visible: boolean;
  initialBio: string | null;
  initialGithubUrl: string | null;
  initialAvatarUrl: string | null;
  username: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'updated', data: { bio: string; githubUrl: string; avatarUrl: string | null }): void;
}>();

const userStore = useUserStore();

const bio = ref('');
const githubUrl = ref('');
const currentAvatarUrl = ref<string | null>(null);
const previewAvatarUrl = ref<string | null>(null);
const selectedFile = ref<File | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const closeBtnRef = ref<HTMLButtonElement | null>(null);
let returnFocusEl: HTMLElement | null = null;

const saving = ref(false);
const uploadingAvatar = ref(false);
const errorMessage = ref('');
const successNotice = ref('');

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.visible) {
    e.preventDefault();
    handleClose();
  }
}

watch(
  () => props.visible,
  (val, prev) => {
    if (val) {
      returnFocusEl = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      bio.value = props.initialBio || '';
      githubUrl.value = props.initialGithubUrl || '';
      currentAvatarUrl.value = props.initialAvatarUrl || null;
      previewAvatarUrl.value = null;
      selectedFile.value = null;
      errorMessage.value = '';
      successNotice.value = '';
      window.addEventListener('keydown', handleKeydown);
      nextTick(() => {
        closeBtnRef.value?.focus();
      });
    } else if (prev) {
      window.removeEventListener('keydown', handleKeydown);
      const target = returnFocusEl;
      returnFocusEl = null;
      nextTick(() => {
        target?.focus();
      });
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown);
});

function triggerFileInput() {
  fileInput.value?.click();
}

function onFileChange(e: Event) {
  errorMessage.value = '';
  successNotice.value = '';
  const target = e.target as HTMLInputElement;
  if (!target.files || target.files.length === 0) return;

  const file = target.files[0];
  // 校验大小：5MB
  if (file.size > 5 * 1024 * 1024) {
    errorMessage.value = '图片大小不能超过 5MB';
    target.value = '';
    return;
  }

  // 校验格式
  const validTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
  if (!validTypes.includes(file.type)) {
    errorMessage.value = '仅支持 JPG、PNG、WEBP、GIF 格式的图片';
    target.value = '';
    return;
  }

  selectedFile.value = file;
  previewAvatarUrl.value = URL.createObjectURL(file);
}

async function handleUploadAvatar() {
  if (!selectedFile.value) return;
  uploadingAvatar.value = true;
  errorMessage.value = '';
  successNotice.value = '';

  try {
    const res = await profileApi.uploadAvatar(selectedFile.value);
    currentAvatarUrl.value = res.avatarUrl;
    previewAvatarUrl.value = null;
    selectedFile.value = null;
    userStore.updateUserInfo({ avatarUrl: res.avatarUrl });
    successNotice.value = '头像上传成功';
  } catch (err: unknown) {
    errorMessage.value = err instanceof Error ? err.message : '头像上传失败';
  } finally {
    uploadingAvatar.value = false;
  }
}

async function handleSave() {
  errorMessage.value = '';
  successNotice.value = '';

  // 若选择了新头像但尚未单独点击上传，则先上传头像
  if (selectedFile.value) {
    try {
      uploadingAvatar.value = true;
      const avatarRes = await profileApi.uploadAvatar(selectedFile.value);
      currentAvatarUrl.value = avatarRes.avatarUrl;
      previewAvatarUrl.value = null;
      selectedFile.value = null;
      userStore.updateUserInfo({ avatarUrl: avatarRes.avatarUrl });
    } catch (err: unknown) {
      errorMessage.value = err instanceof Error ? err.message : '头像上传失败';
      uploadingAvatar.value = false;
      return;
    } finally {
      uploadingAvatar.value = false;
    }
  }

  saving.value = true;
  try {
    const updated = await profileApi.updateProfile({
      bio: bio.value.trim(),
      githubUrl: githubUrl.value.trim(),
    });
    userStore.updateUserInfo({
      bio: updated.bio,
      githubUrl: updated.githubUrl,
    });
    emit('updated', {
      bio: updated.bio || '',
      githubUrl: updated.githubUrl || '',
      avatarUrl: currentAvatarUrl.value,
    });
    emit('close');
  } catch (err: unknown) {
    errorMessage.value = err instanceof Error ? err.message : '保存个人资料失败';
  } finally {
    saving.value = false;
  }
}

function handleClose() {
  if (saving.value || uploadingAvatar.value) return;
  emit('close');
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="modal-overlay" @click.self="handleClose">
      <div
        class="modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="edit-profile-modal-title"
      >
        <div class="modal-header">
          <h2 id="edit-profile-modal-title" class="modal-title">编辑个人资料</h2>
          <button
            ref="closeBtnRef"
            class="modal-close-btn"
            type="button"
            aria-label="关闭"
            @click="handleClose"
          >
            <span aria-hidden="true">✕</span>
          </button>
        </div>

        <div class="modal-body">
          <div v-if="errorMessage" class="submit-error" role="alert">
            {{ errorMessage }}
          </div>
          <div v-if="successNotice" class="submit-success" role="status">
            {{ successNotice }}
          </div>

          <!-- 头像设置 -->
          <div class="form-group">
            <label for="edit-profile-avatar-input" class="form-label"
              >个人头像 (支持 5MB 以内图片)</label
            >
            <div class="avatar-edit-box">
              <div class="avatar-preview-wrap">
                <img
                  v-if="previewAvatarUrl || currentAvatarUrl"
                  :src="previewAvatarUrl || currentAvatarUrl || ''"
                  class="avatar-img"
                  alt="Avatar"
                />
                <div v-else class="avatar-placeholder mono">
                  {{ username.charAt(0).toUpperCase() }}
                </div>
              </div>

              <div class="avatar-actions">
                <input
                  id="edit-profile-avatar-input"
                  ref="fileInput"
                  type="file"
                  class="hidden-file-input"
                  accept="image/png, image/jpeg, image/jpg, image/webp"
                  @change="onFileChange"
                />
                <button
                  class="btn btn-ghost btn-sm"
                  type="button"
                  :disabled="uploadingAvatar || saving"
                  @click="triggerFileInput"
                >
                  {{ selectedFile ? '重选图片' : '选择图片' }}
                </button>
                <button
                  v-if="selectedFile"
                  class="btn btn-primary btn-sm"
                  type="button"
                  :disabled="uploadingAvatar || saving"
                  @click="handleUploadAvatar"
                >
                  {{ uploadingAvatar ? '上传中…' : '确认上传头像' }}
                </button>
                <span v-if="selectedFile" class="file-name mono">
                  {{ selectedFile.name }}
                </span>
              </div>
            </div>
          </div>

          <!-- 个性签名 / Bio -->
          <div class="form-group">
            <div class="label-with-count">
              <label for="edit-profile-bio" class="form-label">个人简介 / Slogan</label>
              <span class="char-count mono">{{ bio.length }} / 200</span>
            </div>
            <textarea
              id="edit-profile-bio"
              v-model="bio"
              class="textarea"
              maxlength="200"
              rows="3"
              placeholder="一句话介绍自己，支持写技术栈或格言…"
            ></textarea>
          </div>

          <!-- GitHub 主页 -->
          <div class="form-group">
            <label for="edit-profile-github" class="form-label">GitHub 主页链接</label>
            <input
              id="edit-profile-github"
              v-model="githubUrl"
              type="url"
              class="input"
              placeholder="https://github.com/your-username"
            />
          </div>
        </div>

        <div class="modal-footer">
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            :disabled="saving || uploadingAvatar"
            @click="handleClose"
          >
            取消
          </button>
          <button
            class="btn btn-primary btn-sm"
            type="button"
            :disabled="saving || uploadingAvatar"
            @click="handleSave"
          >
            {{ saving ? '正在保存…' : '保存修改' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 17, 17, 0.5);
  backdrop-filter: blur(4px);
}

.modal-card {
  width: 100%;
  max-width: 520px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  box-shadow: var(--shadow-md);
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid var(--line);
}

.modal-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.modal-close-btn {
  font-size: 16px;
  color: var(--ink-tertiary);
  padding: 4px;
  line-height: 1;
}

.modal-close-btn:hover {
  color: var(--ink);
}

.modal-body {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.submit-success {
  background: var(--success-bg);
  color: var(--success);
  border: 1px solid #bbf7d0;
  padding: 8px 12px;
  font-size: 13px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.label-with-count {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.char-count {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.avatar-edit-box {
  display: flex;
  align-items: center;
  gap: 16px;
}

.avatar-preview-wrap {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border: 1px solid var(--line);
  background: var(--bg);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-placeholder {
  font-size: 26px;
  font-weight: 700;
  color: var(--ink-secondary);
}

.avatar-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.hidden-file-input {
  display: none;
}

.file-name {
  font-size: 12px;
  color: var(--ink-secondary);
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--surface);
  font-family: inherit;
  font-size: 14px;
  color: var(--ink);
  resize: vertical;
  transition: border-color 0.15s ease;
}

.textarea:focus {
  border-color: var(--ink);
}

.textarea:focus-visible {
  border-color: var(--kece-accent);
  outline: 2px solid var(--kece-accent);
  outline-offset: 2px;
}

.textarea::placeholder {
  color: var(--ink-tertiary);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--line);
  background: var(--bg);
}

@media (prefers-reduced-motion: reduce) {
  .textarea {
    transition: none;
  }
}
</style>
