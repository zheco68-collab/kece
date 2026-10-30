<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { practiceApi } from '@/api/practice'
import { adminApi } from '@/api/admin'
import { ApiError } from '@/api/request'
import FormField from '@/components/FormField.vue'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'
import type { Question, DbQuestion, ObjectiveQuestion } from '@/types'
import type { PracticeQuestionItem } from '@/types/practice'

const router = useRouter()
const toast = useToast()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.hasRole('ADMIN'))

const submitting = ref(false)
const listLoading = ref(false)
const pageError = ref('')

const form = reactive({
  title: '',
  description: '',
  durationMinutes: 45,
})

/** 已选题目项 */
interface SelectedItem {
  questionId: number
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE'
  objectiveType?: 'SINGLE' | 'MULTIPLE' | 'JUDGE' | 'FILL'
  score: number
  title: string
}

const selected = ref<SelectedItem[]>([])
const formErrors = reactive({ title: '', questions: '' })
const titleInputRef = ref<HTMLInputElement | null>(null)

// ---- 题库选取 ----
type PickTab = 'ALGO' | 'DB' | 'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL' | 'ALL_OBJ'
const tab = ref<PickTab>('SINGLE')
const tabKeyword = ref('')
const algoList = ref<Question[]>([])
const dbList = ref<DbQuestion[]>([])
const objList = ref<ObjectiveQuestion[]>([])

const typeTabs: Array<{ value: PickTab; label: string }> = [
  { value: 'SINGLE', label: '单选题' },
  { value: 'JUDGE', label: '判断题' },
  { value: 'MULTIPLE', label: '多选题' },
  { value: 'FILL', label: '填空题' },
  { value: 'DB', label: '数据库 SQL' },
  { value: 'ALGO', label: '编程算法' },
  { value: 'ALL_OBJ', label: '全部客观题' },
]

async function loadSource() {
  listLoading.value = true
  try {
    const keyword = tabKeyword.value.trim() || undefined
    if (tab.value === 'ALGO') {
      algoList.value = (await adminApi.listQuestions({ keyword, page: 1, size: 100 })).list
    } else if (tab.value === 'DB') {
      dbList.value = (await adminApi.listDbQuestions({ keyword, page: 1, size: 100 })).list
    } else if (tab.value === 'ALL_OBJ') {
      objList.value = (await adminApi.listObjectiveQuestions({ keyword, page: 1, size: 100 })).list
    } else {
      // 单选、判断、多选、填空独立分类题库
      objList.value = (await adminApi.listObjectiveQuestions({ type: tab.value, keyword, page: 1, size: 100 })).list
    }
  } catch {
    pageError.value = '加载题库失败，请稍后重试'
  } finally {
    listLoading.value = false
  }
}

let tabKeywordTimer: ReturnType<typeof setTimeout> | null = null
watch(tabKeyword, () => {
  if (tabKeywordTimer !== null) clearTimeout(tabKeywordTimer)
  tabKeywordTimer = setTimeout(loadSource, 300)
})
watch(tab, loadSource)

interface SourceRowItem {
  id: number
  label: string
  type: string
  objectiveType?: 'SINGLE' | 'MULTIPLE' | 'JUDGE' | 'FILL'
  defaultScore: number
  tagText: string
  tagClass: string
}

function sourceRows(): SourceRowItem[] {
  if (tab.value === 'ALGO') {
    return algoList.value.map((q) => ({
      id: q.id,
      label: q.title,
      type: 'ALGO',
      defaultScore: 20,
      tagText: '算法',
      tagClass: 'badge-algo',
    }))
  }
  if (tab.value === 'DB') {
    return dbList.value.map((q) => ({
      id: q.id,
      label: q.title,
      type: 'DB',
      defaultScore: 20,
      tagText: 'SQL',
      tagClass: 'badge-db',
    }))
  }
  return objList.value.map((q) => {
    const text = q.content.length > 50 ? `${q.content.slice(0, 50)}…` : q.content
    const typeLabel = q.type === 'SINGLE' ? '单选' : q.type === 'JUDGE' ? '判断' : q.type === 'MULTIPLE' ? '多选' : '填空'
    const tagClass = `badge-${q.type.toLowerCase()}`
    const defaultScore = q.score || (q.type === 'JUDGE' ? 2 : 5)
    return {
      id: q.id,
      label: text,
      type: 'OBJECTIVE',
      objectiveType: q.type as any,
      defaultScore,
      tagText: typeLabel,
      tagClass,
    }
  })
}

