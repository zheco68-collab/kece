<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  problemSetApi,
  type ProblemSetDetail,
  type ProblemSetItemVO,
} from '@/api/problemSet'
import { useUserStore } from '@/stores/user'
import { useToast } from '@/composables/useToast'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const toast = useToast()

const setId = computed(() => Number(route.params.id))
const detail = ref<ProblemSetDetail | null>(null)
const loading = ref(true)
const error = ref('')
const collapsedSections = ref<Set<number>>(new Set())
const statusFilter = ref<'ALL' | 'UNSOLVED' | 'SOLVED'>('ALL')

const categoryLabels: Record<string, string> = {
  ALGO: '算法编程',
  DB: '数据库 SQL',
  OBJECTIVE: '客观题',
  MIXED: '全栈综合',
  COURSE: '课程同步',
}

const difficultyLabels: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
  SELECT: 'SELECT 查询',
  DML: 'DML 变更',
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  FILL: '填空题',
}

const typeLabels: Record<string, string> = {
  ALGO: '算法编程',
  DB: 'SQL 数据库',
  OBJECTIVE: '客观题',
}

// 题集编排修改仅在管理后台中开放

const progressPercent = computed(() => {
  if (!detail.value || !detail.value.itemCount) return 0
  return Math.min(
    100,
    Math.round(((detail.value.solvedCount || 0) / detail.value.itemCount) * 100),
  )
})

async function loadDetail() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await problemSetApi.detail(setId.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载题集详情失败'
  } finally {
    loading.value = false
  }
}

async function toggleFav() {
  if (!detail.value) return
  if (!userStore.isLoggedIn) {
    toast.info('请先登录后再收藏题集')
    router.push('/login')
    return
  }
  try {
    const res = await problemSetApi.toggleFavorite(detail.value.id)
    detail.value.favorited = res.favorited
    detail.value.participantCount = res.participantCount
    toast.success(res.favorited ? '已收藏该题集' : '已取消收藏')
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败')
  }
}

function toggleSection(secId: number) {
  const next = new Set(collapsedSections.value)
  if (next.has(secId)) {
    next.delete(secId)
  } else {
    next.add(secId)
  }
  collapsedSections.value = next
}

function filteredSectionItems(items: ProblemSetItemVO[]): ProblemSetItemVO[] {
  if (statusFilter.value === 'SOLVED') {
    return items.filter((i) => i.solved)
  }
  if (statusFilter.value === 'UNSOLVED') {
    return items.filter((i) => !i.solved)
  }
  return items
}

function openItem(item: ProblemSetItemVO) {
  if (!item.sourceAlive) {
    toast.info('该原题已归档或下架，暂不可进入作答')
    return
  }
  if (userStore.isLoggedIn && detail.value) {
    problemSetApi.touch(detail.value.id, item.id).catch(() => {})
  }
  const query = { setId: String(setId.value), itemId: String(item.id) }
  const t = (item.questionType || 'ALGO').toUpperCase()
  if (t === 'DB') {
    router.push({ path: `/database/${item.questionId}`, query })
  } else if (t === 'OBJECTIVE') {
    router.push({ path: `/objective/${item.questionId}`, query })
  } else {
    router.push({ path: `/questions/${item.questionId}`, query })
  }
}

onMounted(loadDetail)
</script>

