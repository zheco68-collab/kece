<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  profileApi,
  type HeatmapData,
  type UserExamRecord,
  type UserProfile,
} from '@/api/profile'
import { submissionApi } from '@/api/submission'
import { databaseApi } from '@/api/database'
import { objectiveApi } from '@/api/objective'
import HeatmapCalendar from '@/components/HeatmapCalendar.vue'
import EditProfileModal from '@/components/EditProfileModal.vue'
import ChangePasswordModal from '@/components/ChangePasswordModal.vue'
import { useUserStore } from '@/stores/user'
import { renderMarkdown } from '@/utils/markdown'
import { copyToClipboard } from '@/utils/clipboard'
import type {
  DbSubmissionRecord,
  ObjectiveSubmissionRecord,
  ObjectiveType,
  SubmissionRecord,
  SubmissionStatus,
} from '@/types'
import { LANGUAGE_LABELS, STATUS_ABBR, statusTone } from '@/utils/submission'

const route = useRoute()
const userStore = useUserStore()

// 目标用户 ID 解析：若路由传了 /profile/:id 则为该用户的 id，否则为本人
const targetUserId = computed<number | undefined>(() => {
  const p = route.params.id
  if (p && !Array.isArray(p) && !Number.isNaN(Number(p))) {
    return Number(p)
  }
  return undefined
})

const isSelf = computed(() => {
  if (!targetUserId.value) return true
  return targetUserId.value === userStore.userInfo?.id
})

const isAdminOrTeacher = computed(() => {
  const roles = userStore.userInfo?.roles || []
  return roles.includes('ADMIN') || roles.includes('TEACHER')
})

// ---- 个人基础信息 ----
const profile = ref<UserProfile | null>(null)
const profileLoading = ref(true)
const profileError = ref('')

// ---- 打卡热力图与考试战报 ----
const heatmapData = ref<HeatmapData | null>(null)
const heatmapLoading = ref(true)
const exams = ref<UserExamRecord[]>([])
const examsLoading = ref(true)

// ---- 提交记录整合模块 ----
type TabKey = 'code' | 'database' | 'objective'
const activeTab = ref<TabKey>('code')
const pageSize = 10

// 编程题提交
const codeStatus = ref<SubmissionStatus | ''>('')
const codePage = ref(1)
const codeList = ref<SubmissionRecord[]>([])
const codeTotal = ref(0)
const codeLoading = ref(false)
const codeSelected = ref<SubmissionRecord | null>(null)

// 数据库题提交
const dbStatus = ref<SubmissionStatus | ''>('')
const dbPage = ref(1)
const dbList = ref<DbSubmissionRecord[]>([])
const dbTotal = ref(0)
const dbLoading = ref(false)
const dbSelected = ref<DbSubmissionRecord | null>(null)

// 客观题作答
const objStatus = ref<SubmissionStatus | ''>('')
const objPage = ref(1)
const objList = ref<ObjectiveSubmissionRecord[]>([])
const objTotal = ref(0)
const objLoading = ref(false)
const objSelected = ref<ObjectiveSubmissionRecord | null>(null)

// 状态筛选选项
const codeStatusFilters: Array<{ value: SubmissionStatus | ''; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'ACCEPTED', label: 'AC (通过)' },
  { value: 'WRONG_ANSWER', label: 'WA (答案错误)' },
  { value: 'TIME_LIMIT_EXCEEDED', label: 'TLE (运行超时)' },
  { value: 'MEMORY_LIMIT_EXCEEDED', label: 'MLE (内存超限)' },
  { value: 'RUNTIME_ERROR', label: 'RE (运行错误)' },
  { value: 'COMPILE_ERROR', label: 'CE (编译错误)' },
  { value: 'SYSTEM_ERROR', label: 'SE (系统错误)' },
]

const dbStatusFilters: Array<{ value: SubmissionStatus | ''; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'ACCEPTED', label: 'AC (通过)' },
  { value: 'WRONG_ANSWER', label: 'WA (答案错误)' },
  { value: 'TIME_LIMIT_EXCEEDED', label: 'TLE (运行超时)' },
  { value: 'SYSTEM_ERROR', label: 'SE (系统错误)' },
]

const objStatusFilters: Array<{ value: SubmissionStatus | ''; label: string }> = [
  { value: '', label: '全部结果' },
  { value: 'ACCEPTED', label: '答对' },
  { value: 'WRONG_ANSWER', label: '答错' },
]

const OBJECTIVE_TYPE_LABELS: Record<ObjectiveType, string> = {
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  JUDGE: '判断题',
  FILL: '填空题',
}

// 弹窗与 Toast
const editModalVisible = ref(false)
const passwordModalVisible = ref(false)
const toastMessage = ref('')
const copyTip = ref(false)

function showToast(msg: string) {
  toastMessage.value = msg
  setTimeout(() => {
    if (toastMessage.value === msg) {
      toastMessage.value = ''
    }
  }, 3500)
}

async function copyCode(text: string) {
  const ok = await copyToClipboard(text)
  if (ok) {
    copyTip.value = true
    setTimeout(() => {
      copyTip.value = false
    }, 1500)
  }
}

function formatDate(iso: string | null): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const date = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${date}`
}

function formatTime(iso: string | null): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const date = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${date} ${h}:${min}`
}

function parseCode(codeContent: string): string {
  if (!codeContent) return ''
  try {
    const obj = JSON.parse(codeContent)
    if (obj && typeof obj === 'object') {
      const val = Object.values(obj).find((v) => typeof v === 'string' && v.length > 0)
      if (typeof val === 'string') return val
    }
  } catch {
    return codeContent
  }
  return codeContent
}