function isSelected(questionId: number): boolean {
  const isObj = tab.value !== 'ALGO' && tab.value !== 'DB'
  if (isObj) {
    return selected.value.some((s) => s.questionId === questionId && s.questionType === 'OBJECTIVE')
  }
  return selected.value.some((s) => s.questionId === questionId && s.questionType === tab.value)
}

function addQuestion(row: SourceRowItem) {
  if (isSelected(row.id)) return
  const qType: 'ALGO' | 'DB' | 'OBJECTIVE' = (row.type === 'ALGO' || row.type === 'DB') ? row.type : 'OBJECTIVE'
  selected.value.push({
    questionId: row.id,
    questionType: qType,
    objectiveType: row.objectiveType,
    score: row.defaultScore,
    title: row.label,
  })
}

function removeQuestion(index: number) {
  selected.value.splice(index, 1)
}

function moveQuestion(index: number, dir: -1 | 1) {
  const target = index + dir
  if (target < 0 || target >= selected.value.length) return
  const [item] = selected.value.splice(index, 1)
  selected.value.splice(target, 0, item)
}

const stats = computed(() => {
  const res = {
    single: { count: 0, score: 0 },
    judge: { count: 0, score: 0 },
    multiple: { count: 0, score: 0 },
    fill: { count: 0, score: 0 },
    db: { count: 0, score: 0 },
    algo: { count: 0, score: 0 },
    totalScore: 0,
    totalCount: selected.value.length,
  }
  for (const s of selected.value) {
    const sc = Number(s.score) || 0
    res.totalScore += sc
    if (s.questionType === 'ALGO') {
      res.algo.count++
      res.algo.score += sc
    } else if (s.questionType === 'DB') {
      res.db.count++
      res.db.score += sc
    } else {
      const ot = s.objectiveType
      if (ot === 'JUDGE' || s.title.startsWith('[判断]')) {
        res.judge.count++
        res.judge.score += sc
      } else if (ot === 'MULTIPLE' || s.title.startsWith('[多选]')) {
        res.multiple.count++
        res.multiple.score += sc
      } else if (ot === 'FILL' || s.title.startsWith('[填空]')) {
        res.fill.count++
        res.fill.score += sc
      } else {
        res.single.count++
        res.single.score += sc
      }
    }
  }
  return res
})

function getSelectedBadge(s: SelectedItem) {
  if (s.questionType === 'ALGO') return { text: '编程算法', class: 'badge-algo' }
  if (s.questionType === 'DB') return { text: '数据库 SQL', class: 'badge-db' }
  const ot = s.objectiveType
  if (ot === 'JUDGE' || s.title.startsWith('[判断]')) return { text: '判断题', class: 'badge-judge' }
  if (ot === 'MULTIPLE' || s.title.startsWith('[多选]')) return { text: '多选题', class: 'badge-multiple' }
  if (ot === 'FILL' || s.title.startsWith('[填空]')) return { text: '填空题', class: 'badge-fill' }
  return { text: '单选题', class: 'badge-single' }
}

function autoSortByStandards() {
  const getOrderWeight = (s: SelectedItem) => {
    if (s.questionType === 'OBJECTIVE') {
      const ot = s.objectiveType
      if (ot === 'SINGLE' || s.title.startsWith('[单选]')) return 1
      if (ot === 'JUDGE' || s.title.startsWith('[判断]')) return 2
      if (ot === 'MULTIPLE' || s.title.startsWith('[多选]')) return 3
      if (ot === 'FILL' || s.title.startsWith('[填空]')) return 4
      return 1
    }
    if (s.questionType === 'DB') return 5
    return 6
  }
  selected.value.sort((a, b) => getOrderWeight(a) - getOrderWeight(b))
  toast.success('已按标准课练题型顺序自动排布（单选→判断→多选→填空→SQL→编程）')
}