<template>
  <div class="set-detail-page container">
    <!-- 加载与异常状态 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在加载题集章节与同步 AC 进度…</p>
    </div>

    <div v-else-if="error" class="state-card card">
      <div class="error-title">加载失败</div>
      <p class="error-msg">{{ error }}</p>
      <button class="btn btn-ghost" @click="router.push('/problem-sets')">
        返回题集列表
      </button>
    </div>

    <template v-else-if="detail">
      <!-- 顶部题集总览仪表盘 -->
      <header class="set-hero card">
        <div class="hero-top-bar">
          <button class="btn-back" @click="router.push('/problem-sets')">
            ← 返回题集大厅
          </button>

          <div class="top-right-actions">

            <button
              type="button"
              class="btn-fav"
              :class="{ 'is-fav': detail.favorited }"
              @click="toggleFav"
            >
              {{ detail.favorited ? '★ 已收藏' : '☆ 收藏题集' }}
              <span class="fav-count mono">({{ detail.participantCount }})</span>
            </button>
          </div>
        </div>

        <div class="hero-body">
          <div class="hero-main">
            <div class="badge-row">
              <span v-if="detail.isOfficial === 1" class="badge-official mono">
                OFFICIAL 精选
              </span>
              <span class="badge-cat mono">
                {{ categoryLabels[detail.category] || detail.category }}
              </span>
              <span
                class="badge diff-badge"
                :class="`difficulty-${(detail.difficulty || 'MEDIUM').toLowerCase()}`"
              >
                {{ difficultyLabels[detail.difficulty] || detail.difficulty }}
              </span>
              <span class="author-meta mono">编排者：{{ detail.authorName }}</span>
            </div>

            <h1 class="set-title">{{ detail.title }}</h1>
            <div v-if="detail.subtitle" class="set-subtitle">{{ detail.subtitle }}</div>
            <p v-if="detail.description" class="set-desc">{{ detail.description }}</p>

            <div v-if="detail.tags && detail.tags.length > 0" class="tag-row">
              <span v-for="t in detail.tags" :key="t" class="tag-chip"># {{ t }}</span>
            </div>
          </div>

          <!-- 右侧攻克进度与断点续刷控制盒 -->
          <aside class="hero-progress-box">
            <div class="prog-header mono">
              <span class="prog-label">MY PROGRESS</span>
              <span class="prog-pct">{{ progressPercent }}%</span>
            </div>

            <div class="prog-numbers mono">
              <span class="solved-big">{{ detail.solvedCount || 0 }}</span>
              <span class="total-slash">/ {{ detail.itemCount }} 题已攻克</span>
            </div>

            <div class="prog-bar-track">
              <div
                class="prog-bar-fill"
                :style="{ width: `${progressPercent}%` }"
              ></div>
            </div>

            <div class="comp-pills mono">
              <span v-if="detail.algoCount > 0">算法 {{ detail.algoCount }}</span>
              <span v-if="detail.dbCount > 0">SQL {{ detail.dbCount }}</span>
              <span v-if="detail.objectiveCount > 0">客观 {{ detail.objectiveCount }}</span>
            </div>

            <button
              v-if="detail.resumeItem"
              type="button"
              class="btn btn-primary btn-block resume-btn"
              @click="openItem(detail.resumeItem)"
            >
              <span>
                {{ (detail.solvedCount || 0) > 0 ? '继续上次刷题 →' : '从第一题开始 →' }}
              </span>
            </button>
          </aside>
        </div>
      </header>

      <!-- 章节列表工具栏 -->
      <div class="sections-toolbar">
        <div class="toolbar-left mono">
          <span>CHAPTERS ({{ detail.sections.length }})</span>
          <span>·</span>
          <span>TOTAL {{ detail.itemCount }} ITEMS</span>
        </div>

        <div class="status-tabs">
          <button
            type="button"
            class="status-tab"
            :class="{ active: statusFilter === 'ALL' }"
            @click="statusFilter = 'ALL'"
          >
            全部 ({{ detail.itemCount }})
          </button>
          <button
            type="button"
            class="status-tab"
            :class="{ active: statusFilter === 'UNSOLVED' }"
            @click="statusFilter = 'UNSOLVED'"
          >
            未攻克 ({{ Math.max(0, detail.itemCount - (detail.solvedCount || 0)) }})
          </button>
          <button
            type="button"
            class="status-tab"
            :class="{ active: statusFilter === 'SOLVED' }"
            @click="statusFilter = 'SOLVED'"
          >
            已攻克 ({{ detail.solvedCount || 0 }})
          </button>
        </div>
      </div>

      <!-- 章节折叠面板列表 -->
      <div class="sections-list">
        <section
          v-for="(sec, sIdx) in detail.sections"
          :key="sec.id || sIdx"
          class="section-block card"
        >
          <div class="section-head" @click="toggleSection(sec.id)">
            <div class="sec-head-left">
              <span class="sec-index mono">
                {{ String(sIdx + 1).padStart(2, '0') }}
              </span>
              <div>
                <h2 class="sec-title">{{ sec.title }}</h2>
                <p v-if="sec.description" class="sec-desc">{{ sec.description }}</p>
              </div>
            </div>

            <div class="sec-head-right mono">
              <span class="sec-stat">
                {{ sec.solvedCount }} / {{ sec.totalCount }} 已通过
              </span>
              <span class="sec-toggle">
                {{ collapsedSections.has(sec.id) ? '+ 展开' : '− 收起' }}
              </span>
            </div>
          </div>

          <div v-if="!collapsedSections.has(sec.id)" class="section-items">
            <div
              v-if="filteredSectionItems(sec.items).length === 0"
              class="section-empty muted"
            >
              当前筛选条件下暂无题目
            </div>

            <div
              v-for="(item, idx) in filteredSectionItems(sec.items)"
              :key="item.id"
              class="item-row"
              :class="{
                'is-solved': item.solved,
                'is-dead': !item.sourceAlive,
              }"
              @click="openItem(item)"
            >
              <!-- 状态圈 -->
              <span
                class="status-circle mono"
                :class="{ solved: item.solved }"
                :title="item.solved ? '已攻克' : '未解答'"
              >
                {{ item.solved ? '✓' : String(idx + 1).padStart(2, '0') }}
              </span>

              <!-- 题型徽章 -->
              <span
                class="qtype-badge mono"
                :class="`qtype-${(item.questionType || 'ALGO').toLowerCase()}`"
              >
                {{ typeLabels[item.questionType] || item.questionType }}
              </span>

              <!-- 题目标题 -->
              <span class="item-title">
                <span class="item-qid mono">#{{ item.questionId }}</span>
                <span>{{ item.title }}</span>
                <span v-if="!item.sourceAlive" class="dead-tag">
                  [原题已归档]
                </span>
              </span>

              <!-- 难度或子题型徽标 -->
              <span
                v-if="item.difficulty"
                class="badge item-diff"
                :class="`difficulty-${item.difficulty.toLowerCase()}`"
              >
                {{ difficultyLabels[item.difficulty] || item.difficulty }}
              </span>

              <!-- 操作指引 -->
              <span class="item-action">
                {{ !item.sourceAlive ? '不可用' : item.solved ? '复盘 →' : '做题 →' }}
              </span>
            </div>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.set-detail-page {
  padding-top: 28px;
  padding-bottom: 72px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.set-hero {
  padding: 24px 28px 28px;
  background: #ffffff;
  margin-bottom: 24px;
}

.hero-top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 18px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--line);
}

