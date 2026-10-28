<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { questionApi } from '@/api/question'
import { submissionApi } from '@/api/submission'
import { renderMarkdown } from '@/utils/markdown'
import { useUserStore } from '@/stores/user'
import { useToast } from '@/composables/useToast'
import type { Question } from '@/types'
import { DIFFICULTY_LABELS as difficultyLabels } from '@/utils/submission'
import { copyToClipboard } from '@/utils/clipboard'
import { fetchUserInfo } from '@/utils/userCache'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { success, error: toastError } = useToast()

const id = computed(() => Number(route.params.id))

const question = ref<Question | null>(null)
const loading = ref(true)
const error = ref('')
const isSolved = ref(false)
const authorName = ref('')
const authorAvatarUrl = ref<string | null>(null)



const descriptionHtml = computed(() => renderMarkdown(question.value?.description ?? ''))

async function copyText(text: string | null | undefined, label: string) {
  if (!text) return
  const ok = await copyToClipboard(text)
  if (ok) {
    success(`${label}已复制到剪贴板`)
  } else {
    toastError('复制失败，请手动选取复制')
  }
}

const fromSetId = computed(() => {
  const s = route.query.setId
  return s ? String(s) : null
})

function goBack() {
  if (fromSetId.value) {
    router.push(`/problem-sets/${fromSetId.value}`)
  } else {
    router.push('/questions')
  }
}

function goToSubmit() {
  const query = fromSetId.value
    ? { setId: fromSetId.value, itemId: String(route.query.itemId || '') }
    : undefined
  router.push({ path: `/questions/${id.value}/submit`, query })
}

async function loadSolvedStatus() {
  if (!userStore.isLoggedIn) return
  try {
    const solvedIds = await submissionApi.solved()
    if (solvedIds.includes(id.value)) {
      isSolved.value = true
    }
  } catch {
    /* 忽略已通过状态拉取失败 */
  }
}