const totalScore = computed(() => stats.value.totalScore)

async function save() {
  formErrors.title = form.title.trim() ? '' : '请填写课练标题'
  formErrors.questions = selected.value.length > 0 ? '' : '请至少选择一道题目'

  if (formErrors.title) {
    toast.error('请填写课练标题')
    titleInputRef.value?.focus()
    titleInputRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    return
  }

  if (formErrors.questions) {
    toast.error(formErrors.questions)
    return
  }

  if (!form.durationMinutes || form.durationMinutes < 5 || form.durationMinutes > 300) {
    const msg = '课上时长需在 5 至 300 分钟之间'
    pageError.value = msg
    toast.error(msg)
    return
  }

  if (selected.value.some((s) => !s.score || s.score <= 0)) {
    formErrors.questions = '每题分值需大于 0'
    toast.error('每题分值需大于 0，请检查已选题目分值')
    return
  }

  submitting.value = true
  pageError.value = ''
  try {
    const questionsPayload: PracticeQuestionItem[] = selected.value.map((s, idx) => ({
      questionId: s.questionId,
      questionType: s.questionType,
      title: s.title,
      score: s.score,
      sortOrder: idx + 1,
    }))

    const res = await practiceApi.create({
      title: form.title.trim(),
      description: form.description.trim() || undefined,
      durationMinutes: form.durationMinutes,
      questions: questionsPayload,
    })

    toast.success(`课堂练习发起成功！6 位上机口令：${res.accessCode}`)
    router.push('/admin/practices')
  } catch (e) {
    const errorMsg =
      e instanceof ApiError
        ? e.message
        : e instanceof Error
          ? e.message
          : '发起课练失败，请重试'
    pageError.value = errorMsg
    toast.error(errorMsg)
  } finally {
    submitting.value = false
  }
}

onMounted(loadSource)
</script>

