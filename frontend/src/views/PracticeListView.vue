<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { practiceApi } from '@/api/practice'
import { useUserStore } from '@/stores/user'
import { useToast } from '@/composables/useToast'
import type { PracticeSummary } from '@/types/practice'

const router = useRouter()
const userStore = useUserStore()
const { error: toastError, success: toastSuccess } = useToast()

const practices = ref<PracticeSummary[]>([])
const loading = ref(true)
const inputCode = ref('')
const joining = ref(false)
const currentTab = ref<'ALL' | 'ACTIVE' | 'ENDED'>('ALL')

// 口令验证弹窗状态
const targetPractice = ref<PracticeSummary | null>(null)
const modalCode = ref('')
const modalJoining = ref(false)
const modalError = ref('')
const modalInputRef = ref<HTMLInputElement | null>(null)

const isTeacherOrAdmin = computed(() =>
  userStore.hasRole('TEACHER') || userStore.hasRole('ADMIN')
)

const filteredPractices = computed(() => {
  if (currentTab.value === 'ACTIVE') {
    return practices.value.filter((p) => p.status === 'ACTIVE')
  }
  if (currentTab.value === 'ENDED') {
    return practices.value.filter((p) => p.status === 'ENDED')
  }
  return practices.value
})

async function fetchPractices() {
  loading.value = true
  try {
    const res = await practiceApi.list({ page: 1, size: 50 })
    practices.value = res.list
  } catch (e) {
    toastError(e instanceof Error ? e.message : '加载课练列表失败')
  } finally {
    loading.value = false
  }
}

// 顶部快捷输入口令加入
async function handleJoinByCode() {
  const code = inputCode.value.trim().toUpperCase()
  if (!code) {
    toastError('请输入 6 位课练口令')
    return
  }
  if (!userStore.isLoggedIn) {
    toastError('请先登录后再加入课练')
    router.push('/login')
    return
  }

  joining.value = true
  try {
    await practiceApi.join(code)
    toastSuccess('加入课练成功！正在进入房间…')
    router.push(`/practice/${code}`)
  } catch (e) {
    toastError(e instanceof Error ? e.message : '口令错误或课练不存在')
  } finally {
    joining.value = false
  }
}

function handleInputCode(e: Event) {
  const target = e.target as HTMLInputElement
  inputCode.value = target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8)
}

// 点击课练卡片：已加入则直接进入，未加入且进行中则打开专属口令输入弹窗
function openJoinModal(p: PracticeSummary) {
  if (p.myJoined && p.accessCode) {
    router.push(`/practice/${p.accessCode}`)
    return
  }
  if (p.status === 'ENDED') {
    toastError('该课练已截止收卷，无法加入')
    return
  }
  if (!userStore.isLoggedIn) {
    toastError('请先登录后再输入口令进入课练')
    router.push('/login')
    return
  }
  targetPractice.value = p
  modalCode.value = ''
  modalError.value = ''
  nextTick(() => {
    modalInputRef.value?.focus()
  })
}

function closeJoinModal() {
  targetPractice.value = null
  modalCode.value = ''
  modalError.value = ''
}

async function handleModalJoin() {
  const code = modalCode.value.trim().toUpperCase()
  if (!code) {
    modalError.value = '请输入 6 位课练口令'
    return
  }
  modalJoining.value = true
  modalError.value = ''
  try {
    await practiceApi.join(code)
    toastSuccess(`口令验证成功！正在进入「${targetPractice.value?.title}」…`)
    const targetCode = code
    closeJoinModal()
    router.push(`/practice/${targetCode}`)
  } catch (e) {
    modalError.value = e instanceof Error ? e.message : '口令错误，请核对教师公布的房间口令'
  } finally {
    modalJoining.value = false
  }
}

onMounted(() => {
  fetchPractices()
})
</script>

