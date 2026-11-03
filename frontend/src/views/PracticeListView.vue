<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { practiceApi } from '@/api/practice'
import Pagination from '@/components/Pagination.vue'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'
import type { PracticeSummary } from '@/types/practice'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { error: toastError, success: toastSuccess } = useToast()

const practices = ref<PracticeSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = 15
const loading = ref(true)

const keyword = ref('')
const currentTab = ref<'ALL' | 'ACTIVE' | 'ENDED'>('ALL')
const scopeTab = ref<'ALL' | 'MINE'>('ALL')

// 右上角口令直达舱
const inputCode = ref('')
const joining = ref(false)
const topCodeInputRef = ref<HTMLInputElement | null>(null)

// 行内口令直通状态（点击未解锁进行中课练直接在行右侧展开）
const inlineUnlockId = ref<number | null>(null)
const inlineCode = ref('')
const inlineJoining = ref(false)

/**
 * 后端仅对「本人已加入」或「本场授课教师 / 管理员」返回非空 accessCode
 */
function canDirectEnter(p: PracticeSummary): boolean {
  return Boolean(p.accessCode)
}

function isManagerPreview(p: PracticeSummary): boolean {
  return !p.myJoined && Boolean(p.accessCode)
}

function formatQuestionDist(p: PracticeSummary): string {
  const obj = p.objectiveCount || 0
  const prac = p.practicalCount || 0
  if (obj > 0 && prac > 0) {
    return `客观 ${obj} · 实操 ${prac}`
  }
  if (obj > 0) {
    return `客观 ${obj} 题`
  }
  if (prac > 0) {
    return `实操 ${prac} 题`
  }
  return `共 ${p.questionCount || 0} 题`
}

async function fetchPractices() {
  loading.value = true
  try {
    const res = await practiceApi.list({
      keyword: keyword.value.trim() || undefined,
      status: currentTab.value === 'ALL' ? undefined : currentTab.value,
      joinedOnly: scopeTab.value === 'MINE' ? true : undefined,
      page: page.value,
      size,
    })
    practices.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    toastError(e instanceof Error ? e.message : '加载随堂课练列表失败')
  } finally {
    loading.value = false
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    fetchPractices()
  }, 260)
})

watch(currentTab, () => {
  page.value = 1
  inlineUnlockId.value = null
  fetchPractices()
})

watch(scopeTab, () => {
  page.value = 1
  inlineUnlockId.value = null
  fetchPractices()
})

watch(page, () => {
  inlineUnlockId.value = null
  fetchPractices()
})

function handleTopCodeInput(e: Event) {
  const target = e.target as HTMLInputElement
  inputCode.value = target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8)
}

async function handleJoinByTopCode() {
  const code = inputCode.value.trim().toUpperCase()
  if (!code) {
    toastError('请输入 6 位课堂口令')
    return
  }
  if (!userStore.isLoggedIn) {
    toastError('请先登录后再进入随堂课练')
    router.push('/login')
    return
  }

  joining.value = true
  try {
    await practiceApi.join(code)
    toastSuccess('口令验证成功，正在进入课练…')
    router.push(`/practice/${code}`)
  } catch (e) {
    toastError(e instanceof Error ? e.message : '口令错误或课练不存在')
  } finally {
    joining.value = false
  }
}

function handleRowClick(p: PracticeSummary) {
  if (canDirectEnter(p) && p.accessCode) {
    router.push(`/practice/${p.accessCode}`)
    return
  }
  if (p.status === 'ENDED') {
    return
  }
  openInlineUnlock(p)
}

function openInlineUnlock(p: PracticeSummary) {
  if (!userStore.isLoggedIn) {
    toastError('请先登录后再输入口令进入课练')
    router.push('/login')
    return
  }
  inlineUnlockId.value = p.id
  inlineCode.value = ''
  nextTick(() => {
    const el = document.getElementById(`inline-code-input-${p.id}`) as HTMLInputElement | null
    el?.focus()
  })
}

function closeInlineUnlock() {
  inlineUnlockId.value = null
  inlineCode.value = ''
}

function handleInlineCodeInput(e: Event) {
  const target = e.target as HTMLInputElement
  inlineCode.value = target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8)
}