<template>
  <div class="practice-edit container">
    <h1 class="page-title">发起课堂练习</h1>

    <p v-if="pageError" class="submit-error">{{ pageError }}</p>

    <!-- 基本信息卡片 -->
    <div class="form card">
      <FormField label="练习标题" :error="formErrors.title">
        <input
          ref="titleInputRef"
          v-model="form.title"
          class="input"
          type="text"
          placeholder="例如：第4周上机实验：动态规划与SQL分析实战"
        />
      </FormField>

      <FormField label="练习说明（Markdown）">
        <textarea
          v-model="form.description"
          class="input"
          rows="3"
          placeholder="可选，提示学生本次上机考核范围、重点算法或课堂注意事项..."
        ></textarea>
      </FormField>

      <FormField label="课上时长（分钟）">
        <input
          v-model.number="form.durationMinutes"
          class="input num"
          type="number"
          min="5"
          max="300"
          placeholder="45"
        />
      </FormField>
    </div>

    <!-- 组卷：选择题库 -->
    <div class="picker card">
      <h2 class="section-title">
        题库选择
        <span class="scope-tip" :class="isAdmin ? 'admin' : 'teacher'">
          {{ isAdmin ? '管理员权限 · 全站题库无限制' : '教师权限 · 仅可选择本人创建的题目' }}
        </span>
      </h2>
      <div class="tabs">
        <button
          v-for="t in typeTabs"
          :key="t.value"
          class="tab"
          :class="{ active: tab === t.value }"
          type="button"
          @click="tab = t.value"
        >
          {{ t.label }}
        </button>
        <input
          v-model="tabKeyword"
          class="input search"
          type="text"
          placeholder="搜索该题型题目…"
        />
      </div>

      <div v-if="listLoading" class="state-card"><div class="loading-spinner"></div><p class="muted">正在加载题库…</p></div>
      <ul v-else class="source-list">
        <li
          v-for="row in sourceRows()"
          :key="`${tab}-${row.id}`"
          class="source-row"
        >
          <span class="type-pill" :class="row.tagClass">{{ row.tagText }}</span>
          <span class="source-label">{{ row.label }}</span>
          <span class="source-score-hint">建议 {{ row.defaultScore }} 分</span>
          <button
            class="btn btn-ghost btn-sm"
            type="button"
            :disabled="isSelected(row.id)"
            @click="addQuestion(row)"
          >
            {{ isSelected(row.id) ? '已在卷中' : '+ 添加' }}
          </button>
        </li>
        <li v-if="sourceRows().length === 0" class="muted empty">
          {{ isAdmin ? '该题型暂无可选题库' : '暂无可用题目（教师仅可选择本人创建的题目）' }}
        </li>
      </ul>
    </div>

    <!-- 已选题目清单 -->
    <div class="chosen card">
      <div class="chosen-header-row">
        <h2 class="section-title">
          已选题目（共 {{ selected.length }} 题 · 满分 {{ totalScore }} 分）
          <span v-if="formErrors.questions" class="error-text">{{ formErrors.questions }}</span>
        </h2>
        <button
          v-if="selected.length > 1"
          class="btn btn-outline-sort btn-sm"
          type="button"
          title="按单选、判断、多选、填空、SQL、编程规范顺序重新排布"
          @click="autoSortByStandards"
        >
          ⚡ 按标准题型顺序自动排布
        </button>
      </div>

      <!-- 题型统计横幅 -->
      <div v-if="selected.length > 0" class="subtotal-bar">
        <span v-if="stats.single.count > 0" class="sub-pill single">单选: {{ stats.single.count }}题 ({{ stats.single.score }}分)</span>
        <span v-if="stats.judge.count > 0" class="sub-pill judge">判断: {{ stats.judge.count }}题 ({{ stats.judge.score }}分)</span>
        <span v-if="stats.multiple.count > 0" class="sub-pill multiple">多选: {{ stats.multiple.count }}题 ({{ stats.multiple.score }}分)</span>
        <span v-if="stats.fill.count > 0" class="sub-pill fill">填空: {{ stats.fill.count }}题 ({{ stats.fill.score }}分)</span>
        <span v-if="stats.db.count > 0" class="sub-pill db">SQL: {{ stats.db.count }}题 ({{ stats.db.score }}分)</span>
        <span v-if="stats.algo.count > 0" class="sub-pill algo">编程: {{ stats.algo.count }}题 ({{ stats.algo.score }}分)</span>
      </div>

      <ul class="chosen-list">
        <li v-for="(s, i) in selected" :key="i" class="chosen-row">
          <span class="chosen-order mono">#{{ i + 1 }}</span>
          <span class="type-pill" :class="getSelectedBadge(s).class">
            {{ getSelectedBadge(s).text }}
          </span>
          <span class="chosen-label">{{ s.title }}</span>
          <span class="chosen-score">
            <input
              v-model.number="s.score"
              class="input score-input"
              type="number"
              min="1"
            />
            分
          </span>
          <button
            class="btn btn-ghost btn-sm btn-icon"
            type="button"
            title="上移"
            :disabled="i === 0"
            @click="moveQuestion(i, -1)"
          >
            ↑
          </button>
          <button
            class="btn btn-ghost btn-sm btn-icon"
            type="button"
            title="下移"
            :disabled="i === selected.length - 1"
            @click="moveQuestion(i, 1)"
          >
            ↓
          </button>
          <button
            class="btn btn-ghost btn-sm btn-danger"
            type="button"
            @click="removeQuestion(i)"
          >
            移除
          </button>
        </li>
        <li v-if="selected.length === 0" class="muted empty">尚未选择题目，请在上方按类别添加题目</li>
      </ul>
    </div>

    <!-- 底部操作按钮 -->
    <div class="actions">
      <p v-if="pageError || formErrors.title || formErrors.questions" class="action-error-hint">
        ⚠ {{ pageError || formErrors.title || formErrors.questions }}
      </p>
      <div class="action-buttons">
        <button class="btn btn-ghost" type="button" @click="router.push('/admin/practices')">
          取消
        </button>
        <button class="btn btn-primary" type="button" :disabled="submitting" @click="save">
          {{ submitting ? '生成口令中…' : '发起课堂练习并生成口令 →' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.practice-edit {
  padding-top: 32px;
}

.page-title {
  margin: 28px 0 8px;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.5px;
}

.form,
.picker,
.chosen {
  padding: 16px;
  margin-bottom: 16px;
}

.field-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.num {
  max-width: 240px;
}

.section-title {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}

.tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.tab {
  padding: 6px 14px;
  border: 1px solid var(--line);
  border-radius: var(--pill);
  background: var(--surface);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tab.active {
  border-color: var(--ink);
  background: var(--ink);
  color: #fff;
}

.search {
  width: 240px;
  margin-left: auto;
}

.source-list,
.chosen-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.source-row,
.chosen-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.source-label,
.chosen-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13.5px;
}

.chosen-order {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ink);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
}

.type-pill {
  padding: 1px 7px;
  font-size: 11px;
  font-weight: 700;
  border: 1px solid var(--line);
  border-radius: 0;
  white-space: nowrap;
}

.pill-algo {
  color: #2b8a3e;
  background: #ebfbee;
  border-color: #b2f2bb;
}

.pill-db {
  color: #111111;
  background: #f1f3f5;
  border-color: #ced4da;
}

.pill-objective {
  color: #7048e8;
  background: #f8f0fc;
  border-color: #d0bfff;
}

.chosen-score {
  color: var(--ink-tertiary);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.score-input {
  width: 64px;
}

.empty {
  padding: 12px 0;
  font-size: 13px;
}

.error-text {
  color: var(--danger);
  font-size: 13px;
  font-weight: 400;
  margin-left: 8px;
}

.actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
  padding-bottom: 40px;
}

.action-buttons {
  display: flex;
  align-items: center;
  gap: 10px;
}

.action-error-hint {
  margin: 0;
  color: var(--danger);
  font-size: 13.5px;
  font-weight: 500;
  background: var(--danger-bg);
  padding: 6px 14px;
  border: 1px solid var(--danger);
  border-radius: var(--radius);
}

.btn-icon {
  width: 28px;
  padding: 0;
}

.btn-danger {
  color: var(--danger);
}

.btn-danger:hover {
  background: var(--danger-bg);
  border-color: var(--danger);
}

.submit-error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}

