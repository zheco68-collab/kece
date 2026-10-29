<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isRegister = computed(() => route.name === 'register')
const loading = ref(false)
const submitError = ref('')
const showPassword = ref(false)

const form = reactive({
  email: '',
  password: '',
})

const errors = reactive({
  email: '',
  password: '',
})

function validate(): boolean {
  errors.email = ''
  errors.password = ''

  let ok = true
  const emailVal = form.email.trim()
  if (!emailVal) {
    errors.email = '请输入电子邮箱'
    ok = false
  } else {
    const isLegacyUsername = !isRegister.value && !emailVal.includes('@')
    if (!isLegacyUsername) {
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
      if (!emailRegex.test(emailVal)) {
        errors.email = '请输入有效的邮箱地址'
        ok = false
      }
    }
  }

  if (!form.password) {
    errors.password = '请输入密码'
    ok = false
  } else if (form.password.length < 6 || form.password.length > 64) {
    errors.password = '密码需为 6-64 个字符'
    ok = false
  }
  return ok
}

// 防开放重定向校验
function resolveTarget(): string {
  const raw = route.query.redirect
  const target = Array.isArray(raw) ? raw[0] : raw
  if (
    typeof target === 'string' &&
    target.startsWith('/') &&
    !target.startsWith('//') &&
    !target.startsWith('/\\')
  ) {
    return target
  }
  return '/'
}