onMounted(async () => {
  try {
    question.value = await questionApi.detail(id.value)
    if (question.value?.authorName) {
      authorName.value = question.value.authorName
      authorAvatarUrl.value = question.value.authorAvatarUrl || null
    } else if (question.value?.createdBy) {
      fetchUserInfo(question.value.createdBy).then((u) => {
        if (u.username) authorName.value = u.username
        if (u.avatarUrl) authorAvatarUrl.value = u.avatarUrl
      })
    }
    await loadSolvedStatus()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载题目失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="question-page container">
    <!-- 加载与异常状态 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在加载编程题描述与测评数据…</p>
    </div>

    <div v-else-if="error" class="state-card card">
      <div class="error-title">加载失败</div>
      <p class="error-msg">{{ error }}</p>
      <button class="btn btn-outline" @click="goBack">{{ fromSetId ? '返回题集' : '返回题目列表' }}</button>
    </div>

    <!-- 题目主内容区 -->
    <template v-else-if="question">
      <!-- 顶部面包屑与标题卡片 -->
      <header class="q-header card">
        <div class="header-top">
          <button class="btn-back" @click="goBack">
            <svg class="back-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            <span>{{ fromSetId ? '返回题集' : '返回题库列表' }}</span>
          </button>
          <div class="header-status-tags">
            <span v-if="isSolved" class="solved-pill">✓ 已通过此题</span>
            <span class="badge diff-pill" :class="`difficulty-${question.difficulty.toLowerCase()}`">
              {{ difficultyLabels[question.difficulty] }}
            </span>
          </div>
        </div>

        <div class="header-main">
          <h1 class="q-title">
            <span class="q-id">#{{ question.id }}</span>
            <span class="q-text">{{ question.title }}</span>
          </h1>
          <button class="btn btn-primary btn-submit-cta" @click="goToSubmit">
            <span>提交代码</span>
            <svg class="cta-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="5" y1="12" x2="19" y2="12" />
              <polyline points="12 5 19 12 12 19" />
            </svg>
          </button>
        </div>

        <!-- 测评限制规格与环境参数 -->
        <div class="header-meta-row">
          <div class="meta-item">
            <span class="meta-label">时间限制</span>
            <span class="meta-val">{{ question.timeLimit ?? '-' }} ms</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">内存限制</span>
            <span class="meta-val">{{ question.memoryLimit ?? '-' }} MB</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">支持语言</span>
            <span class="meta-val">{{ question.languages?.join(' / ') || 'C++ / Java / Python' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">测评环境</span>
            <span class="meta-val">Docker 沙箱隔离执行</span>
          </div>
          <div v-if="question.tags && question.tags.length > 0" class="meta-item meta-item-tags">
            <span class="meta-label">算法分类标签</span>
            <div class="meta-tags-list">
              <span v-for="t in question.tags" :key="t" class="detail-tag-chip mono">#{{ t }}</span>
            </div>
          </div>
          <div v-if="question.createdBy" class="meta-item meta-item-author">
            <span class="meta-label">出题人</span>
            <RouterLink
              :to="`/profile/${question.createdBy}`"
              class="meta-author"
              :title="`出题人：${authorName || question.authorName || '出题教师'} · 点击进入个人主页`"
            >
              <img
                v-if="authorAvatarUrl || question.authorAvatarUrl"
                :src="authorAvatarUrl || question.authorAvatarUrl!"
                class="meta-author-avatar"
                alt="头像"
              />
              <span v-else class="meta-author-fallback">
                <svg class="meta-author-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
              </span>
              <span class="meta-author-name">{{ authorName || question.authorName || '出题教师' }}</span>
            </RouterLink>
          </div>
        </div>
      </header>

      <!-- 题面正文卡片 -->
      <article class="q-body-card card">
        <div class="card-section-head">
          <span class="sec-title">题目描述</span>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -- 内容已由 renderMarkdown 清洗 -->
        <div class="prose" v-html="descriptionHtml"></div>
      </article>

      <!-- 样例测试数据卡片 -->
      <section v-if="question.sampleTestCases?.length" class="q-samples-card card">
        <div class="card-section-head">
          <span class="sec-title">样例测试数据</span>
          <span class="sec-count">共 {{ question.sampleTestCases.length }} 组公开样例</span>
        </div>

        <div class="samples-grid">
          <div
            v-for="(tc, i) in question.sampleTestCases"
            :key="tc.id"
            class="sample-item card"
          >
            <div class="sample-item-head">
              <span class="sample-idx">样例 #{{ i + 1 }}</span>
            </div>
            <div class="sample-row">
              <div class="sample-block">
                <div class="block-bar">
                  <span class="block-title">标准输入 (stdin)</span>
                  <button class="btn-copy" @click="copyText(tc.input, `样例 #${i + 1} 输入`)">
                    复制输入
                  </button>
                </div>
                <pre class="sample-code">{{ tc.input || '（无输入）' }}</pre>
              </div>

              <div class="sample-block">
                <div class="block-bar">
                  <span class="block-title">标准期望输出 (stdout)</span>
                  <button class="btn-copy" @click="copyText(tc.expectedOutput, `样例 #${i + 1} 期望输出`)">
                    复制输出
                  </button>
                </div>
                <pre class="sample-code">{{ tc.expectedOutput }}</pre>
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.question-page {
  padding-top: 24px;
  padding-bottom: 64px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 状态加载卡片 */
.state-card {
  padding: 64px 32px;
  text-align: center;
  border-radius: 0;
}
.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--line);
  border-top-color: var(--ink);
  animation: spin 0.8s linear infinite;
  margin: 0 auto 16px;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.error-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--danger);
  margin-bottom: 8px;
}
.error-msg {
  color: var(--ink-secondary);
  margin-bottom: 24px;
}

/* 顶部信息卡片 */
.q-header {
  padding: 24px 28px;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
  background: var(--surface);
}
.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 500;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-back:hover {
  background: var(--surface);
  color: var(--ink);
  border-color: var(--ink);
}
.back-icon {
  width: 14px;
  height: 14px;
}
.header-status-tags {
  display: flex;
  align-items: center;
  gap: 10px;
}
.solved-pill {
  font-size: 12px;
  font-weight: 700;
  color: var(--success);
  background: #e8f5e9;
  border: 1px solid var(--success);
  padding: 3px 8px;
  border-radius: 0;
}
.diff-pill {
  border-radius: 0 !important;
  font-size: 12px;
  padding: 3px 10px;
  font-weight: 700;
}

.header-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}
.meta-item-author {
  margin-left: auto;
}
.meta-author {
  display: inline-flex;
  align-items: stretch;
  height: 32px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink);
  text-decoration: none;
  font-size: 13px;
  font-weight: 600;
  border-radius: 0;
  overflow: hidden;
  transition: all 0.15s ease;
  user-select: none;
}
.meta-author:hover {
  background: var(--surface);
  border-color: var(--ink);
}
.meta-author-avatar {
  height: 100%;
  aspect-ratio: 1 / 1;
  object-fit: cover;
  border: none;
  border-right: 1px solid var(--line);
  border-radius: 0;
  display: block;
  flex-shrink: 0;
}
.meta-author:hover .meta-author-avatar {
  border-right-color: var(--ink);
}
.meta-author-fallback {
  height: 100%;
  aspect-ratio: 1 / 1;
  background: var(--surface);
  border-right: 1px solid var(--line);
  color: var(--ink-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 0;
  flex-shrink: 0;
  transition: all 0.15s ease;
}
.meta-author:hover .meta-author-fallback {
  border-right-color: var(--ink);
  color: var(--ink);
}
.meta-author-svg {
  width: 15px;
  height: 15px;
}
.meta-author-name {
  display: flex;
  align-items: center;
  padding: 0 10px;
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 600;
  transition: color 0.15s ease;
  white-space: nowrap;
}
.meta-author:hover .meta-author-name {
  color: var(--ink);
}
.q-title {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--ink);
}
.q-id {
  color: var(--ink-tertiary);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}
.btn-submit-cta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  font-size: 14px;
  font-weight: 700;
  border-radius: 0;
  cursor: pointer;
}
.cta-arrow {
  width: 16px;
  height: 16px;
}