<template>
  <div class="practice-list-page container">
    <!-- 头部说明 -->
    <header class="page-head">
      <div class="head-main">
        <h1 class="page-title">随堂课练</h1>
        <p class="muted">免建班免排期 · 6 位口令凭码上机 · 实时评测对拍 · 教师大屏投屏</p>
      </div>
      <div v-if="isTeacherOrAdmin" class="head-extra">
        <RouterLink to="/admin/practices" class="btn btn-ghost btn-sm teacher-link">
          <span>教师控制台：管理课练 →</span>
        </RouterLink>
      </div>
    </header>

    <!-- 6 位口令快速加入横幅 -->
    <div class="join-banner card">
      <div class="join-info">
        <div class="join-icon-wrap">
          <svg class="key-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 2l-2 2m-1.5 1.5L16 7l-1.5-1.5-3 3 1.5 1.5L9 13H5v4h4v-2l2-2 1.5 1.5 3-3-1.5-1.5 1.5-1.5 2-2 3.5 3.5 1.5-1.5z" />
          </svg>
        </div>
        <div class="join-text">
          <h2 class="join-title">输入 6 位课堂口令即刻入场</h2>
          <p class="muted join-desc">老师在黑板或投影仪上公布的房间口令，学生输入后直接同步开练</p>
        </div>
      </div>
      <div class="join-action">
        <div class="join-input-group">
          <input
            :value="inputCode"
            type="text"
            class="input code-input mono"
            placeholder="如 KE98A2"
            maxlength="8"
            @input="handleInputCode"
            @keydown.enter="handleJoinByCode"
          />
          <button
            class="btn btn-primary btn-join"
            :disabled="joining || !inputCode.trim()"
            @click="handleJoinByCode"
          >
            {{ joining ? '加入中…' : '进入课练 →' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 筛选工具栏 -->
    <div class="toolbar card">
      <div class="status-tabs">
        <button
          class="tab"
          :class="{ active: currentTab === 'ALL' }"
          @click="currentTab = 'ALL'"
        >
          全部课练 ({{ practices.length }})
        </button>
        <button
          class="tab"
          :class="{ active: currentTab === 'ACTIVE' }"
          @click="currentTab = 'ACTIVE'"
        >
          进行中 ({{ practices.filter((p) => p.status === 'ACTIVE').length }})
        </button>
        <button
          class="tab"
          :class="{ active: currentTab === 'ENDED' }"
          @click="currentTab = 'ENDED'"
        >
          已结束 ({{ practices.filter((p) => p.status === 'ENDED').length }})
        </button>
      </div>

      <button
        class="btn btn-ghost btn-sm btn-refresh"
        :disabled="loading"
        @click="fetchPractices"
      >
        {{ loading ? '刷新中…' : '刷新状态 ↻' }}
      </button>
    </div>

    <!-- 状态反馈 -->
    <div v-if="loading && practices.length === 0" class="state-card card">
      <div class="loading-spinner"></div><p class="muted">正在加载随堂课练活动…</p>
    </div>

    <div v-else-if="filteredPractices.length === 0" class="empty">
      当前暂无可参与的随堂课练
    </div>

    <!-- 课练流式卡片列表 -->
    <div v-else class="practice-list">
      <article
        v-for="p in filteredPractices"
        :key="p.id"
        class="practice-card card"
      >
        <div class="practice-main">
          <div class="practice-title-row">
            <h2
              class="practice-title"
              :class="{ 'is-ended': p.status === 'ENDED' }"
              @click="openJoinModal(p)"
            >
              <!-- 课练访问凭证状态标识：已解锁 vs 需口令（保护课堂私密性，大厅绝不明文暴露 6 位口令） -->
              <span v-if="p.myJoined" class="code-unlocked-pill" title="已输入口令解锁，点击可直接进入">
                <svg class="inline-lock" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                  <rect x="3" y="11" width="18" height="11" />
                  <path d="M7 11V7a5 5 0 0 1 9.9-1" />
                </svg>
                <span>已解锁</span>
              </span>
              <span v-else class="code-lock-pill" title="该课练受课堂口令保护，需输入口令进入">
                <svg class="inline-lock" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                  <rect x="3" y="11" width="18" height="11" />
                  <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                </svg>
                <span>需口令</span>
              </span>

              <span class="practice-name">{{ p.title }}</span>
            </h2>

            <div class="practice-badges">
              <span
                class="badge status-badge"
                :class="p.status === 'ACTIVE' ? 'status-ongoing' : 'status-ended'"
              >
                <span v-if="p.status === 'ACTIVE'" class="pulse-dot"></span>
                {{ p.status === 'ACTIVE' ? '进行中' : '已归档' }}
              </span>
              <span v-if="p.myJoined" class="badge badge-joined">
                已参练 · {{ p.myScore ?? 0 }} 分
              </span>
              <span v-if="p.mySubmitted" class="badge badge-submitted">
                已交卷
              </span>
            </div>
          </div>

          <p v-if="p.description" class="practice-desc">
            {{ p.description }}
          </p>

          <!-- 参数排盘 -->
          <div class="practice-meta-row">
            <div class="meta-item">
              <span class="meta-k">发起教师</span>
              <span class="meta-v">{{ p.teacherName }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">限时时长</span>
              <span class="meta-v mono">{{ p.durationMinutes > 0 ? `${p.durationMinutes} 分钟` : '不限时' }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">试题构成</span>
              <span class="meta-v">
                {{ p.objectiveCount > 0 ? `客观题 ${p.objectiveCount} 题 · ` : '' }}实操 {{ p.practicalCount }} 题
              </span>
            </div>
            <div class="meta-item">
              <span class="meta-k">参练人数</span>
              <span class="meta-v mono">{{ p.memberCount }} 人</span>
            </div>
          </div>
        </div>

        <div class="practice-action">
          <button
            v-if="isTeacherOrAdmin && p.accessCode && p.status === 'ACTIVE'"
            class="btn btn-ghost btn-sm btn-projector"
            type="button"
            title="查看课练实时数据看板"
            @click="router.push(`/practice/${p.accessCode}/monitor`)"
          >
            课练数据 ↗
          </button>

          <!-- 已结束课练：已加入用户可进入复盘回顾，未加入者不可再加入 -->
          <template v-if="p.status === 'ENDED'">
            <button
              v-if="p.myJoined && p.accessCode"
              class="btn btn-secondary btn-sm btn-enter"
              type="button"
              @click="router.push(`/practice/${p.accessCode}`)"
            >
              <span>查看回顾</span>
              <svg class="cta-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </button>
            <button
              v-else
              class="btn btn-ghost btn-sm btn-ended"
              disabled
              type="button"
            >
              已结束
            </button>
          </template>

          <!-- 进行中课练 -->
          <template v-else>
            <!-- 已加入用户可直接进入，未加入用户需点按弹出口令验证框 -->
            <button
              v-if="p.myJoined && p.accessCode"
              class="btn btn-primary btn-sm btn-enter"
              type="button"
              @click="router.push(`/practice/${p.accessCode}`)"
            >
              <span>进入课练</span>
              <svg class="cta-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </button>
            <button
              v-else
              class="btn btn-secondary btn-sm btn-key-enter"
              type="button"
              @click="openJoinModal(p)"
            >
              <svg class="cta-lock" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="11" width="18" height="11" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
              <span>输入口令进入</span>
            </button>
          </template>
        </div>
      </article>
    </div>

    <!-- 专属课练口令输入验证弹窗 -->
    <Teleport to="body">
      <div v-if="targetPractice" class="modal-backdrop" @click.self="closeJoinModal">
        <div class="modal card">
          <div class="modal-header">
            <div class="modal-lock-badge">
              <svg class="lock-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="11" width="18" height="11" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
            </div>
            <div class="modal-header-text">
              <h2 class="modal-title">输入课练口令</h2>
              <p class="modal-subtitle muted">进入「{{ targetPractice.title }}」</p>
            </div>
          </div>

          <div class="modal-body">
            <label class="modal-label">6 位课堂口令（老师在黑板或投屏公布）</label>
            <input
              ref="modalInputRef"
              v-model="modalCode"
              type="text"
              class="input modal-code-input mono"
              placeholder="如 KE98A2"
              maxlength="8"
              @keydown.enter="handleModalJoin"
            />
            <p v-if="modalError" class="modal-error">{{ modalError }}</p>
          </div>

          <div class="modal-footer">
            <button class="btn btn-ghost btn-sm" type="button" @click="closeJoinModal">
              取消
            </button>
            <button
              class="btn btn-primary btn-sm btn-verify"
              type="button"
              :disabled="modalJoining || !modalCode.trim()"
              @click="handleModalJoin"
            >
              {{ modalJoining ? '验证中…' : '验证并进入 →' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.practice-list-page {
  padding-top: 40px;
  padding-bottom: 64px;
}

/* 头部 */
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  gap: 16px;
  flex-wrap: wrap;
}

.page-title {
  margin: 0 0 4px;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.5px;
  color: var(--ink);
}

.teacher-link {
  font-weight: 600;
}

/* 6 位口令加入横幅卡片 */
.join-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 24px;
  margin-bottom: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  flex-wrap: wrap;
}

.join-info {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.join-icon-wrap {
  width: 44px;
  height: 44px;
  background: var(--bg);
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink);
  flex-shrink: 0;
}

.key-icon {
  width: 20px;
  height: 20px;
}

.join-title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.join-desc {
  margin: 0;
  font-size: 13px;
}

.join-action {
  flex-shrink: 0;
}

.join-input-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.code-input {
  width: 150px;
  height: 40px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  text-align: center;
}

.btn-join {
  height: 40px;
  padding: 0 20px;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}

/* 检索与状态工具条 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  margin-bottom: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  flex-wrap: wrap;
}

.status-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.tab {
  padding: 6px 16px;
  border-radius: var(--radius);
  font-size: 13.5px;
  color: var(--ink-secondary);
  border: 1px solid var(--line);
  background: var(--surface);
  cursor: pointer;
  transition: all 0.15s ease;
}

.tab.active {
  background: var(--button);
  border-color: var(--button);
  color: #fff;
  font-weight: 600;
}

.btn-refresh {
  font-size: 12.5px;
}

.loading {
  padding: 48px 0;
  text-align: center;
}

.empty {
  padding: 48px 0;
  text-align: center;
  color: var(--ink-tertiary);
  font-size: 14px;
}

/* 课练卡片流式列表（与考试大厅完全对齐） */
.practice-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.practice-card {
  padding: 22px 26px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.practice-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--ink);
}

.practice-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.practice-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.practice-title {
  margin: 0;
  font-size: 17.5px;
  font-weight: 700;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.practice-name {
  transition: color 0.15s ease;
}

.practice-title:hover .practice-name {
  color: var(--ink);
  text-decoration: underline;
}

.code-unlocked-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 11.5px;
  font-weight: 600;
  background: #ebfbee;
  color: #2b8a3e;
  border: 1px solid rgba(43, 138, 62, 0.3);
  border-radius: var(--radius);
  flex-shrink: 0;
}

.code-lock-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 11.5px;
  font-weight: 600;
  background: var(--bg);
  color: var(--ink-tertiary);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  flex-shrink: 0;
}

.inline-lock {
  width: 11px;
  height: 11px;
}

.practice-badges {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-badge {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.status-ongoing {
  background: #e8f5e9;
  color: #2e7d32;
  border: 1px solid rgba(46, 125, 50, 0.3);
}

.status-ended {
  background: #f0f0f0;
  color: var(--ink-tertiary);
  border: 1px solid var(--line);
}

.pulse-dot {
  width: 6px;
  height: 6px;
  background: #2e7d32;
  border-radius: 0;
  animation: pulse 1s infinite alternate;
}

@keyframes pulse {
  from {
    opacity: 0.3;
    transform: scale(0.8);
  }
  to {
    opacity: 1;
    transform: scale(1.2);
  }
}

.badge-joined {
  background: #ebfbee;
  color: #2b8a3e;
  border: 1px solid #b2f2bb;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
}

.badge-submitted {
  background: #f1f3f5;
  color: var(--ink-secondary);
  border: 1px solid #ced4da;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
}

.practice-desc {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-secondary);
  line-height: 1.5;
  max-width: 780px;
}

.practice-meta-row {
  display: flex;
  align-items: center;
  gap: 22px;
  flex-wrap: wrap;
  padding-top: 2px;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.meta-k {
  color: var(--ink-tertiary);
  font-size: 12px;
}

.meta-v {
  color: var(--ink-secondary);
  font-weight: 500;
}

/* 操作按钮 */
.practice-action {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-enter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  font-weight: 600;
  font-size: 13.5px;
}

.btn-key-enter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  font-weight: 600;
  font-size: 13px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink);
  transition: all 0.15s ease;
}

.btn-key-enter:hover {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}

.cta-arrow {
  width: 13px;
  height: 13px;
}

.cta-lock {
  width: 13px;
  height: 13px;
}

.btn-projector {
  padding: 9px 14px;
  font-size: 13px;
  font-weight: 600;
}

/* 验证口令弹窗样式 */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal {
  width: 100%;
  max-width: 440px;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-md);
  padding: 24px;
}

.modal-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}

.modal-lock-badge {
  width: 42px;
  height: 42px;
  background: var(--bg);
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink);
  flex-shrink: 0;
}

.lock-icon {
  width: 20px;
  height: 20px;
}

.modal-header-text {
  min-width: 0;
}

.modal-title {
  margin: 0 0 2px;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
}

.modal-subtitle {
  margin: 0;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.modal-body {
  margin-bottom: 22px;
}

.modal-label {
  display: block;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-secondary);
  margin-bottom: 8px;
}

.modal-code-input {
  height: 46px;
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 3px;
  text-transform: uppercase;
  text-align: center;
}

.modal-error {
  color: var(--danger);
  font-size: 12.5px;
  margin: 8px 0 0;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.btn-verify {
  padding: 8px 20px;
  font-weight: 600;
}
</style>