function stripMarkdown(md: string, max = 50): string {
  if (!md) return ''
  const plain = md
    .replace(/```[\s\S]*?```/g, '')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/[#*_\-[\]()!]/g, '')
    .trim()
  return plain.length > max ? `${plain.slice(0, max)}…` : plain
}

// 身份标签配色规范：管理员(紫色) / 教师(绿色) / 学生(黑色)
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

// 数据加载
async function loadProfile() {
  profileLoading.value = true
  profileError.value = ''
  try {
    profile.value = await profileApi.getProfile(targetUserId.value)
  } catch (err: unknown) {
    profileError.value = err instanceof Error ? err.message : '获取用户信息失败'
  } finally {
    profileLoading.value = false
  }
}

async function loadHeatmap() {
  heatmapLoading.value = true
  try {
    heatmapData.value = await profileApi.getHeatmap(targetUserId.value)
  } catch {
    heatmapData.value = null
  } finally {
    heatmapLoading.value = false
  }
}

async function loadExams() {
  examsLoading.value = true
  try {
    exams.value = await profileApi.getExams(targetUserId.value)
  } catch {
    exams.value = []
  } finally {
    examsLoading.value = false
  }
}

async function loadCodeSubmissions() {
  if (!isSelf.value && !isAdminOrTeacher.value) {
    codeList.value = []
    codeTotal.value = 0
    return
  }
  codeLoading.value = true
  try {
    const res = await submissionApi.list({
      userId: targetUserId.value,
      status: codeStatus.value || undefined,
      page: codePage.value,
      size: pageSize,
    })
    codeList.value = res.list
    codeTotal.value = res.total
  } catch {
    codeList.value = []
    codeTotal.value = 0
  } finally {
    codeLoading.value = false
  }
}

async function loadDbSubmissions() {
  if (!isSelf.value && !isAdminOrTeacher.value) {
    dbList.value = []
    dbTotal.value = 0
    return
  }
  dbLoading.value = true
  try {
    const res = await databaseApi.listSubmissions({
      userId: targetUserId.value,
      status: dbStatus.value || undefined,
      page: dbPage.value,
      size: pageSize,
    })
    dbList.value = res.list
    dbTotal.value = res.total
  } catch {
    dbList.value = []
    dbTotal.value = 0
  } finally {
    dbLoading.value = false
  }
}

async function loadObjSubmissions() {
  if (!isSelf.value && !isAdminOrTeacher.value) {
    objList.value = []
    objTotal.value = 0
    return
  }
  objLoading.value = true
  try {
    const correct =
      objStatus.value === 'ACCEPTED' ? 1 : objStatus.value === 'WRONG_ANSWER' ? 0 : undefined
    const res = await objectiveApi.listSubmissions({
      userId: targetUserId.value,
      correct: correct as 0 | 1 | undefined,
      page: objPage.value,
      size: pageSize,
    })
    objList.value = res.list
    objTotal.value = res.total
  } catch {
    objList.value = []
    objTotal.value = 0
  } finally {
    objLoading.value = false
  }
}

function switchTab(t: TabKey) {
  if (activeTab.value === t) return
  activeTab.value = t
  if (t === 'code' && codeList.value.length === 0 && !codeLoading.value) loadCodeSubmissions()
  if (t === 'database' && dbList.value.length === 0 && !dbLoading.value) loadDbSubmissions()
  if (t === 'objective' && objList.value.length === 0 && !objLoading.value) loadObjSubmissions()
}

function loadAllData() {
  loadProfile()
  loadHeatmap()
  loadExams()
  loadCodeSubmissions()
  loadDbSubmissions()
  loadObjSubmissions()
}

watch(
  () => targetUserId.value,
  () => {
    codePage.value = 1
    dbPage.value = 1
    objPage.value = 1
    loadAllData()
  },
)

watch(codeStatus, () => {
  codePage.value = 1
  loadCodeSubmissions()
})

watch(dbStatus, () => {
  dbPage.value = 1
  loadDbSubmissions()
})

watch(objStatus, () => {
  objPage.value = 1
  loadObjSubmissions()
})

function onProfileUpdated(data: {
  bio: string
  githubUrl: string
  avatarUrl: string | null
}) {
  if (profile.value) {
    profile.value.bio = data.bio
    profile.value.githubUrl = data.githubUrl
    if (data.avatarUrl) {
      profile.value.avatarUrl = data.avatarUrl
    }
  }
  showToast('个人资料已成功更新')
}

function onPasswordChanged() {
  showToast('密码修改成功，请妥善保管新密码')
}

function applyTabFromRoute() {
  const tab = route.query.tab
  if (tab === 'database') activeTab.value = 'database'
  else if (tab === 'objective') activeTab.value = 'objective'
  else if (tab === 'code') activeTab.value = 'code'

  if (tab) {
    setTimeout(() => {
      const el = document.getElementById('submissions-section')
      if (el) {
        el.scrollIntoView({ behavior: 'smooth' })
      }
    }, 150)
  }
}

watch(
  () => route.query.tab,
  () => {
    applyTabFromRoute()
  },
)

onMounted(() => {
  applyTabFromRoute()
  loadAllData()
})
</script>

<template>
  <div class="profile-page">
    <div class="container profile-container">
      <!-- 顶部浮层 Toast 提示 -->
      <transition name="toast-fade">
        <div v-if="toastMessage" class="toast-bar">
          <svg class="toast-icon" viewBox="0 0 16 16" fill="currentColor">
            <path d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z" />
          </svg>
          <span>{{ toastMessage }}</span>
        </div>
      </transition>

      <!-- 1. 个人名片 Hero Card -->
      <section v-if="profileError" class="card error-card">
        <p class="submit-error">{{ profileError }}</p>
      </section>

      <section v-else class="card hero-profile-card">
        <div class="hero-main-layout">
          <!-- 方形大头像：128px × 128px，直角，本人访问悬浮提示更换 -->
          <div
            class="hero-avatar-box"
            :class="{ 'is-clickable': isSelf }"
            :title="isSelf ? '点击修改头像与资料' : profile?.username"
            @click="isSelf && (editModalVisible = true)"
          >
            <img
              v-if="profile?.avatarUrl"
              :src="profile.avatarUrl"
              class="avatar-image"
              alt="Avatar"
            />
            <div v-else class="avatar-placeholder-large mono">
              {{ profile?.username?.charAt(0).toUpperCase() || 'U' }}
            </div>
            <div v-if="isSelf" class="avatar-hover-mask">
              <svg class="mask-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
                <circle cx="12" cy="13" r="4"/>
              </svg>
              <span>更换头像</span>
            </div>
          </div>

          <!-- 用户核心信息 -->
          <div class="hero-info-column">
            <div class="user-primary-row">
              <div class="user-title-group">
                <h1 class="user-display-name">{{ profile?.username }}</h1>
                <!-- 身份徽章：管理员(紫色) / 教师(绿色) / 学生(黑色) -->
                <span class="role-badge" :class="primaryRoleInfo.class">
                  {{ primaryRoleInfo.label }}
                </span>
                <span class="user-id-badge mono">UID: {{ profile?.id }}</span>
                <span v-if="!isSelf" class="visitor-badge">访客视角</span>
              </div>

              <!-- 本人视角：编辑资料与修改密码操作 -->
              <div v-if="isSelf" class="hero-actions-group">
                <button
                  class="btn btn-primary btn-sm action-btn"
                  type="button"
                  @click="editModalVisible = true"
                >
                  <svg class="btn-svg" viewBox="0 0 16 16" fill="currentColor">
                    <path d="M11.013 1.427a1.75 1.75 0 0 1 2.474 0l1.086 1.086a1.75 1.75 0 0 1 0 2.474l-8.61 8.61c-.21.21-.47.364-.756.445l-3.251.93a.75.75 0 0 1-.927-.928l.929-3.25c.081-.286.235-.547.445-.758l8.61-8.61Zm1.414 1.06a.25.25 0 0 0-.354 0L10.811 3.75l1.439 1.44 1.263-1.263a.25.25 0 0 0 0-.354l-1.086-1.086ZM9.75 4.81l-6.9 6.9-.64 2.241 2.242-.64 6.9-6.9-1.602-1.602Z"/>
                  </svg>
                  编辑资料
                </button>
                <button
                  class="btn btn-ghost btn-sm action-btn"
                  type="button"
                  @click="passwordModalVisible = true"
                >
                  <svg class="btn-svg" viewBox="0 0 16 16" fill="currentColor">
                    <path d="M4 4a4 4 0 0 1 8 0v2h.25c.966 0 1.75.784 1.75 1.75v5.5A1.75 1.75 0 0 1 12.25 15h-8.5A1.75 1.75 0 0 1 2 13.25v-5.5C2 6.784 2.784 6 3.75 6H4V4Zm1.5 2h5V4a2.5 2.5 0 0 0-5 0v2ZM3.75 7.5a.25.25 0 0 0-.25.25v5.5c0 .138.112.25.25.25h8.5a.25.25 0 0 0 .25-.25v-5.5a.25.25 0 0 0-.25-.25h-8.5Z"/>
                  </svg>
                  修改密码
                </button>
              </div>
            </div>

            <!-- 元信息标签栏 (Email, 加入时间, GitHub) -->
            <div class="user-meta-pills">
              <span v-if="profile?.email" class="meta-pill">
                <svg class="pill-svg" viewBox="0 0 16 16" fill="currentColor">
                  <path d="M1.75 2h12.5c.966 0 1.75.784 1.75 1.75v8.5A1.75 1.75 0 0 1 14.25 14H1.75A1.75 1.75 0 0 1 0 12.25v-8.5C0 2.784.784 2 1.75 2ZM1.5 4.31v7.94c0 .138.112.25.25.25h12.5a.25.25 0 0 0 .25-.25V4.31L8.38 8.64a.75.75 0 0 1-.76 0L1.5 4.31Zm12.63-1.06H1.87L8 7.42l6.13-4.17Z" />
                </svg>
                {{ profile.email }}
              </span>
              <span class="meta-pill">
                <svg class="pill-svg" viewBox="0 0 16 16" fill="currentColor">
                  <path d="M4.75 0a.75.75 0 0 1 .75.75V2h5V.75a.75.75 0 0 1 1.5 0V2h1.25c.966 0 1.75.784 1.75 1.75v10.5A1.75 1.75 0 0 1 13.25 16H2.75A1.75 1.75 0 0 1 1 14.25V3.75C1 2.784 1.784 2 2.75 2H4V.75A.75.75 0 0 1 4.75 0ZM2.5 7.5v6.75c0 .138.112.25.25.25h10.5a.25.25 0 0 0 .25-.25V7.5H2.5Zm10.75-2.5V3.75a.25.25 0 0 0-.25-.25H2.75a.25.25 0 0 0-.25.25V5h10.5Z" />
                </svg>
                加入于 {{ formatDate(profile?.createdAt || null) }}
              </span>
              <a
                v-if="profile?.githubUrl"
                :href="profile.githubUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="meta-pill github-pill"
                title="打开 GitHub 主页"
              >
                <svg class="pill-svg" viewBox="0 0 16 16" fill="currentColor">
                  <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.012 8.012 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
                </svg>
                <span>GitHub</span>
                <svg class="ext-arrow" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M3 1h8v8M11 1 5 7" />
                </svg>
              </a>
            </div>

            <!-- 个性签名 / Slogan 专属沉浸框 -->
            <div class="user-slogan-box">
              <span class="quote-sign">“</span>
              <p class="slogan-text">
                {{ profile?.bio || '这位同学很低调，暂无个性签名~' }}
              </p>
              <span class="quote-sign">”</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 2. 52-周 AC 打卡热力图 -->
      <section class="profile-section-item">
        <HeatmapCalendar :data="heatmapData" :loading="heatmapLoading" />
      </section>

      <!-- 3. 考试实战档案 -->
      <section class="card profile-block-card">
        <div class="block-header">
          <div class="block-title-box">
            <div class="block-title-row">
              <h3 class="block-title">
                {{ isSelf ? '我的考试实战' : (profile?.username || '用户') + ' 的考试实战' }}
              </h3>
              <span class="block-counter mono">{{ exams.length }} 场</span>
            </div>
            <span class="block-sub">参与并完成提交的在线考试历史成绩与战报</span>
          </div>
        </div>

        <div v-if="examsLoading" class="block-loading muted">
          <div class="loading-spinner"></div><p class="muted" style="margin: 0">正在加载考试记录…</p>
        </div>

        <div v-else-if="exams.length === 0" class="empty-block">
          <svg class="empty-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
            <polyline points="14 2 14 8 20 8"/>
            <line x1="16" y1="13" x2="8" y2="13"/>
            <line x1="16" y1="17" x2="8" y2="17"/>
            <polyline points="10 9 9 9 8 9"/>
          </svg>
          <span class="empty-text">暂无已完成的考试记录</span>
        </div>

        <div v-else class="exam-grid-container">
          <div
            v-for="item in exams"
            :key="item.examId"
            class="exam-battle-card"
          >
            <div class="battle-card-header">
              <div class="battle-title-wrap">
                <span class="battle-title">{{ item.title }}</span>
                <span
                  class="badge exam-mode-pill"
                  :class="item.mode === 'ACM' ? 'mode-acm' : 'mode-oi'"
                >
                  {{ item.mode }}
                </span>
              </div>
              <RouterLink
                :to="`/exams/${item.examId}/result`"
                class="btn btn-ghost btn-sm battle-detail-btn"
              >
                查看战报 →
              </RouterLink>
            </div>

            <p v-if="item.description" class="battle-desc">
              {{ item.description }}
            </p>

            <div class="battle-metrics-row">
              <div class="metric-cell">
                <span class="metric-k">得分</span>
                <span class="metric-v score mono">{{ item.score ?? '-' }} 分</span>
              </div>
              <div class="metric-cell">
                <span class="metric-k">排名</span>
                <span class="metric-v mono">#{{ item.rank ?? '-' }} <small>/ {{ item.totalParticipants }}</small></span>
              </div>
              <div class="metric-cell">
                <span class="metric-k">时长</span>
                <span class="metric-v mono">{{ item.durationMinutes }} min</span>
              </div>
              <div class="metric-cell">
                <span class="metric-k">交卷时间</span>
                <span class="metric-v time mono">{{ formatTime(item.submittedAt) }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 4. 全量整合提交记录模块 (Submissions Module) -->
      <section v-if="isSelf || isAdminOrTeacher" id="submissions-section" class="card profile-block-card">
        <div class="block-header">
          <div class="block-title-box">
            <div class="block-title-row">
              <h3 class="block-title">
                {{ isSelf ? '我的提交记录' : (profile?.username || '用户') + ' 的提交记录' }}
              </h3>
              <span class="block-counter mono">
                {{ activeTab === 'code' ? codeTotal : activeTab === 'database' ? dbTotal : objTotal }} 条
              </span>
            </div>
            <span class="block-sub">算法题代码、数据库 SQL 与客观题作答的全量评测历史</span>
          </div>

          <!-- 右侧状态筛选下拉 -->
          <div class="submissions-filter-box">
            <select
              v-if="activeTab === 'code'"
              v-model="codeStatus"
              class="status-select mono"
            >
              <option v-for="f in codeStatusFilters" :key="f.value" :value="f.value">
                {{ f.label }}
              </option>
            </select>
            <select
              v-else-if="activeTab === 'database'"
              v-model="dbStatus"
              class="status-select mono"
            >
              <option v-for="f in dbStatusFilters" :key="f.value" :value="f.value">
                {{ f.label }}
              </option>
            </select>
            <select
              v-else
              v-model="objStatus"
              class="status-select mono"
            >
              <option v-for="f in objStatusFilters" :key="f.value" :value="f.value">
                {{ f.label }}
              </option>
            </select>
          </div>
        </div>

        <!-- 3 大题型切换标签 -->
        <div class="submissions-tabs">
          <button
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === 'code' }"
            @click="switchTab('code')"
          >
            <span>编程题提交</span>
            <span class="tab-count mono">{{ codeTotal }}</span>
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === 'database' }"
            @click="switchTab('database')"
          >
            <span>数据库提交</span>
            <span class="tab-count mono">{{ dbTotal }}</span>
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: activeTab === 'objective' }"
            @click="switchTab('objective')"
          >
            <span>客观题作答</span>
            <span class="tab-count mono">{{ objTotal }}</span>
          </button>
        </div>

        <!-- Tab 1: 编程题表格 -->
        <template v-if="activeTab === 'code'">
          <div v-if="codeLoading" class="block-loading muted">
            <div class="loading-spinner"></div><p class="muted" style="margin: 0">正在加载编程题提交…</p>
          </div>
          <div v-else-if="codeList.length === 0" class="empty-block">
            <span class="empty-text">暂无编程题提交记录</span>
          </div>
          <div v-else class="table-responsive">
            <table class="timeline-table">
              <thead>
                <tr>
                  <th>提交号</th>
                  <th>题目</th>
                  <th>语言</th>
                  <th>评测状态</th>
                  <th>得分</th>
                  <th>用时</th>
                  <th>内存</th>
                  <th>提交时间</th>
                  <th v-if="isSelf">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in codeList" :key="r.submissionId">
                  <td class="mono sub-id">#{{ r.submissionId }}</td>
                  <td class="q-title-col">
                    <RouterLink :to="`/questions/${r.questionId}`" class="q-link">
                      {{ r.questionTitle || `题目 #${r.questionId}` }}
                    </RouterLink>
                  </td>
                  <td class="mono lang-cell">
                    {{ LANGUAGE_LABELS[r.language] ?? r.language }}
                  </td>
                  <td>
                    <button
                      type="button"
                      class="status-badge"
                      :class="[statusTone(r.status), isSelf ? 'clickable' : '']"
                      :title="isSelf ? '点击查看提交代码' : ''"
                      @click="isSelf ? (codeSelected = r) : null"
                    >
                      {{ STATUS_ABBR[r.status] ?? r.status }}
                    </button>
                  </td>
                  <td class="mono score-cell">{{ r.score ?? '-' }}</td>
                  <td class="mono time-used">{{ r.timeUsedMs != null ? r.timeUsedMs + 'ms' : '-' }}</td>
                  <td class="mono mem-used">{{ r.memoryUsedKb != null ? r.memoryUsedKb + 'KB' : '-' }}</td>
                  <td class="mono date-cell">{{ formatTime(r.createdAt) }}</td>
                  <td v-if="isSelf">
                    <RouterLink
                      :to="`/submissions/${r.submissionId}?questionId=${r.questionId}`"
                      class="detail-action"
                    >
                      详情
                    </RouterLink>
                  </td>
                </tr>
              </tbody>
            </table>

            <!-- 分页 -->
            <div v-if="codeTotal > pageSize" class="pagination-bar">
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="codePage <= 1"
                @click="codePage--; loadCodeSubmissions()"
              >
                上一页
              </button>
              <span class="page-indicator mono">
                第 {{ codePage }} / {{ Math.ceil(codeTotal / pageSize) }} 页 (共 {{ codeTotal }} 条)
              </span>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="codePage >= Math.ceil(codeTotal / pageSize)"
                @click="codePage++; loadCodeSubmissions()"
              >
                下一页
              </button>
            </div>
          </div>
        </template>

        <!-- Tab 2: 数据库题表格 -->
        <template v-else-if="activeTab === 'database'">
          <div v-if="dbLoading" class="block-loading muted">
            <div class="loading-spinner"></div><p class="muted" style="margin: 0">正在加载数据库提交…</p>
          </div>
          <div v-else-if="dbList.length === 0" class="empty-block">
            <span class="empty-text">暂无数据库提交记录</span>
          </div>
          <div v-else class="table-responsive">
            <table class="timeline-table">
              <thead>
                <tr>
                  <th>提交号</th>
                  <th>题目</th>
                  <th>评测状态</th>
                  <th>得分</th>
                  <th>用时</th>
                  <th>提交时间</th>
                  <th v-if="isSelf">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in dbList" :key="r.submissionId">
                  <td class="mono sub-id">#{{ r.submissionId }}</td>
                  <td class="q-title-col">
                    <RouterLink :to="`/database/${r.questionId}`" class="q-link">
                      {{ r.questionTitle || `题目 #${r.questionId}` }}
                    </RouterLink>
                  </td>
                  <td>
                    <button
                      type="button"
                      class="status-badge"
                      :class="[statusTone(r.status), isSelf ? 'clickable' : '']"
                      :title="isSelf ? '点击查看提交 SQL' : ''"
                      @click="isSelf ? (dbSelected = r) : null"
                    >
                      {{ STATUS_ABBR[r.status] ?? r.status }}
                    </button>
                  </td>
                  <td class="mono score-cell">{{ r.score ?? '-' }}</td>
                  <td class="mono time-used">{{ r.timeUsedMs != null ? r.timeUsedMs + 'ms' : '-' }}</td>
                  <td class="mono date-cell">{{ formatTime(r.createdAt) }}</td>
                  <td v-if="isSelf">
                    <RouterLink
                      :to="`/database-submissions/${r.submissionId}`"
                      class="detail-action"
                    >
                      详情
                    </RouterLink>
                  </td>
                </tr>
              </tbody>
            </table>

            <!-- 分页 -->
            <div v-if="dbTotal > pageSize" class="pagination-bar">
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="dbPage <= 1"
                @click="dbPage--; loadDbSubmissions()"
              >
                上一页
              </button>
              <span class="page-indicator mono">
                第 {{ dbPage }} / {{ Math.ceil(dbTotal / pageSize) }} 页 (共 {{ dbTotal }} 条)
              </span>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="dbPage >= Math.ceil(dbTotal / pageSize)"
                @click="dbPage++; loadDbSubmissions()"
              >
                下一页
              </button>
            </div>
          </div>
        </template>

        <!-- Tab 3: 客观题表格 -->
        <template v-else>
          <div v-if="objLoading" class="block-loading muted">
            <div class="loading-spinner"></div><p class="muted" style="margin: 0">正在加载客观题作答…</p>
          </div>
          <div v-else-if="objList.length === 0" class="empty-block">
            <span class="empty-text">暂无客观题作答记录</span>
          </div>
          <div v-else class="table-responsive">
            <table class="timeline-table">
              <thead>
                <tr>
                  <th>提交号</th>
                  <th>题目内容</th>
                  <th>题型</th>
                  <th>作答结果</th>
                  <th>得分</th>
                  <th v-if="isSelf">提交答案</th>
                  <th>作答时间</th>
                  <th v-if="isSelf">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in objList" :key="r.submissionId">
                  <td class="mono sub-id">#{{ r.submissionId }}</td>
                  <td class="q-title-col">
                    <RouterLink :to="`/objective/${r.questionId}`" class="q-link">
                      {{ stripMarkdown(r.questionContent) || `客观题 #${r.questionId}` }}
                    </RouterLink>
                  </td>
                  <td>
                    <span class="badge obj-type-tag">
                      {{ OBJECTIVE_TYPE_LABELS[r.type] ?? r.type }}
                    </span>
                  </td>
                  <td>
                    <button
                      type="button"
                      class="status-badge"
                      :class="[r.correct === 1 ? 'ac' : 'wa', isSelf ? 'clickable' : '']"
                      :title="isSelf ? '点击查看作答明细' : ''"
                      @click="isSelf ? (objSelected = r) : null"
                    >
                      {{ r.correct === 1 ? 'AC' : 'WA' }}
                    </button>
                  </td>
                  <td class="mono score-cell">
                    {{ r.score ?? '-' }} / {{ r.fullScore ?? '-' }}
                  </td>
                  <td v-if="isSelf" class="mono ans-preview">
                    {{ r.answer || '-' }}
                  </td>
                  <td class="mono date-cell">{{ formatTime(r.createdAt) }}</td>
                  <td v-if="isSelf">
                    <button
                      type="button"
                      class="detail-action btn-inline"
                      @click="objSelected = r"
                    >
                      查看
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>

            <!-- 分页 -->
            <div v-if="objTotal > pageSize" class="pagination-bar">
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="objPage <= 1"
                @click="objPage--; loadObjSubmissions()"
              >
                上一页
              </button>
              <span class="page-indicator mono">
                第 {{ objPage }} / {{ Math.ceil(objTotal / pageSize) }} 页 (共 {{ objTotal }} 条)
              </span>
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="objPage >= Math.ceil(objTotal / pageSize)"
                @click="objPage++; loadObjSubmissions()"
              >
                下一页
              </button>
            </div>
          </div>
        </template>
      </section>
    </div>

    <!-- 弹窗 1: 编辑资料 -->
    <EditProfileModal
      :visible="editModalVisible"
      :username="profile?.username || ''"
      :initial-bio="profile?.bio || null"
      :initial-github-url="profile?.githubUrl || null"
      :initial-avatar-url="profile?.avatarUrl || null"
      @close="editModalVisible = false"
      @updated="onProfileUpdated"
    />

    <!-- 弹窗 2: 修改密码 -->
    <ChangePasswordModal
      :visible="passwordModalVisible"
      @close="passwordModalVisible = false"
      @success="onPasswordChanged"
    />

    <!-- 弹窗 3: 编程题代码预览弹窗 -->
    <Teleport to="body">
      <div v-if="codeSelected" class="modal-overlay" @click.self="codeSelected = null">
        <div class="modal-card code-view-modal" role="dialog" aria-modal="true">
          <div class="modal-header">
            <div class="code-modal-title-group">
              <span class="mono code-modal-id">#{{ codeSelected.submissionId }}</span>
              <span class="code-modal-title">{{ codeSelected.questionTitle }}</span>
              <span
                class="status-badge"
                :class="statusTone(codeSelected.status)"
              >
                {{ STATUS_ABBR[codeSelected.status] ?? codeSelected.status }}
              </span>
            </div>
            <button class="modal-close-btn" type="button" @click="codeSelected = null">✕</button>
          </div>

          <div class="modal-body modal-code-body">
            <div class="code-modal-meta mono">
              <span>语言: <b>{{ LANGUAGE_LABELS[codeSelected.language] ?? codeSelected.language }}</b></span>
              <span>得分: <b>{{ codeSelected.score ?? '-' }}</b></span>
              <span>用时: <b>{{ codeSelected.timeUsedMs != null ? codeSelected.timeUsedMs + 'ms' : '-' }}</b></span>
              <span>内存: <b>{{ codeSelected.memoryUsedKb != null ? codeSelected.memoryUsedKb + 'KB' : '-' }}</b></span>
              <span class="copy-action-span">
                <button
                  type="button"
                  class="btn btn-ghost btn-sm copy-btn"
                  @click="copyCode(parseCode(codeSelected.codeContent))"
                >
                  {{ copyTip ? '已复制 ✔' : '复制代码' }}
                </button>
              </span>
            </div>
            <pre class="code-pre mono"><code>{{ parseCode(codeSelected.codeContent) }}</code></pre>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 弹窗 4: 数据库 SQL 预览弹窗 -->
    <Teleport to="body">
      <div v-if="dbSelected" class="modal-overlay" @click.self="dbSelected = null">
        <div class="modal-card code-view-modal" role="dialog" aria-modal="true">
          <div class="modal-header">
            <div class="code-modal-title-group">
              <span class="mono code-modal-id">#{{ dbSelected.submissionId }}</span>
              <span class="code-modal-title">{{ dbSelected.questionTitle }}</span>
              <span
                class="status-badge"
                :class="statusTone(dbSelected.status)"
              >
                {{ STATUS_ABBR[dbSelected.status] ?? dbSelected.status }}
              </span>
            </div>
            <button class="modal-close-btn" type="button" @click="dbSelected = null">✕</button>
          </div>

          <div class="modal-body modal-code-body">
            <div class="code-modal-meta mono">
              <span>类型: <b>SQL 数据库评测</b></span>
              <span>得分: <b>{{ dbSelected.score ?? '-' }}</b></span>
              <span>用时: <b>{{ dbSelected.timeUsedMs != null ? dbSelected.timeUsedMs + 'ms' : '-' }}</b></span>
              <span class="copy-action-span">
                <button
                  type="button"
                  class="btn btn-ghost btn-sm copy-btn"
                  @click="copyCode(dbSelected.sqlContent)"
                >
                  {{ copyTip ? '已复制 ✔' : '复制 SQL' }}
                </button>
              </span>
            </div>
            <pre class="code-pre mono"><code>{{ dbSelected.sqlContent }}</code></pre>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 弹窗 5: 客观题作答预览弹窗 -->
    <Teleport to="body">
      <div v-if="objSelected" class="modal-overlay" @click.self="objSelected = null">
        <div class="modal-card obj-view-modal" role="dialog" aria-modal="true">
          <div class="modal-header">
            <div class="code-modal-title-group">
              <span class="mono code-modal-id">#{{ objSelected.submissionId }}</span>
              <span class="badge obj-type-tag">{{ OBJECTIVE_TYPE_LABELS[objSelected.type] ?? objSelected.type }}</span>
              <span
                class="status-badge"
                :class="objSelected.correct === 1 ? 'ac' : 'wa'"
              >
                {{ objSelected.correct === 1 ? 'AC 答对' : 'WA 答错' }}
              </span>
            </div>
            <button class="modal-close-btn" type="button" @click="objSelected = null">✕</button>
          </div>

          <div class="modal-body obj-modal-body">
            <div class="obj-content-box prose" v-html="renderMarkdown(objSelected.questionContent)"></div>
            <div class="obj-answer-details mono">
              <div class="ans-row">
                <span class="ans-label">提交答案:</span>
                <span class="ans-val" :class="objSelected.correct === 1 ? 'ans-ac' : 'ans-wa'">
                  {{ objSelected.answer || '(未作答)' }}
                </span>
              </div>
              <div v-if="objSelected.correctAnswer" class="ans-row">
                <span class="ans-label">正确答案:</span>
                <span class="ans-val ans-ac">{{ objSelected.correctAnswer }}</span>
              </div>
              <div class="ans-row">
                <span class="ans-label">获得得分:</span>
                <span class="ans-val">{{ objSelected.score ?? '-' }} / {{ objSelected.fullScore ?? '-' }} 分</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.profile-page {
  padding: 32px 0 72px;
}

