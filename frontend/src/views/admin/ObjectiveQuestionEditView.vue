<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/request'
import { objectiveApi } from '@/api/objective'
import FormField from '@/components/FormField.vue'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import { useToast } from '@/composables/useToast'
import type { MatchType, ObjectiveType } from '@/types'

const route = useRoute()
const router = useRouter()
const toast = useToast()

const questionId = computed(() => {
  const raw = route.params.id
  return raw ? Number(raw) : null
})
const isEdit = computed(() => questionId.value !== null)

const submitting = ref(false)
const loading = ref(false)
const pageError = ref('')

const initialType = (route.query.type as ObjectiveType) || 'SINGLE'
const validInitialType: ObjectiveType = ['SINGLE', 'MULTIPLE', 'JUDGE', 'FILL'].includes(initialType)
  ? initialType
  : 'SINGLE'

const form = reactive({
  type: validInitialType,
  status: 1,
  content: '',
  answer: validInitialType === 'JUDGE' ? '对' : '',
  matchType: 'EXACT' as MatchType,
  score: validInitialType === 'JUDGE' ? 2 : 5,
})

const options = ref<string[]>(['', ''])
const optionsError = ref('')

const formErrors = reactive({
  content: '',
  answer: '',
  score: '',
})

const isChoice = computed(() => form.type === 'SINGLE' || form.type === 'MULTIPLE')
const isFill = computed(() => form.type === 'FILL')
const isJudge = computed(() => form.type === 'JUDGE')

/** 答案占位提示随题型变化。 */
const answerHint = computed(() => {
  if (form.type === 'SINGLE') return '如 A（单选填一个选项字母）'
  if (form.type === 'MULTIPLE') return '如 A,C（多选按字母排序、逗号分隔）'
  if (form.type === 'JUDGE') return '请点击下方选择 对 或 错'
  return '填空关键字或正则表达式'
})

function addOption() {
  options.value.push('')
}

function removeOption(index: number) {
  options.value.splice(index, 1)
}

// 切换题型时重置答案，避免残留不匹配的内容。
watch(
  () => form.type,
  (newType) => {
    if (newType === 'JUDGE') {
      form.answer = '对'
    } else {
      form.answer = ''
    }
    form.matchType = 'EXACT'
  },
)