async function submitInlineUnlock(p: PracticeSummary) {
  const code = inlineCode.value.trim().toUpperCase()
  if (!code) {
    toastError('请输入 6 位课堂口令')
    return
  }
  inlineJoining.value = true
  try {
    await practiceApi.join(code, p.id)
    toastSuccess(`已解锁「${p.title}」，正在进入…`)
    closeInlineUnlock()
    router.push(`/practice/${code}`)
  } catch (e) {
    toastError(e instanceof Error ? e.message : '口令与当前课练不匹配，请核对教师公布的口令')
  } finally {
    inlineJoining.value = false
  }
}

onMounted(() => {
  const unlockParam = route.query.unlock
  if (unlockParam) {
    inputCode.value = String(unlockParam).toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8)
    nextTick(() => {
      topCodeInputRef.value?.focus()
    })
  }
  fetchPractices()
})
</script>

<template>
  <div class="practice-hall container">
    <!-- 顶部标题 + 右上角紧凑口令直达舱 -->
    <div class="page-head">
      <div class="page-head-left">
        <h1 class="page-title">随堂课练</h1>
        <p class="muted head-sub">
          6 位课堂口令凭码上机 · 客观与实操混排评测 · 实时榜单演进
        </p>
      </div>

      <div class="head-code-dock">
        <span class="dock-label mono">KEY 口令入场</span>
        <input
          ref="topCodeInputRef"
          :value="inputCode"
          type="text"
          class="dock-input mono"
          placeholder="如 KE98A2"
          maxlength="8"
          @input="handleTopCodeInput"
          @keydown.enter="handleJoinByTopCode"
        />
        <button
          type="button"
          class="btn btn-primary dock-btn"
          :disabled="joining || !inputCode.trim()"
          @click="handleJoinByTopCode"
        >
          {{ joining ? '验证中…' : '进入课练 →' }}
        </button>
      </div>
    </div>

    <!-- 单行综合工具栏（z-index: 2，配合下方卡片掉落动效） -->
    <div class="toolbar card">
      <div class="toolbar-left">
        <div class="search-wrap">
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor">
            <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z" />
          </svg>
          <input
            v-model="keyword"
            class="input search-input"
            type="search"
            placeholder="搜索课练名称、说明…"
          />
        </div>

        <div class="filter-tabs">
          <button
            type="button"
            class="tab-btn"
            :class="{ active: currentTab === 'ALL' }"
            @click="currentTab = 'ALL'"
          >
            全部课练
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: currentTab === 'ACTIVE' }"
            @click="currentTab = 'ACTIVE'"
          >
            进行中
          </button>
          <button
            type="button"
            class="tab-btn"
            :class="{ active: currentTab === 'ENDED' }"
            @click="currentTab = 'ENDED'"
          >
            已结束
          </button>
        </div>
      </div>

      <div class="toolbar-right">
        <div v-if="userStore.isLoggedIn" class="status-tabs">
          <button
            type="button"
            class="status-tab"
            :class="{ active: scopeTab === 'ALL' }"
            @click="scopeTab = 'ALL'"
          >
            全部
          </button>
          <button
            type="button"
            class="status-tab"
            :class="{ active: scopeTab === 'MINE' }"
            @click="scopeTab = 'MINE'"
          >
            已参与
          </button>
        </div>

        <button
          type="button"
          class="btn btn-ghost btn-sm refresh-btn"
          :disabled="loading"
          @click="fetchPractices"
        >
          {{ loading ? '刷新中…' : '↻ 刷新' }}
        </button>
      </div>
    </div>

    <!-- 全站统一加载与空状态 -->
    <div v-if="loading && practices.length === 0" class="loading-box card muted">
      <span class="loading-spin"></span>
      <span>正在加载随堂课练…</span>
    </div>

    <div v-else-if="practices.length === 0" class="empty-box card">
      <p class="empty-title">暂无匹配的随堂课练</p>
      <p class="muted empty-desc">可直接在右上角输入教师公布的 6 位课堂口令入场</p>
    </div>

    <!-- 题集风单行紧凑卡片列表（1:1 继承题集/题库 cardDropIn 掉落动画） -->
    <div
      v-else
      :key="`${page}-${currentTab}-${scopeTab}`"
      class="question-list"
    >
      <article
        v-for="(p, index) in practices"
        :key="p.id"
        class="question-item card"
        :class="{ 'is-ended-unjoined': p.status === 'ENDED' && !canDirectEnter(p) }"
        :style="{ '--drop-index': index }"
        @click="handleRowClick(p)"
      >
        <!-- 左侧：状态指示图标 + 序号 + 标题与工业风标签组 -->
        <div class="q-main-layout">
          <div class="q-status-wrap">
            <!-- 已交卷或已参练结束：与题库一致的绿圆勾 -->
            <span
              v-if="p.mySubmitted || (p.status === 'ENDED' && p.myJoined)"
              class="status-icon solved"
              title="已参练完成"
            >
              <svg viewBox="0 0 16 16" fill="currentColor">
                <path d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z" />
              </svg>
            </span>
            <!-- 进行中且已解锁（或教师免密直达）：绿色脉冲点 -->
            <span
              v-else-if="p.status === 'ACTIVE' && canDirectEnter(p)"
              class="status-icon ongoing-dot"
              :title="isManagerPreview(p) ? '进行中 · 教师免密视角' : '进行中 · 已解锁'"
            ></span>
            <!-- 进行中但未解锁：锁图标 -->
            <span
              v-else-if="p.status === 'ACTIVE' && !canDirectEnter(p)"
              class="status-icon locked-key"
              title="需输入 6 位课堂口令解锁"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="11" width="18" height="11" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
            </span>
            <!-- 已结束未参练：空心灰环 -->
            <span v-else class="status-icon unsolved" :title="isManagerPreview(p) ? '已结束 · 教师视角' : '已结束 · 未参练'">
              <span class="unsolved-ring"></span>
            </span>
          </div>

          <span class="q-number mono">#{{ String((page - 1) * size + index + 1).padStart(2, '0') }}</span>

          <div class="q-content">
            <div class="q-title-line">
              <h3 class="q-title" :class="{ 'muted-title': p.status === 'ENDED' && !canDirectEnter(p) }">
                {{ p.title }}
              </h3>
              <span v-if="p.description" class="q-desc-inline">— {{ p.description }}</span>
            </div>

            <div class="q-tags-row">
              <span class="meta-tag type mono">{{ formatQuestionDist(p) }}</span>
              <span class="meta-tag limits mono">
                {{ p.durationMinutes > 0 ? `限时 ${p.durationMinutes} 分钟` : '不限时' }}
              </span>
              <span class="meta-tag limits mono">教师: {{ p.teacherName }}</span>
              <span class="meta-tag limits mono">{{ p.memberCount }} 人参练</span>
              <span v-if="p.myJoined" class="meta-tag progress mono">
                {{ p.mySubmitted ? '已交卷' : '已解锁' }} · {{ p.myScore ?? 0 }} 分
              </span>
              <span v-else-if="isManagerPreview(p)" class="meta-tag manager mono">
                管理视角 · #{{ p.accessCode }}
              </span>
            </div>
          </div>
        </div>

        <!-- 右侧：状态标识 + 动作入口 -->
        <div class="q-right-layout" @click.stop>
          <!-- 场景 A：授课教师 / 管理员免密视角 -->
          <template v-if="isManagerPreview(p) && p.accessCode">
            <span
              class="difficulty-pill"
              :class="p.status === 'ACTIVE' ? 'pill-easy' : 'pill-ended'"
            >
              {{ p.status === 'ACTIVE' ? '进行中' : '已结束' }}
            </span>
            <span class="action-cta" @click="router.push(`/practice/${p.accessCode}`)">
              <span class="cta-text">进入视角</span>
              <svg class="cta-arrow" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M6 3l5 5-5 5" />
              </svg>
            </span>
          </template>

          <!-- 场景 1：进行中 · 学生已解锁 -->
          <template v-else-if="p.status === 'ACTIVE' && p.myJoined && p.accessCode">
            <span class="difficulty-pill pill-easy">进行中</span>
            <span class="action-cta" @click="router.push(`/practice/${p.accessCode}`)">
              <span class="cta-text">进入课练</span>
              <svg class="cta-arrow" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M6 3l5 5-5 5" />
              </svg>
            </span>
          </template>

          <!-- 场景 2：进行中 · 未解锁（支持行内直接展开口令输入条，绑定 p.id 防串房） -->
          <template v-else-if="p.status === 'ACTIVE' && !p.myJoined">
            <span class="difficulty-pill pill-easy">进行中</span>

            <div v-if="inlineUnlockId === p.id" class="inline-code-box">
              <input
                :id="`inline-code-input-${p.id}`"
                :value="inlineCode"
                type="text"
                class="inline-code-input mono"
                placeholder="6位口令"
                maxlength="8"
                @input="handleInlineCodeInput"
                @keydown.enter="submitInlineUnlock(p)"
                @keydown.esc="closeInlineUnlock"
              />
              <button
                type="button"
                class="btn btn-primary btn-sm"
                :disabled="inlineJoining || !inlineCode.trim()"
                @click="submitInlineUnlock(p)"
              >
                {{ inlineJoining ? '…' : '进房 →' }}
              </button>
              <button
                type="button"
                class="btn btn-ghost btn-sm"
                title="取消"
                @click="closeInlineUnlock"
              >
                ×
              </button>
            </div>

            <span v-else class="action-cta" @click="openInlineUnlock(p)">
              <span class="cta-text">输入口令</span>
              <svg class="cta-arrow" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M6 3l5 5-5 5" />
              </svg>
            </span>
          </template>

          <!-- 场景 3：已结束 · 已参练 -->
          <template v-else-if="p.status === 'ENDED' && p.myJoined && p.accessCode">
            <span class="difficulty-pill pill-ended">已结束</span>
            <span class="action-cta" @click="router.push(`/practice/${p.accessCode}`)">
              <span class="cta-text">查看回顾</span>
              <svg class="cta-arrow" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M6 3l5 5-5 5" />
              </svg>
            </span>
          </template>

          <!-- 场景 4：已结束 · 未参练（仅保留单个徽章） -->
          <template v-else>
            <span class="difficulty-pill pill-ended">已结束</span>
          </template>
        </div>
      </article>
    </div>

    <Pagination
      v-if="total > size"
      v-model:page="page"
      :size="size"
      :total="total"
    />
  </div>