async function submit() {
  if (loading.value || !validate()) return
  loading.value = true
  submitError.value = ''
  try {
    const auth = isRegister.value
      ? await authApi.register({
          email: form.email.trim(),
          password: form.password,
        })
      : await authApi.login({
          email: form.email.trim(),
          password: form.password,
        })
    userStore.setAuth(auth)
    router.push(resolveTarget())
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : '请求失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-container">
      <!-- 左侧：品牌展示区（严格对齐 VI 设计手册登录页左侧展示） -->
      <div class="auth-brand-side">
        <div class="brand-hero">
          <RouterLink to="/" class="brand-logo-link">
            <img src="/kece-mark.png" alt="KECE 课测" class="brand-hero-mark" />
          </RouterLink>
          <p class="brand-hero-tagline">多维度编程与技能评测平台</p>
        </div>
      </div>

      <!-- 右侧：登录/注册浮动卡片（对齐 VI 手册右侧圆角卡片与输入框图标） -->
      <div class="auth-form-side">
        <div class="auth-card">
          <div class="card-header">
            <h2 class="card-title">{{ isRegister ? '邮箱快速注册' : '邮箱登录' }}</h2>
            <p class="card-subtitle">
              {{ isRegister ? '输入常用邮箱即可秒级开启你的技术成长之旅' : '输入你的邮箱与密码以继续作答与评测' }}
            </p>
          </div>

          <form class="auth-form" @submit.prevent="submit">
            <!-- 统一邮箱输入框（登录/注册公用） -->
            <div class="input-group">
              <div class="input-wrapper" :class="{ 'has-error': errors.email }">
                <svg class="field-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
                  <rect x="2" y="4" width="20" height="16" rx="2" />
                  <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
                </svg>
                <input
                  v-model="form.email"
                  class="native-input"
                  type="email"
                  :placeholder="isRegister ? '请输入电子邮箱（用于登录与通知）' : '请输入注册邮箱'"
                  autocomplete="email"
                />
              </div>
              <p v-if="errors.email" class="error-tip">{{ errors.email }}</p>
            </div>

            <!-- 密码输入框 -->
            <div class="input-group">
              <div class="input-wrapper" :class="{ 'has-error': errors.password }">
                <svg class="field-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9">
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                  <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                </svg>
                <input
                  v-model="form.password"
                  class="native-input"
                  :type="showPassword ? 'text' : 'password'"
                  placeholder="请输入密码（至少6位）"
                  autocomplete="current-password"
                />
                <button
                  type="button"
                  class="pwd-toggle"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  tabindex="-1"
                  @click="showPassword = !showPassword"
                >
                  <svg v-if="!showPassword" class="eye-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                  <svg v-else class="eye-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                    <line x1="1" y1="1" x2="23" y2="23" />
                  </svg>
                </button>
              </div>
              <p v-if="errors.password" class="error-tip">{{ errors.password }}</p>
            </div>

            <!-- 接口提交错误提示 -->
            <div v-if="submitError" class="submit-alert">
              <svg class="alert-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
              <span>{{ submitError }}</span>
            </div>

            <!-- 登录/注册胶囊按钮 -->
            <button class="btn btn-primary btn-block submit-btn" :disabled="loading" type="submit">
              <span v-if="loading" class="btn-spinner"></span>
              <span>{{ loading ? '处理中…' : isRegister ? '立即注册' : '登录' }}</span>
            </button>
          </form>

          <!-- 底部辅助链接 -->
          <div class="card-footer">
            <template v-if="!isRegister">
              <span class="footer-link muted-tip">忘记密码？请联系管理员</span>
              <RouterLink to="/register" class="footer-link primary-action">没有账号？免费注册</RouterLink>
            </template>
            <template v-else>
              <span class="footer-link muted-tip"></span>
              <RouterLink to="/login" class="footer-link primary-action">已有账号？去登录</RouterLink>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* =========================================
   登录与注册整体布局（严格对齐 VI 设计手册）
   ========================================= */
.auth-page {
  min-height: 100vh;
  background: #f7f8fa;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
}

.auth-container {
  width: 100%;
  max-width: 960px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: center;
  min-height: 540px;
  gap: 32px;
}

/* =========================================
   左侧品牌展示区
   ========================================= */
.auth-brand-side {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
}

.brand-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.brand-logo-link {
  display: inline-block;
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.brand-logo-link:hover {
  transform: scale(1.03);
}

.brand-hero-mark {
  width: 180px;
  height: 180px;
  object-fit: contain;
  filter: drop-shadow(0 12px 24px rgba(0, 0, 0, 0.06));
}

.brand-hero-tagline {
  margin-top: 14px;
  font-size: 15px;
  color: #7b8190;
  letter-spacing: 0.4px;
  font-weight: 400;
}

/* =========================================
   右侧表单卡片区（VI 规范微圆角浮动卡片）
   ========================================= */
.auth-form-side {
  display: flex;
  align-items: center;
  justify-content: center;
}

.auth-card {
  width: 100%;
  max-width: 400px;
  background: #ffffff;
  border-radius: var(--radius);
  padding: 44px 38px;
  box-shadow:
    0 20px 48px rgba(17, 24, 39, 0.07),
    0 2px 6px rgba(17, 24, 39, 0.02);
  border: 1px solid rgba(0, 0, 0, 0.05);
}

.card-header {
  margin-bottom: 28px;
}

.card-title {
  font-size: 24px;
  font-weight: 700;
  color: #111827;
  margin: 0 0 6px;
  letter-spacing: -0.3px;
}

.card-subtitle {
  font-size: 13.5px;
  color: #858b98;
  margin: 0;
  line-height: 1.5;
}

/* =========================================
   输入框控件组合（线性图标 + 浅灰包围盒）
   ========================================= */
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.input-group {
  display: flex;
  flex-direction: column;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  height: 48px;
  background: #f4f5f8;
  border-radius: 0;
  padding: 0 14px;
  border: 1px solid transparent;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.input-wrapper:hover {
  background: #eff1f5;
}

.input-wrapper:focus-within {
  background: #ffffff;
  border-color: #111827;
  box-shadow: 0 0 0 3px rgba(17, 24, 39, 0.08);
}

.input-wrapper.has-error {
  border-color: #e5484d;
  background: #fdf2f2;
}

.field-icon {
  width: 19px;
  height: 19px;
  color: #8d93a1;
  flex-shrink: 0;
  margin-right: 12px;
  transition: color 0.15s ease;
}

.input-wrapper:focus-within .field-icon {
  color: #111827;
}

.native-input {
  flex: 1;
  height: 100%;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14.5px;
  color: #111827;
  font-family: inherit;
  padding: 0;
}

.native-input::placeholder {
  color: #9ea4b0;
  font-size: 14px;
}

.pwd-toggle {
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  color: #8d93a1;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.15s ease;
}

.pwd-toggle:hover {
  color: #111827;
}

.eye-icon {
  width: 18px;
  height: 18px;
}

.error-tip {
  margin: 5px 0 0 4px;
  font-size: 12px;
  color: #e5484d;
}

/* =========================================
   提交警告与操作按钮
   ========================================= */
.submit-alert {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fde8e8;
  color: #c81e1e;
  padding: 10px 14px;
  border-radius: 0;
  font-size: 13px;
  line-height: 1.4;
}

.alert-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.submit-btn {
  height: 48px;
  font-size: 15.5px;
  font-weight: 600;
  border-radius: var(--pill);
  background: #111827;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-shadow: 0 8px 20px rgba(17, 24, 39, 0.14);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  margin-top: 6px;
}

.submit-btn:hover:not(:disabled) {
  background: #000000;
  transform: translateY(-1px);
  box-shadow: 0 12px 24px rgba(17, 24, 39, 0.2);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.btn-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* =========================================
   底部链接区
   ========================================= */
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24px;
  font-size: 13.5px;
}

.footer-link {
  text-decoration: none;
  transition: color 0.15s ease;
}

.muted-tip {
  color: #8c919d;
}

.primary-action {
  color: #111827;
  font-weight: 600;
}

.primary-action:hover {
  color: #4b5563;
  text-decoration: underline;
}

/* =========================================
   移动端与小屏幕自适应
   ========================================= */
@media (max-width: 820px) {
  .auth-container {
    grid-template-columns: 1fr;
    min-height: auto;
    gap: 20px;
  }
  .auth-brand-side {
    padding: 16px 0 0;
  }
  .brand-hero-mark {
    width: 120px;
    height: 120px;
  }
  .auth-card {
    padding: 32px 24px;
    border-radius: 0;
  }
}
</style>
