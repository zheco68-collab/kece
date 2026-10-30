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
import { renderMarkdown } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const toast = useToast()

const setId = computed(() => Number(route.params.id))
const detail = ref<ProblemSetDetail | null>(null)
const descriptionHtml = computed(() => renderMarkdown(detail.value?.description || ''))
const loading = ref(true)
const error = ref('')
const collapsedSections = ref<Set<number>>(new Set())
const statusFilter = ref<'ALL' | 'UNSOLVED' | 'SOLVED'>('ALL')

const categoryLabels: Record<string, string> = {
  ALGO: '算法编程',
  DB: '数据库 SQL',
  OBJECTIVE: '标准客观',
  MIXED: '综合题集',
}

const difficultyLabels: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}

function getItemBadge(item: ProblemSetItemVO) {
  const d = (item.difficulty || '').toUpperCase()
  // 1. 算法题难度（与算法题库 pill-easy / pill-medium / pill-hard 保持一致）
  if (d === 'EASY') return { label: '简单', cls: 'pill-easy' }
  if (d === 'MEDIUM') return { label: '中等', cls: 'pill-medium' }
  if (d === 'HARD') return { label: '困难', cls: 'pill-hard' }

  // 2. 数据库题型（与数据库题库 pill-select / pill-dml 保持一致）
  if (d === 'SELECT') return { label: 'SELECT 查询', cls: 'pill-select' }
  if (d === 'DML') return { label: 'DML 操作', cls: 'pill-dml' }

  // 3. 客观题题型（与客观题题库对应，鲜明可见，绝不出现白字白底）
  if (d === 'SINGLE') return { label: '单选题', cls: 'pill-single' }
  if (d === 'MULTIPLE') return { label: '多选题', cls: 'pill-multiple' }
  if (d === 'JUDGE') return { label: '判断题', cls: 'pill-judge' }
  if (d === 'FILL') return { label: '填空题', cls: 'pill-fill' }

  // 兜底
  return { label: item.difficulty || '综合', cls: 'pill-default' }
}

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
            <!-- eslint-disable-next-line vue/no-v-html -- 内容已由 renderMarkdown 清洗 -->
            <div
              v-if="detail.description"
              class="set-desc markdown-body"
              v-html="descriptionHtml"
            ></div>

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

          <div
            v-if="!collapsedSections.has(sec.id)"
            :key="`${sec.id}-${statusFilter}`"
            class="section-items"
          >
            <div
              v-if="filteredSectionItems(sec.items).length === 0"
              class="section-empty muted"
            >
              当前筛选条件下暂无题目
            </div>

            <!-- 题目条目：样式与相应题库完全对齐，并保持相同的下落动效 -->
            <div
              v-for="(item, idx) in filteredSectionItems(sec.items)"
              :key="item.id"
              class="question-item card"
              :class="{ 'is-dead': !item.sourceAlive }"
              :style="{ '--drop-index': idx }"
              @click="openItem(item)"
            >
              <!-- 左侧：解题状态图标 + 题号 + 标题与元数据 -->
              <div class="q-main-layout">
                <!-- 解题状态图标（与题库完全一致的绿圆勾或未完成圆环） -->
                <div class="q-status-wrap">
                  <span
                    v-if="item.solved"
                    class="status-icon solved"
                    title="已攻克 (Accepted)"
                  >
                    <svg viewBox="0 0 16 16" fill="currentColor">
                      <path d="M16 8A8 8 0 1 1 0 8a8 8 0 0 1 16 0Zm-3.97-3.03a.75.75 0 0 0-1.08.022L7.477 9.417 5.384 7.323a.75.75 0 0 0-1.06 1.06L6.97 11.03a.75.75 0 0 0 1.079-.02l3.992-4.99a.75.75 0 0 0-.01-1.05Z" />
                    </svg>
                  </span>
                  <span v-else class="status-icon unsolved" title="尚未解答">
                    <span class="unsolved-ring"></span>
                  </span>
                </div>

                <!-- 题号 (Mono) -->
                <span class="q-number mono">#{{ item.questionId }}</span>

                <!-- 标题与次级信息 -->
                <div class="q-content">
                  <h3 class="q-title">
                    {{ item.title }}
                    <span v-if="!item.sourceAlive" class="dead-tag">[原题已归档]</span>
                  </h3>

                  <!-- 相应题库对应的次级元数据行 -->
                  <div class="q-tags-row">
                    <!-- 算法编程 (ALGO) 题库元数据标签 -->
                    <template v-if="(item.questionType || detail.category) === 'ALGO'">
                      <span class="meta-tag limits mono">1000ms / 256MB</span>
                      <span class="meta-tag lang mono">C++</span>
                      <span class="meta-tag lang mono">Java</span>
                      <span class="meta-tag lang mono">Python</span>
                      <span class="meta-tag lang mono">arkts</span>
                    </template>

                    <!-- 数据库 SQL (DB) 题库元数据标签 -->
                    <template v-else-if="(item.questionType || detail.category) === 'DB'">
                      <span class="meta-tag dialect mono">MySQL 8.0</span>
                      <span class="meta-tag limits mono">无序比对</span>
                    </template>

                    <!-- 客观题 (OBJECTIVE) 题库元数据标签 -->
                    <template v-else>
                      <span class="meta-tag type mono">标准客观题</span>
                      <span class="meta-tag limits mono">即时判分</span>
                    </template>
                  </div>
                </div>
              </div>

              <!-- 右侧：难度徽章 + 快速答题按钮（与题库完全一致） -->
              <div class="q-right-layout">
                <span
                  class="difficulty-pill"
                  :class="getItemBadge(item).cls"
                >
                  {{ getItemBadge(item).label }}
                </span>

                <span class="action-cta">
                  <span class="cta-text">开始做题</span>
                  <svg class="cta-arrow" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M6 3l5 5-5 5" />
                  </svg>
                </span>
              </div>
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
  border: 1px solid var(--line);
  background: var(--surface);
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
  transition: all 0.15s ease;
}