.profile-container {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* Toast 提示条 */
.toast-bar {
  background: var(--button);
  color: #fff;
  padding: 12px 20px;
  border-radius: 0;
  font-size: 14px;
  font-weight: 500;
  border: 1px solid #000;
  box-shadow: var(--shadow-md);
  display: flex;
  align-items: center;
  gap: 10px;
}

.toast-icon {
  width: 16px;
  height: 16px;
  color: #4ade80;
  flex-shrink: 0;
}

.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: all 0.25s ease;
}

.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* 1. 个人名片 Hero Card（头像占满左侧，严格直角边线分割） */
.hero-profile-card {
  padding: 0;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.hero-main-layout {
  display: flex;
  align-items: stretch;
  min-height: 180px;
}

/* 方形头像占满左侧 (宽高自适应正方形，右侧直角边线分割) */
.hero-avatar-box {
  width: auto;
  min-width: 180px;
  max-width: 240px;
  aspect-ratio: 1 / 1;
  align-self: stretch;
  flex-shrink: 0;
  border: none;
  border-right: 1px solid var(--line);
  background: #fbfbfb;
  position: relative;
  overflow: hidden;
  transition: opacity 0.15s ease;
}

.hero-avatar-box.is-clickable {
  cursor: pointer;
}

.hero-avatar-box.is-clickable:hover {
  opacity: 0.92;
}

.avatar-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-placeholder-large {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 64px;
  font-weight: 800;
  color: var(--ink-secondary);
  background: #f4f4f5;
}

.avatar-hover-mask {
  position: absolute;
  inset: 0;
  background: rgba(17, 17, 17, 0.72);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  opacity: 0;
  transition: opacity 0.2s ease;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.hero-avatar-box.is-clickable:hover .avatar-hover-mask {
  opacity: 1;
}

.mask-icon {
  width: 22px;
  height: 22px;
}

/* 右侧核心信息栏 */
.hero-info-column {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  padding: 24px 32px;
  min-width: 0;
}

.user-primary-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.user-title-group {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.user-display-name {
  margin: 0;
  font-size: 28px;
  font-weight: 800;
  color: var(--ink);
  letter-spacing: -0.6px;
  line-height: 1.1;
}

/* 身份标签配色规范：
   - 管理员: 紫色 (#7048e8)
   - 教师: 绿色 (#2b8a3e)
   - 学生: 黑色 (#1e1e1e)
*/
.role-badge {
  font-size: 12px;
  font-weight: 700;
  border-radius: 0;
  padding: 3px 10px;
  white-space: nowrap;
  letter-spacing: 0.3px;
  line-height: 1.2;
}

.role-badge.role-admin {
  background: #7048e8;
  color: #ffffff;
  border: 1px solid #5f3dc4;
}

.role-badge.role-teacher {
  background: #2b8a3e;
  color: #ffffff;
  border: 1px solid #237032;
}

.role-badge.role-student {
  background: #1e1e1e;
  color: #ffffff;
  border: 1px solid #111111;
}

.user-id-badge {
  font-size: 12px;
  color: var(--ink-tertiary);
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 2px 8px;
}

.visitor-badge {
  font-size: 11px;
  color: var(--ink-secondary);
  background: #e2e8f0;
  border: 1px solid #cbd5e1;
  padding: 2px 8px;
}

.hero-actions-group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.btn-svg {
  width: 14px;
  height: 14px;
}

/* 元数据标签行 */
.user-meta-pills {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 13px;
}

.meta-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.pill-svg {
  width: 13px;
  height: 13px;
  color: var(--ink-tertiary);
  flex-shrink: 0;
}

.github-pill {
  color: var(--ink);
  text-decoration: none;
  font-weight: 500;
  transition: all 0.15s ease;
}

.github-pill:hover {
  border-color: var(--ink);
  background: #fff;
}

.ext-arrow {
  width: 9px;
  height: 9px;
  stroke-width: 2;
  margin-left: -2px;
}

/* Slogan 个性签名沉浸卡 */
.user-slogan-box {
  margin-top: 4px;
  padding: 12px 18px;
  background: #fafafa;
  border: 1px solid var(--line);
  border-left: 3px solid var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.quote-sign {
  font-size: 20px;
  font-weight: 800;
  color: var(--ink-tertiary);
  line-height: 1;
}

.slogan-text {
  margin: 0;
  font-weight: 500;
  color: var(--ink);
  line-height: 1.5;
  word-break: break-word;
}

/* 通用分区卡片 */
.profile-block-card {
  padding: 24px;
  background: var(--surface);
  border: 1px solid var(--line);
}

.block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.block-title-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.block-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.block-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
}

.block-counter {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.block-sub {
  font-size: 13px;
  color: var(--ink-tertiary);
}

.block-loading {
  padding: 36px 0;
  text-align: center;
  font-size: 14px;
}

.empty-block {
  padding: 40px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--ink-tertiary);
}

.empty-svg {
  width: 36px;
  height: 36px;
  color: var(--ink-tertiary);
}

.empty-text {
  font-size: 14px;
}

/* 考试战绩网格 */
.exam-grid-container {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(460px, 1fr));
  gap: 16px;
}

.exam-battle-card {
  padding: 20px;
  border: 1px solid var(--line);
  background: #fbfbfb;
  display: flex;
  flex-direction: column;
  gap: 14px;
  transition: all 0.15s ease;
}

.exam-battle-card:hover {
  border-color: var(--ink);
  background: #fff;
  box-shadow: var(--shadow-sm);
}

.battle-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.battle-title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.battle-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.exam-mode-pill {
  font-size: 11px;
  font-weight: 600;
}

.mode-acm {
  background: #f0f0f0;
  color: var(--ink);
  border: 1px solid var(--line);
}

.mode-oi {
  background: var(--button);
  color: #fff;
}

.battle-detail-btn {
  flex-shrink: 0;
}

.battle-desc {
  margin: 0;
  font-size: 13px;
  color: var(--ink-secondary);
  line-height: 1.5;
}

.battle-metrics-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.metric-k {
  font-size: 11px;
  color: var(--ink-tertiary);
}

.metric-v {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

.metric-v.score {
  color: var(--ink);
  font-size: 16px;
}

.metric-v.time {
  font-size: 12px;
  font-weight: 500;
  color: var(--ink-secondary);
}

/* 提交模块样式 */
.submissions-filter-box {
  display: flex;
  align-items: center;
}

.status-select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--line);
  background: var(--surface);
  font-family: inherit;
  font-size: 13px;
  color: var(--ink);
  outline: none;
  cursor: pointer;
  border-radius: 0;
}

.status-select:focus {
  border-color: var(--ink);
}

.submissions-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--line);
  padding-bottom: 12px;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: var(--bg);
  border: 1px solid var(--line);
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
  border-radius: 0;
}

