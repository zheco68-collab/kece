<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { profileApi } from '@/api/profile';

const props = defineProps<{
  visible: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'success'): void;
}>();

const oldPassword = ref('');
const newPassword = ref('');
const confirmPassword = ref('');
const loading = ref(false);
const errorMessage = ref('');
const closeBtnRef = ref<HTMLButtonElement | null>(null);
let returnFocusEl: HTMLElement | null = null;

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
      oldPassword.value = '';
      newPassword.value = '';
      confirmPassword.value = '';
      errorMessage.value = '';
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
);

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown);
});

async function handleSubmit() {
  errorMessage.value = '';

  if (!oldPassword.value) {
    errorMessage.value = '请输入原密码';
    return;
  }
  if (!newPassword.value) {
    errorMessage.value = '请输入新密码';
    return;
  }
  if (newPassword.value.length < 6) {
    errorMessage.value = '新密码长度至少需要 6 位';
    return;
  }
  if (newPassword.value === oldPassword.value) {
    errorMessage.value = '新密码不能与原密码相同';
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的新密码不一致';
    return;
  }

  loading.value = true;
  try {
    await profileApi.changePassword({
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
    });
    emit('success');
    emit('close');
  } catch (err: unknown) {
    errorMessage.value = err instanceof Error ? err.message : '修改密码失败';
  } finally {
    loading.value = false;
  }
}

function handleClose() {
  if (loading.value) return;
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
        aria-labelledby="change-pwd-modal-title"
      >
        <div class="modal-header">
          <h2 id="change-pwd-modal-title" class="modal-title">修改密码</h2>
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

        <form class="modal-body" @submit.prevent="handleSubmit">
          <div v-if="errorMessage" class="submit-error" role="alert">
            {{ errorMessage }}
          </div>

          <div class="form-group">
            <label for="cp-old-pwd" class="form-label">原密码</label>
            <input
              id="cp-old-pwd"
              v-model="oldPassword"
              type="password"
              class="input"
              autocomplete="current-password"
              placeholder="请输入当前使用的密码"
            />
          </div>

          <div class="form-group">
            <label for="cp-new-pwd" class="form-label">新密码</label>
            <input
              id="cp-new-pwd"
              v-model="newPassword"
              type="password"
              class="input"
              autocomplete="new-password"
              placeholder="不少于 6 位的新密码"
            />
          </div>

          <div class="form-group">
            <label for="cp-confirm-pwd" class="form-label">确认新密码</label>
            <input
              id="cp-confirm-pwd"
              v-model="confirmPassword"
              type="password"
              class="input"
              autocomplete="new-password"
              placeholder="再次输入新密码"
            />
          </div>

          <div class="modal-footer">
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="loading"
              @click="handleClose"
            >
              取消
            </button>
            <button class="btn btn-primary btn-sm" type="submit" :disabled="loading">
              {{ loading ? '提交中…' : '确认修改' }}
            </button>
          </div>
        </form>
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
  max-width: 440px;
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
  gap: 18px;
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

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
</style>
