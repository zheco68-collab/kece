<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { practiceApi } from '@/api/practice'
import { questionApi } from '@/api/question'
import { renderMarkdown } from '@/utils/markdown'
import { useToast } from '@/composables/useToast'
import type { Question } from '@/types'
import { DIFFICULTY_LABELS as difficultyLabels } from '@/utils/submission'
import type { PracticeDetail, PracticePracticalQuestion } from '@/types/practice'
import { copyToClipboard } from '@/utils/clipboard'

const route = useRoute()
const router = useRouter()
const { success, error: toastError } = useToast()

const code = computed(() => String(route.params.code).toUpperCase())
const practiceQuestionId = computed(() => Number(route.params.id))

const practiceDetail = ref<PracticeDetail | null>(null)
const practiceQuestion = ref<PracticePracticalQuestion | null>(null)
const question = ref<Question | null>(null)
const loading = ref(true)
const error = ref('')

const isSolved = computed(() => practiceQuestion.value?.userStatus === 'ACCEPTED')



const descriptionHtml = computed(() => renderMarkdown(question.value?.description ?? ''))

const sampleTestCases = computed(() => question.value?.sampleTestCases ?? [])

async function copyText(text: string | null | undefined, label: string) {
  if (!text) return
  const ok = await copyToClipboard(text)
  if (ok) {
    success(`${label}已复制到剪贴板`)
  } else {
    toastError('复制失败，请手动选取复制')
  }
}

function goToSubmit() {
  router.push(`/practice/${code.value}/question/${practiceQuestionId.value}/submit`)
}

function goToResult() {
  if (practiceQuestion.value?.submissionId) {
    router.push(
      `/practice/${code.value}/question/${practiceQuestionId.value}/result/${practiceQuestion.value.submissionId}`,
    )
  }
}

onMounted(async () => {
  try {
    const pDetail = await practiceApi.detail(code.value)
    practiceDetail.value = pDetail

    const pq = pDetail.practicalQuestions.find(
      (item) => item.practiceQuestionId === practiceQuestionId.value,
    )
    if (!pq) {
      error.value = '未找到该实战题目'
      return
    }
    practiceQuestion.value = pq

    question.value = await questionApi.detail(pq.questionId)
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
      <button class="btn btn-outline" @click="router.push(`/practice/${code}`)">
        返回课练题目列表
      </button>
    </div>

    <!-- 题目主内容区 (直接照抄 QuestionDetailView 标准架构与视觉样式) -->
    <template v-else-if="question && practiceQuestion">
      <!-- 顶部面包屑与标题卡片 -->
      <header class="q-header card">
        <div class="header-top">
          <button class="btn-back" @click="router.push(`/practice/${code}`)">
            <svg class="back-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            <span>返回课练题目列表</span>
          </button>
          <div class="header-status-tags">
            <span v-if="isSolved" class="solved-pill">✓ 已满分通过 ({{ practiceQuestion.score }}分)</span>
            <span v-else-if="practiceQuestion.userScore > 0" class="partial-pill">
              ◐ 已得 {{ practiceQuestion.userScore }} / {{ practiceQuestion.score }} 分 (部分分)
            </span>
            <span class="badge diff-pill" :class="`difficulty-${question.difficulty.toLowerCase()}`">
              {{ difficultyLabels[question.difficulty] }}
            </span>
            <span class="practice-pill">本题分值: {{ practiceQuestion.score }} 分</span>
          </div>
        </div>

        <div class="header-main">
          <h1 class="q-title">
            <span class="q-id">#{{ question.id }}</span>
            <span class="q-text">{{ question.title }}</span>
          </h1>
          <div class="header-actions">
            <button
              v-if="practiceQuestion.submissionId"
              class="btn btn-outline btn-view-result"
              @click="goToResult"
            >
              查看测评结果
            </button>
            <button
              v-if="practiceDetail?.status !== 'ENDED' && !practiceDetail?.mySubmitted"
              class="btn btn-primary btn-submit-cta"
              @click="goToSubmit"
            >
              <span>提交代码</span>
              <svg class="cta-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </button>
          </div>
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
        </div>
      </header>

      <!-- 题面正文卡片 -->
      <article class="q-body-card card">
        <div class="card-section-head">
          <span class="sec-title">题目描述</span>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div class="prose" v-html="descriptionHtml"></div>
      </article>

      <!-- 样例测试数据卡片 -->
      <section v-if="sampleTestCases.length" class="q-samples-card card">
        <div class="card-section-head">
          <span class="sec-title">样例测试数据</span>
          <span class="sec-count">共 {{ sampleTestCases.length }} 组公开样例</span>
        </div>

        <div class="samples-grid">
          <div
            v-for="(tc, i) in sampleTestCases"
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
  border: 1px solid var(--line);
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
  background: #ffffff;
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 500;
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-back:hover {
  background: var(--ink);
  color: #ffffff;
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
.partial-pill {
  font-size: 12px;
  font-weight: 700;
  color: #d9480f;
  background: #fff4e6;
  border: 1px solid #f59f00;
  padding: 3px 8px;
  border-radius: 0;
}
.diff-pill {
  border-radius: 0 !important;
  font-size: 12px;
  padding: 3px 10px;
  font-weight: 700;
}
.practice-pill {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 3px 10px;
  border-radius: 0;
}

.header-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
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
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.btn-view-result {
  font-size: 13px;
  padding: 8px 16px;
  border-radius: 0;
  border-color: var(--line);
  color: var(--ink-secondary);
}
.btn-view-result:hover {
  border-color: var(--ink);
  color: var(--ink);
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
  background: #e9ecef;
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
  border: 1px solid var(--line);
  overflow: hidden;
}
.prose {
  padding: 28px;
  line-height: 1.8;
  font-size: 15px;
  color: var(--ink);
  background: #ffffff;
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
  background: #f8f9fa;
  color: var(--ink);
  border: 1px solid var(--line);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  border-radius: 0;
}
.prose :deep(pre) {
  background: #f8f9fa;
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
  border: 1px solid var(--line);
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
  background: #ffffff;
  overflow: hidden;
}
.sample-item-head {
  padding: 10px 16px;
  background: #e9ecef;
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
  background: #ffffff;
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
  background: #f8f9fa;
  padding: 10px 12px;
  border: 1px solid var(--line);
}
</style>