.meta-tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.detail-tag-chip {
  font-size: 11.5px;
  padding: 2px 8px;
  background: #1d4ed8;
  border: 1px solid #1e40af;
  color: #ffffff;
  font-weight: 600;
}

.header-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
.meta-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.meta-label {
  font-size: 11px;
  color: var(--ink-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.meta-val {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

/* 章节头部 */
.card-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
}
.sec-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}
.sec-count {
  font-size: 12px;
  color: var(--ink-tertiary);
}

/* 题面正文卡片 */
.q-body-card {
  border-radius: 0;
  background: var(--surface);
  overflow: hidden;
}
.prose {
  padding: 28px;
  line-height: 1.8;
  font-size: 15px;
  color: var(--ink);
}
.prose :deep(h1),
.prose :deep(h2),
.prose :deep(h3) {
  margin-top: 28px;
  margin-bottom: 14px;
  font-weight: 700;
  color: var(--ink);
}
.prose :deep(p) {
  margin: 14px 0;
}
.prose :deep(code) {
  padding: 2px 6px;
  background: var(--bg);
  color: var(--ink);
  border: 1px solid var(--line);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  border-radius: 0;
}
.prose :deep(pre) {
  background: var(--bg);
  color: var(--ink);
  border: 1px solid var(--line);
  padding: 16px;
  overflow-x: auto;
  border-radius: 0;
}
.prose :deep(pre code) {
  background: none;
  border: none;
  padding: 0;
  color: inherit;
}

/* 样例卡片 */
.q-samples-card {
  border-radius: 0;
  background: var(--surface);
  overflow: hidden;
}
.samples-grid {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.sample-item {
  border-radius: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  overflow: hidden;
}
.sample-item-head {
  padding: 10px 16px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
}
.sample-idx {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}
.sample-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
}
@media (max-width: 768px) {
  .sample-row {
    grid-template-columns: 1fr;
  }
}
.sample-block {
  padding: 14px 16px;
  border-right: 1px solid var(--line);
}
.sample-block:last-child {
  border-right: none;
}
.block-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary);
}
.btn-copy {
  padding: 2px 8px;
  font-size: 11px;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--ink-secondary);
  cursor: pointer;
  border-radius: 0;
  transition: all 0.15s ease;
}
.btn-copy:hover {
  background: var(--ink);
  color: #fff;
  border-color: var(--ink);
}
.sample-code {
  margin: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
  color: var(--ink);
}

</style>