async function loadQuestion() {
  if (!questionId.value) return
  loading.value = true
  pageError.value = ''
  try {
    const q = await objectiveApi.detail(questionId.value)
    form.type = q.type
    form.status = q.status
    form.content = q.content
    form.answer = q.answer ?? ''
    form.matchType = q.matchType ?? 'EXACT'
    form.score = q.score
    options.value = q.options.length > 0 ? [...q.options] : ['', '']
  } catch (e) {
    pageError.value = e instanceof ApiError ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  formErrors.content = form.content.trim() ? '' : '题干不能为空'
  formErrors.answer = form.answer.trim() ? '' : '答案不能为空'
  const s = form.score
  formErrors.score = s >= 1 && s <= 100 ? '' : '分值需在 1-100 之间'
  optionsError.value = ''
  if (isChoice.value) {
    const filled = options.value.filter((o) => o.trim() !== '')
    if (filled.length < 2) {
      optionsError.value = '选择题至少需要 2 个选项'
    }
  } else if (isJudge.value) {
    if (form.answer !== '对' && form.answer !== '错') {
      formErrors.answer = '判断题答案必须为对或错'
    }
  }
  return !formErrors.content && !formErrors.answer && !formErrors.score && !optionsError.value
}

async function save() {
  if (submitting.value || !validate()) return
  submitting.value = true
  pageError.value = ''
  try {
    const body = {
      type: form.type,
      status: form.status,
      content: form.content,
      answer: form.answer.trim(),
      matchType: isFill.value ? form.matchType : undefined,
      score: form.score,
      options: isJudge.value
        ? ['对', '错']
        : isChoice.value
          ? options.value.map((o) => o.trim()).filter((o) => o !== '')
          : undefined,
    }
    if (questionId.value) {
      await objectiveApi.update(questionId.value, body)
    } else {
      await objectiveApi.create(body)
    }
    toast.success(isEdit.value ? '题目已更新' : '题目已创建')
    router.push('/admin/objective-questions')
  } catch (e) {
    pageError.value = e instanceof ApiError ? e.message : '保存失败，请稍后重试'
    toast.error(pageError.value)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadQuestion()
})
</script>

<template>
  <div class="admin container">
    <RouterLink class="back" to="/admin/objective-questions">← 返回客观题管理</RouterLink>
    <h1 class="page-title">{{ isEdit ? '编辑客观题' : '新建客观题' }}</h1>

    <p v-if="loading" class="muted">加载中…</p>
    <p v-if="pageError" class="submit-error">{{ pageError }}</p>

    <div v-if="!loading" class="form-cards">
      <div class="card form-card">
        <h2 class="card-title">基本信息</h2>
        <div class="field-row">
          <FormField label="题型" required>
            <select v-model="form.type" class="input">
              <option value="SINGLE">单选</option>
              <option value="MULTIPLE">多选</option>
              <option value="JUDGE">判断</option>
              <option value="FILL">填空</option>
            </select>
          </FormField>
          <FormField label="状态">
            <select v-model="form.status" class="input">
              <option :value="1">公开</option>
              <option :value="0">私有</option>
            </select>
          </FormField>
          <FormField label="分值" :error="formErrors.score" hint="1-100">
            <input v-model.number="form.score" class="input" type="number" min="1" max="100" />
          </FormField>
        </div>
        <FormField label="题干（Markdown）" required :error="formErrors.content">
          <MarkdownEditor v-model="form.content" placeholder="支持 Markdown 语法" />
        </FormField>
      </div>

      <div v-if="isChoice" class="card form-card">
        <div class="card-head">
          <h2 class="card-title">选项</h2>
          <button class="btn btn-ghost btn-sm" type="button" @click="addOption">添加选项</button>
        </div>
        <p v-if="optionsError" class="field-hint-error">{{ optionsError }}</p>
        <div v-for="(_, index) in options" :key="index" class="option-row">
          <span class="option-letter">{{ String.fromCharCode(65 + index) }}</span>
          <input v-model="options[index]" class="input" :placeholder="`选项 ${String.fromCharCode(65 + index)} 内容`" />
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            :disabled="options.length <= 2"
            @click="removeOption(index)"
          >
            删除
          </button>
        </div>
      </div>

      <div class="card form-card">
        <h2 class="card-title">答案</h2>
        <div v-if="isJudge" class="judge-answer-picker">
          <label
            class="judge-answer-btn"
            :class="{ active: form.answer === '对' }"
            @click="form.answer = '对'"
          >
            <span class="judge-icon">✓</span>
            <span class="judge-text">对（正确）</span>
          </label>
          <label
            class="judge-answer-btn"
            :class="{ active: form.answer === '错' }"
            @click="form.answer = '错'"
          >
            <span class="judge-icon">✕</span>
            <span class="judge-text">错（错误）</span>
          </label>
        </div>
        <FormField v-else label="答案" required :error="formErrors.answer" :hint="answerHint">
          <input v-model="form.answer" class="input" :placeholder="answerHint" />
        </FormField>
        <FormField v-if="isFill" label="匹配方式">
          <select v-model="form.matchType" class="input">
            <option value="EXACT">精确匹配</option>
            <option value="IGNORE_CASE">忽略大小写</option>
            <option value="REGEX">正则表达式</option>
          </select>
        </FormField>
      </div>

      <div class="form-actions">
        <button class="btn btn-primary" type="button" :disabled="submitting" @click="save">
          {{ submitting ? '保存中…' : '保存' }}
        </button>
        <button class="btn btn-ghost" type="button" :disabled="submitting" @click="router.back()">
          取消
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin {
  padding-top: 32px;
  padding-bottom: 48px;
}
.page-title {
  margin: 28px 0 16px;
  font-size: 22px;
  font-weight: 600;
}
.form-cards {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.form-card {
  padding: 20px 22px;
}
.card-title {
  margin: 0 0 16px;
  font-size: 16px;
  font-weight: 600;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.card-head .card-title {
  margin: 0;
}
.field-row {
  display: flex;
  gap: 20px;
}
.field-row > * {
  flex: 1;
}
.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.option-letter {
  flex: none;
  width: 24px;
  text-align: center;
  font-weight: 600;
  color: var(--ink-secondary);
}
.field-hint-error {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--danger);
}
.form-actions {
  display: flex;
  gap: 12px;
}
.judge-answer-picker {
  display: flex;
  gap: 16px;
  margin-top: 8px;
}
.judge-answer-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 14px 20px;
  border: 1px solid var(--line);
  background: var(--surface);
  cursor: pointer;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  transition: all 0.15s ease;
}
.judge-answer-btn:hover {
  border-color: var(--ink-tertiary);
}
.judge-answer-btn.active {
  border-color: var(--ink);
  background: var(--ink);
  color: #fff;
}
.judge-icon {
  font-size: 18px;
  font-weight: 700;
}
</style>
