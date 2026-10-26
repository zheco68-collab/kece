<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { objectiveApi } from '@/api/objective'
import { renderMarkdown } from '@/utils/markdown'
import { STATUS_ABBR, statusTone } from '@/utils/submission'
import { useUserStore } from '@/stores/user'
import { fetchUserInfo } from '@/utils/userCache'
import type {
  AnswerResult,
  ObjectiveQuestion,
  ObjectiveType,
  SubmissionStatus,
} from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const id = computed(() => Number(route.params.id))
const question = ref<ObjectiveQuestion | null>(null)
const loading = ref(true)
const error = ref('')
const authorName = ref('')
const authorAvatarUrl = ref<string | null>(null)

const selected = ref<string[]>([])
const fillText = ref('')
const result = ref<AnswerResult | null>(null)
const submitting = ref(false)
const submitError = ref('')

const typeLabels: Record<ObjectiveType, string> = {
  SINGLE: '单选',
  MULTIPLE: '多选',
  JUDGE: '判断',
  FILL: '填空',
}

const contentHtml = computed(() => renderMarkdown(question.value?.content ?? ''))

/** 客观题只判一次、只有一个「测试点」，用 AC/WA 映射到统一的节点方块样式。 */
const resultStatus = computed<SubmissionStatus>(() =>
  result.value?.correct ? 'ACCEPTED' : 'WRONG_ANSWER',
)

function optionLetter(index: number): string {
  return String.fromCharCode(65 + index)
}

function optionText(option: string): string {
  return option.replace(/^[A-Za-z][.、)）:：]\s*/, '')
}

function isSingle(q: ObjectiveQuestion): boolean {
  return q.type === 'SINGLE'
}

function isJudge(q: ObjectiveQuestion): boolean {
  return q.type === 'JUDGE'
}

function isFill(q: ObjectiveQuestion): boolean {
  return q.type === 'FILL'
}

function toggleOption(letter: string) {
  if (selected.value.includes(letter)) {
    selected.value = selected.value.filter((l) => l !== letter)
  } else {
    selected.value = [...selected.value, letter].sort()
  }
}