.btn-fav:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.btn-fav.is-fav {
  background: #fff9db;
  border-color: #f08c00;
  color: #e67700;
}

.fav-count {
  margin-left: 4px;
  font-size: 12px;
}

.hero-body {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 32px;
  align-items: start;
}

.hero-main {
  display: flex;
  flex-direction: column;
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
  letter-spacing: 0.5px;
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
  margin-left: 4px;
}

.set-title {
  font-size: 26px;
  font-weight: 800;
  color: var(--ink);
  margin: 0 0 6px;
  letter-spacing: -0.5px;
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
  overflow-wrap: anywhere;
  word-break: break-word;
}

.set-desc :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 4px;
  margin: 12px 0;
  display: block;
}

.set-desc :deep(p) {
  margin: 0 0 8px;
}

.set-desc :deep(p:last-child) {
  margin-bottom: 0;
}

.set-desc :deep(strong) {
  color: var(--ink);
  font-weight: 600;
}

.set-desc :deep(code) {
  padding: 2px 6px;
  background: var(--bg);
  color: var(--ink);
  border: 1px solid var(--line);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
}

.set-desc :deep(pre) {
  background: var(--bg);
  color: var(--ink);
  border: 1px solid var(--line);
  padding: 12px 14px;
  margin: 10px 0;
  overflow-x: auto;
}

.set-desc :deep(pre code) {
  background: none;
  border: none;
  padding: 0;
  color: inherit;
}

.set-desc :deep(ul),
.set-desc :deep(ol) {
  margin: 6px 0 8px 20px;
  padding: 0;
}

.set-desc :deep(li) {
  margin-bottom: 4px;
}

.set-desc :deep(blockquote) {
  margin: 8px 0;
  padding: 6px 14px;
  border-left: 3px solid var(--line-strong, #ccc);
  color: var(--ink-secondary);
}

.set-desc :deep(a) {
  color: var(--primary, #10b981);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.set-desc :deep(h1),
.set-desc :deep(h2),
.set-desc :deep(h3),
.set-desc :deep(h4) {
  color: var(--ink);
  font-weight: 700;
  margin: 12px 0 6px;
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
  align-self: start;
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

/* 章节卡片列表 */
.sections-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-block {
  background: #ffffff;
  border: 1px solid var(--line);
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

/* ============================================================
   题目列表容器与条目：与题库列表 1:1 完全对齐并具有相同的掉落动效
============================================================ */
.section-items {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  background: var(--bg);
}

.section-empty {
  padding: 24px;
  text-align: center;
  font-size: 13px;
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
  /* 题目卡片入场从章节下方按序掉落效果，与题库完全一致 */
  animation: cardDropIn 0.44s cubic-bezier(0.25, 1.15, 0.5, 1) backwards;
  animation-delay: calc(var(--drop-index, 0) * 45ms);
  will-change: transform, opacity;
}

.question-item:hover {
  border-color: var(--ink);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.06);
  transform: translateY(-2px) !important;
}

.question-item.is-dead {
  opacity: 0.55;
  cursor: not-allowed;
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

.dead-tag {
  color: var(--danger);
  font-size: 12px;
  font-weight: 500;
  margin-left: 6px;
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

.meta-tag.lang {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  color: #3f3f46;
  font-weight: 600;
}

.meta-tag.dialect {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
  font-weight: 600;
}

.meta-tag.type {
  background: #f4f4f5;
  border: 1px solid #e4e4e7;
  color: #3f3f46;
  font-weight: 600;
}

/* 右侧：难度徽标与操作入口 */
.q-right-layout {
  display: flex;
  align-items: center;
  gap: 16px;
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
  color: #ffffff;
  white-space: nowrap;
}

/* 算法题难度 (ALGO - 与算法题库一致) */
.pill-easy {
  background: #2b8a3e;
  color: #ffffff;
  border: 1px solid #237032;
}

.pill-medium {
  background: #e67700;
  color: #ffffff;
  border: 1px solid #c96500;
}

.pill-hard {
  background: #c92a2a;
  color: #ffffff;
  border: 1px solid #b02525;
}

/* 数据库 SQL 题型 (DB - 与数据库题库一致) */
.pill-select {
  background: #1e1e1e;
  color: #ffffff;
  border: 1px solid #111111;
}

.pill-dml {
  background: #495057;
  color: #ffffff;
  border: 1px solid #343a40;
}

/* 客观题题型 (由白到黑渐变: 单选题 -> 多选题 -> 判断题 -> 填空题) */
.pill-single {
  background: #f8f9fa;
  color: #18181b;
  border: 1px solid #d4d4d8;
}

.pill-multiple {
  background: #d4d4d8;
  color: #18181b;
  border: 1px solid #a1a1aa;
}

.pill-judge {
  background: #52525b;
  color: #ffffff;
  border: 1px solid #3f3f46;
}

.pill-fill {
  background: #18181b;
  color: #ffffff;
  border: 1px solid #09090b;
}

.pill-default {
  background: #495057;
  color: #ffffff;
  border: 1px solid #343a40;
}

.action-cta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-secondary);
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

/* 状态卡片 */
.state-card {
  padding: 56px 0;
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--line);
}

.error-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--danger);
  margin-bottom: 8px;
}

.error-msg {
  font-size: 14px;
  color: var(--ink-secondary);
  margin-bottom: 20px;
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