</template>

<style scoped>
.practice-hall {
  padding-top: 32px;
  padding-bottom: 72px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

/* 顶部标题 + 右上角口令直达舱 */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.page-head-left {
  flex: 1;
  min-width: 260px;
}
.page-title {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.head-sub {
  margin: 0;
  font-size: 14px;
}

.head-code-dock {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
}
.dock-label {
  padding: 0 10px 0 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
  border-right: 1px solid var(--line);
  white-space: nowrap;
}
.dock-input {
  width: 148px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--ink);
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: 1.2px;
  text-transform: uppercase;
  text-align: center;
  border-radius: 0;
  outline: none;
}
.dock-input:focus {
  border-color: var(--ink);
}
.dock-btn {
  height: 34px;
  padding: 0 16px;
  font-size: 13px;
  font-weight: 600;
  border-radius: 0;
  white-space: nowrap;
}

/* 顶部综合工具栏（z-index: 2 与题集/题库完全一致） */
.toolbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  margin-bottom: 20px;
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  flex-wrap: wrap;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  flex: 1;
}
.search-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 240px;
}
.search-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: var(--ink-secondary);
  pointer-events: none;
}
.search-input {
  width: 100%;
  height: 34px;
  padding-left: 32px;
  font-size: 13.5px;
  border-radius: 0;
  background: var(--bg);
}
.filter-tabs {
  display: flex;
  gap: 6px;
}
.tab-btn {
  padding: 5px 12px;
  font-size: 13px;
  font-weight: 500;
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}
.tab-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}
.tab-btn.active {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
  font-weight: 600;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.status-tabs {
  display: inline-flex;
  border: 1px solid var(--line);
  background: var(--bg);
  padding: 2px;
}
.status-tab {
  padding: 4px 10px;
  font-size: 12px;
  border: none;
  background: transparent;
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.12s ease;
}
.status-tab.active {
  background: var(--surface);
  color: var(--ink);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}
.refresh-btn {
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
  border-radius: 0;
}

/* 题目/题集/课练卡片列表（1:1 继承题集与题库的 cardDropIn 按序弹簧掉落动画） */
.question-list {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.question-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
  text-decoration: none;
  color: inherit;
  cursor: pointer;
  transition: all 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  animation: cardDropIn 0.44s cubic-bezier(0.25, 1.15, 0.5, 1) backwards;
  animation-delay: calc(var(--drop-index, 0) * 45ms);
  will-change: transform, opacity;
}
.question-item:hover {
  border-color: var(--ink);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.06);
  transform: translateY(-2px) !important;
}
.question-item.is-ended-unjoined {
  cursor: default;
}
.question-item.is-ended-unjoined:hover {
  border-color: var(--line);
  box-shadow: var(--shadow-sm);
  transform: none !important;
}