.tab-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}

.tab-btn.active {
  background: var(--button);
  color: #fff;
  border-color: var(--button);
}

.tab-count {
  font-size: 12px;
  opacity: 0.85;
}

.table-responsive {
  overflow-x: auto;
}

.timeline-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.timeline-table th {
  text-align: left;
  padding: 10px 14px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-tertiary);
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.timeline-table td {
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
  white-space: nowrap;
}

.timeline-table tbody tr:hover {
  background: #fbfbfb;
}

.sub-id {
  font-size: 13px;
  color: var(--ink-secondary);
}

.q-title-col {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.q-link {
  color: var(--ink);
  font-weight: 500;
  transition: opacity 0.15s;
}

.q-link:hover {
  opacity: 0.7;
  text-decoration: underline;
}

.status-badge {
  display: inline-block;
  min-width: 44px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 700;
  text-align: center;
  color: #fff;
  border-radius: 0;
}

.status-badge.clickable {
  cursor: pointer;
  transition: opacity 0.15s ease;
}

.status-badge.clickable:hover {
  opacity: 0.85;
}

.status-badge.ac {
  background: var(--success);
}
.status-badge.wa {
  background: var(--danger);
}
.status-badge.tle {
  background: #000;
}
.status-badge.mle {
  background: #f57c00;
}
.status-badge.re {
  background: #9c27b0;
}
.status-badge.ce,
.status-badge.se {
  background: #607d8b;
}
.status-badge.run {
  background: #9e9e9e;
}

.obj-type-tag {
  font-size: 11px;
  background: #f0f0f0;
  color: var(--ink-secondary);
  border: 1px solid var(--line);
}

.score-cell,
.time-used,
.mem-used,
.lang-cell {
  font-size: 13px;
}

.date-cell {
  font-size: 13px;
  color: var(--ink-secondary);
}

.ans-preview {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}

.detail-action {
  font-size: 13px;
  color: var(--ink);
  text-decoration: underline;
}

.detail-action:hover {
  opacity: 0.7;
}

.btn-inline {
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
}

.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
  padding-top: 16px;
}