async function submit() {
  if (submitting.value || !question.value) return
  if (!userStore.isLoggedIn) {
    submitError.value = '请先登录后再作答'
    return
  }
  const answer = isFill(question.value) ? fillText.value.trim() : selected.value.join(',')
  if (!answer) {
    submitError.value = '请先作答'
    return
  }
  submitting.value = true
  submitError.value = ''
  try {
    // 后端判分并落库，答对后列表页会显示绿色对勾。
    result.value = await objectiveApi.submit(id.value, answer)
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    question.value = await objectiveApi.detail(id.value)
    if (question.value?.authorName) {
      authorName.value = question.value.authorName
      authorAvatarUrl.value = question.value.authorAvatarUrl || null
    } else if (question.value?.createdBy) {
      fetchUserInfo(question.value.createdBy).then((u) => {
        if (u.username) authorName.value = u.username
        if (u.avatarUrl) authorAvatarUrl.value = u.avatarUrl
      })
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="answer container">
    <button class="back" @click="router.back()">← 返回</button>

    <div v-if="loading" class="state-card card"><div class="loading-spinner"></div><p class="muted">正在加载客观题详情与选项…</p></div>
    <div v-else-if="error" class="submit-error">{{ error }}</div>

    <template v-else-if="question">
      <div class="head">
        <span class="badge" :class="`type-${question.type.toLowerCase()}`">
          {{ typeLabels[question.type] }}
        </span>
        <span class="score">{{ question.score }} 分</span>
        <div v-if="question.createdBy" class="meta-item-author">
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

      <!-- eslint-disable-next-line vue/no-v-html -- 内容已由 renderMarkdown 清洗 -->
      <div class="prose card" v-html="contentHtml"></div>

      <div v-if="isJudge(question)" class="judge-options">
        <label
          class="judge-option"
          :class="{ selected: selected[0] === '对' }"
          @click="selected = ['对']"
        >
          <input type="radio" name="judge" :checked="selected[0] === '对'" />
          <span class="judge-box-letter">✓</span>
          <span class="option-text">对（正确）</span>
        </label>
        <label
          class="judge-option"
          :class="{ selected: selected[0] === '错' }"
          @click="selected = ['错']"
        >
          <input type="radio" name="judge" :checked="selected[0] === '错'" />
          <span class="judge-box-letter">✕</span>
          <span class="option-text">错（错误）</span>
        </label>
      </div>

      <div v-else-if="!isFill(question)" class="options">
        <label
          v-for="(opt, i) in question.options"
          :key="i"
          class="option"
          :class="{ selected: selected.includes(optionLetter(i)) }"
        >
          <input
            v-if="isSingle(question)"
            type="radio"
            name="option"
            :checked="selected[0] === optionLetter(i)"
            @change="selected = [optionLetter(i)]"
          />
          <input
            v-else
            type="checkbox"
            :checked="selected.includes(optionLetter(i))"
            @change="toggleOption(optionLetter(i))"
          />
          <span class="option-letter">{{ optionLetter(i) }}</span>
          <span class="option-text">{{ optionText(opt) }}</span>
        </label>
      </div>

      <div v-else class="fill">
        <input
          v-model="fillText"
          class="input"
          type="text"
          placeholder="请输入答案"
          @keyup.enter="submit"
        />
      </div>

      <p v-if="submitError" class="submit-error">{{ submitError }}</p>

      <div class="actions">
        <button
          v-if="!userStore.isLoggedIn"
          class="btn btn-primary btn-lg"
          @click="router.push('/login')"
        >
          登录后作答
        </button>
        <button v-else class="btn btn-primary btn-lg" :disabled="submitting" @click="submit">
          {{ submitting ? '判分中…' : '提交答案' }}
        </button>
      </div>

      <!-- 判分结果：客观题只有一个「测试点」，沿用算法/数据库 OJ 的结果方块 -->
      <div v-if="result" class="result">
        <div class="node-statuses">
          <span class="node-status" :class="statusTone(resultStatus)">
            <span class="node-no">#1</span>
            <span class="node-abbr">{{ STATUS_ABBR[resultStatus] ?? resultStatus }}</span>
            <span class="node-meta">{{ result.score }} / {{ result.fullScore }} 分</span>
          </span>
        </div>
        <p class="result-answer">正确答案：{{ result.correctAnswer }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.answer {
  padding-top: 32px;
  padding-bottom: 56px;
}
.head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}
.meta-item-author {
  margin-left: auto;
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
.score {
  color: var(--ink-tertiary);
  font-size: 13px;
}
.options {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 20px;
}
.option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background 0.15s ease;
}
.option:hover {
  border-color: var(--ink-tertiary);
}
.option.selected {
  border-color: var(--ink);
  background: var(--bg);
}
.option input {
  accent-color: var(--button);
}
.option-letter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 0;
  background: var(--bg);
  border: 1px solid var(--line);
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.option.selected .option-letter {
  background: var(--button);
  border-color: var(--button);
  color: #fff;
}
.option-text {
  min-width: 0;
}
.fill {
  margin-top: 20px;
  max-width: 420px;
}
.actions {
  margin-top: 24px;
}
.submit-error {
  margin-top: 16px;
}
.result {
  margin-top: 24px;
}
.result-answer {
  margin: 14px 0 0;
  color: var(--ink-tertiary);
  font-size: 14px;
}

/* 测试点方块：尺寸/配色/字体与数据库 OJ 的结果面板保持一致 */
.node-statuses {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.node-status {
  position: relative;
  width: 150px;
  height: 150px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  color: #fff;
}
.node-no {
  position: absolute;
  top: 8px;
  left: 10px;
  font-size: 12px;
  font-weight: 400;
  opacity: 0.85;
  line-height: 1;
}
.node-abbr {
  font-size: 22px;
  font-weight: 700;
  line-height: 1;
}
.node-meta {
  font-size: 11px;
  font-weight: 400;
  opacity: 0.9;
  line-height: 1.3;
}
.node-status.ac {
  background: var(--success);
}
.node-status.wa {
  background: var(--danger);
}
.node-status.tle {
  background: #000;
}
.node-status.mle {
  background: #f57c00;
}
.node-status.re {
  background: #9c27b0;
}
.node-status.ce,
.node-status.se {
  background: #607d8b;
}
.node-status.run {
  background: #9e9e9e;
}
.judge-options {
  display: flex;
  gap: 16px;
  margin-top: 20px;
}
.judge-option {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border: 1px solid var(--line);
  background: var(--surface);
  cursor: pointer;
  transition: all 0.15s ease;
}
.judge-option:hover {
  border-color: var(--ink-tertiary);
}
.judge-option.selected {
  border-color: var(--ink);
  background: var(--bg);
}
.judge-option input {
  display: none;
}
.judge-box-letter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: var(--bg);
  border: 1px solid var(--line);
  font-size: 16px;
  font-weight: 700;
  flex-shrink: 0;
}
.judge-option.selected .judge-box-letter {
  background: var(--ink);
  color: #fff;
  border-color: var(--ink);
}
</style>