@keyframes cardDropIn {
  0% {
    opacity: 0;
    transform: translateY(-38px) scale(0.985);
  }
  60% {
    opacity: 1;
    transform: translateY(3px) scale(1.002);
  }
  82% {
    opacity: 1;
    transform: translateY(-1.5px) scale(0.999);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .question-item {
    animation: none !important;
  }
}

.q-main-layout {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  flex: 1;
}
.q-status-wrap {
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.status-icon.solved {
  color: #2b8a3e;
  width: 20px;
  height: 20px;
  display: inline-flex;
}
.status-icon.ongoing-dot {
  width: 10px;
  height: 10px;
  background: #2b8a3e;
  display: inline-block;
  box-shadow: 0 0 0 3px rgba(43, 138, 62, 0.18);
}
.status-icon.locked-key {
  color: var(--ink-secondary);
  width: 16px;
  height: 16px;
  display: inline-flex;
}
.status-icon.unsolved {
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.unsolved-ring {
  width: 12px;
  height: 12px;
  border: 1.5px solid #d0d0d0;
  border-radius: 50%;
}

.q-number {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-secondary);
  min-width: 36px;
  flex-shrink: 0;
}
.q-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  flex: 1;
}
.q-title-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.q-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.q-title.muted-title {
  color: var(--ink-secondary);
}
.q-desc-inline {
  font-size: 12.5px;
  color: var(--ink-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.q-tags-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.meta-tag {
  font-size: 11.5px;
  padding: 1px 7px;
  line-height: 1.4;
  border-radius: 0;
}
.meta-tag.limits {
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}
.meta-tag.type {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  color: #3f3f46;
  font-weight: 600;
}
.meta-tag.progress {
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
  color: #059669;
  font-weight: 600;
}
.meta-tag.manager {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1d4ed8;
  font-weight: 600;
}

/* 右侧状态徽标与操作入口（对齐题集 .difficulty-pill 与 .action-cta） */
.q-right-layout {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}
.difficulty-pill {
  min-width: 58px;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  border-radius: 0;
  letter-spacing: 0.3px;
  white-space: nowrap;
}
.pill-easy {
  background: #2b8a3e;
  color: #ffffff;
  border: 1px solid #237032;
}
.pill-ended {
  background: #e9ecef;
  color: var(--ink-secondary);
  border: 1px solid var(--line);
}

.action-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}
.question-item:hover .action-cta {
  color: var(--ink);
}
.question-item:hover .cta-arrow {
  transform: translateX(3px);
}
.cta-arrow {
  width: 14px;
  height: 14px;
  transition: transform 0.15s ease;
}

/* 行内口令展开条 */
.inline-code-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px;
  background: var(--bg);
  border: 1px solid var(--ink);
}
.inline-code-input {
  width: 112px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--line);
  background: #ffffff;
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 1px;
  text-transform: uppercase;
  text-align: center;
  outline: none;
}
.inline-code-input:focus {
  border-color: var(--ink);
}

/* 加载与空状态 */
.loading-box,
.empty-box {
  padding: 48px 24px;
  text-align: center;
  border-radius: 0;
}
.empty-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
}
.empty-desc {
  margin: 0;
  font-size: 13px;
}

@media (max-width: 860px) {
  .page-head {
    flex-direction: column;
    align-items: stretch;
  }
  .head-code-dock {
    width: 100%;
  }
  .dock-input {
    flex: 1;
  }
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .search-wrap {
    width: 100%;
  }
  .question-item {
    flex-direction: column;
    align-items: stretch;
  }
  .q-right-layout {
    justify-content: flex-end;
    padding-top: 8px;
    border-top: 1px dashed var(--line);
  }
}
</style>