.scope-tip {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  margin-left: 10px;
  vertical-align: middle;
  letter-spacing: 0.5px;
}

.scope-tip.teacher {
  background: #2b8a3e;
  color: #fff;
}

.scope-tip.admin {
  background: #7048e8;
  color: #fff;
}

/* 题型与统计栏样式 */
.chosen-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}

.btn-outline-sort {
  background: #fff;
  border: 1px solid #1e1e1e;
  color: #1e1e1e;
  font-weight: 700;
  cursor: pointer;
  padding: 5px 12px;
  font-size: 12px;
}

.btn-outline-sort:hover {
  background: #1e1e1e;
  color: #fff;
}

.subtotal-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  background: #f8f9fa;
  border: 1px dashed #ced4da;
  margin-bottom: 14px;
}

.sub-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  background: #fff;
  border: 1px solid #dee2e6;
}

.sub-pill.single { border-color: #74c0fc; color: #1971c2; }
.sub-pill.judge { border-color: #8ce99a; color: #2b8a3e; }
.sub-pill.multiple { border-color: #b197fc; color: #7950f2; }
.sub-pill.fill { border-color: #ffd43b; color: #f59f00; }
.sub-pill.db { border-color: #63e6be; color: #0ca678; }
.sub-pill.algo { border-color: #4dabf7; color: #1864ab; }

.badge-single { background: #e7f5ff; color: #1971c2; }
.badge-judge { background: #ebfbee; color: #2b8a3e; }
.badge-multiple { background: #f3f0ff; color: #7950f2; }
.badge-fill { background: #fff9db; color: #f59f00; }
.badge-db { background: #e6fcf5; color: #0ca678; }
.badge-algo { background: #e7f5ff; color: #1864ab; }

.source-score-hint {
  font-size: 11px;
  color: #868e96;
  white-space: nowrap;
  flex-shrink: 0;
}
</style>