.page-indicator {
  font-size: 13px;
  color: var(--ink-secondary);
}

/* 弹窗通用样式 */
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 17, 17, 0.55);
  backdrop-filter: blur(4px);
}

.modal-card {
  width: 100%;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  box-shadow: var(--shadow-md);
  display: flex;
  flex-direction: column;
}

.code-view-modal {
  max-width: 760px;
  max-height: 85vh;
}

.obj-view-modal {
  max-width: 680px;
  max-height: 85vh;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
}

.code-modal-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.code-modal-id {
  font-size: 14px;
  color: var(--ink-secondary);
  font-weight: 700;
}

.code-modal-title {
  font-size: 15px;
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

.modal-code-body {
  padding: 18px 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}

.code-modal-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 13px;
  color: var(--ink-secondary);
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.copy-action-span {
  margin-left: auto;
}

.copy-btn {
  padding: 4px 10px;
  font-size: 12px;
}

.code-pre {
  margin: 0;
  padding: 16px 18px;
  background: #f6f8fa;
  color: #24292f;
  border: 1px solid var(--line, #e1e4e8);
  border-radius: 0;
  max-height: 50vh;
  overflow: auto;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.code-pre code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.obj-modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}

.obj-content-box {
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 16px;
}

.obj-answer-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
}

.ans-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ans-label {
  color: var(--ink-secondary);
  width: 80px;
}

.ans-val {
  font-weight: 700;
}

.ans-ac {
  color: var(--success);
}

.ans-wa {
  color: var(--danger);
}

@media (max-width: 860px) {
  .hero-main-layout {
    flex-direction: column;
    align-items: stretch;
  }
  .hero-avatar-box {
    width: 100%;
    min-width: unset;
    max-width: unset;
    height: 180px;
    aspect-ratio: auto;
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
  .hero-info-column {
    padding: 20px 16px;
    width: 100%;
    align-items: center;
    text-align: center;
  }
  .user-primary-row {
    flex-direction: column;
    align-items: center;
    gap: 12px;
  }
  .user-title-group {
    justify-content: center;
  }
  .user-meta-pills {
    justify-content: center;
  }
  .exam-grid-container {
    grid-template-columns: 1fr;
  }
  .battle-metrics-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .submissions-tabs {
    flex-wrap: wrap;
  }
}
</style>