.btn-back {
  padding: 6px 12px;
  border: 1px solid var(--line);
  background: var(--surface);
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-secondary);
}

.btn-back:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.top-right-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-fav {
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
}

.btn-fav.is-fav {
  background: #fff9db;
  border-color: #f08c00;
  color: #e67700;
}

.fav-count {
  font-size: 11.5px;
  opacity: 0.85;
}

.hero-body {
  display: grid;
  grid-template-columns: 1fr 310px;
  gap: 32px;
  align-items: stretch;
}

.badge-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.badge-official {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  background: var(--ink);
  color: #ffffff;
}

.badge-cat {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.diff-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
}

.difficulty-easy {
  background: #2b8a3e;
  color: #ffffff;
}
.difficulty-medium {
  background: #e67700;
  color: #ffffff;
}
.difficulty-hard {
  background: #c92a2a;
  color: #ffffff;
}

.author-meta {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.set-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 6px;
  letter-spacing: -0.4px;
}

.set-subtitle {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-secondary);
  margin-bottom: 12px;
}

.set-desc {
  font-size: 14px;
  color: var(--ink-secondary);
  line-height: 1.65;
  margin: 0 0 16px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-chip {
  font-size: 12px;
  padding: 2px 9px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

/* 右侧进度卡片 */
.hero-progress-box {
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 20px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.prog-header {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-tertiary);
  letter-spacing: 0.6px;
}

.prog-pct {
  color: #2b8a3e;
  font-size: 13px;
}

.prog-numbers {
  margin: 8px 0 10px;
}

.solved-big {
  font-size: 32px;
  font-weight: 700;
  color: var(--ink);
}

.total-slash {
  font-size: 13px;
  color: var(--ink-secondary);
  margin-left: 6px;
}

.prog-bar-track {
  width: 100%;
  height: 8px;
  background: #ffffff;
  border: 1px solid var(--line);
  margin-bottom: 12px;
}

.prog-bar-fill {
  height: 100%;
  background: #2b8a3e;
  transition: width 0.3s ease;
}

.comp-pills {
  display: flex;
  gap: 8px;
  font-size: 11.5px;
  color: var(--ink-secondary);
  margin-bottom: 14px;
}

.comp-pills span {
  padding: 2px 7px;
  background: #ffffff;
  border: 1px solid var(--line);
}

.resume-btn {
  padding: 11px 18px;
  font-size: 14px;
  font-weight: 600;
}

/* 工具栏 */
.sections-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.toolbar-left {
  display: flex;
  gap: 8px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-tertiary);
}

.status-tabs {
  display: flex;
  gap: 6px;
}

.status-tab {
  padding: 5px 12px;
  font-size: 12.5px;
  font-weight: 600;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.status-tab.active {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}

/* 章节卡片 */
.sections-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-block {
  background: #ffffff;
  overflow: hidden;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 22px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  user-select: none;
}

.sec-head-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.sec-index {
  width: 32px;
  height: 32px;
  background: var(--ink);
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.sec-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
}

.sec-desc {
  font-size: 12.5px;
  color: var(--ink-secondary);
  margin: 2px 0 0;
}

.sec-head-right {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  color: var(--ink-secondary);
}

.sec-toggle {
  padding: 2px 8px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink);
}

.section-items {
  display: flex;
  flex-direction: column;
}

.section-empty {
  padding: 24px;
  text-align: center;
  font-size: 13px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 22px;
  border-bottom: 1px solid #eef0f3;
  cursor: pointer;
  transition: background 0.15s ease;
}

.item-row:last-child {
  border-bottom: none;
}

.item-row:hover {
  background: #f8f9fb;
}

.item-row.is-dead {
  opacity: 0.55;
  cursor: not-allowed;
}

.status-circle {
  width: 26px;
  height: 26px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-tertiary);
  font-size: 11.5px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.status-circle.solved {
  background: #2b8a3e;
  border-color: #2b8a3e;
  color: #ffffff;
}

.qtype-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border: 1px solid var(--line);
  flex-shrink: 0;
}

.qtype-algo {
  background: #111111;
  color: #ffffff;
  border-color: #111111;
}

.qtype-db {
  background: #495057;
  color: #ffffff;
  border-color: #495057;
}

.qtype-objective {
  background: var(--surface);
  color: var(--ink);
}

.item-title {
  flex: 1;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
}

.item-qid {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.dead-tag {
  font-size: 11.5px;
  color: var(--danger);
  font-weight: 600;
}

.item-diff {
  font-size: 11px;
  padding: 2px 8px;
}

.item-action {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
  min-width: 56px;
  text-align: right;
}

@media (max-width: 860px) {
  .hero-body {
    grid-template-columns: 1fr;
  }
  .sections-toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
}
</style>
