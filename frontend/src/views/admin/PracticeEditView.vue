<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { practiceApi } from '@/api/practice'
import { ApiError } from '@/api/request'
import Pagination from '@/components/Pagination.vue'
import { useToast } from '@/composables/useToast'
import { useQuestionCandidates } from '@/shared/composables/useQuestionCandidates'
import { useUserStore } from '@/stores/user'
import type { PracticeQuestionItem } from '@/types/practice'

const route = useRoute()
const router = useRouter()
const editId = computed(() => (route.params.id ? Number(route.params.id) : null))
const isEdit = computed(() => editId.value !== null)
const toast = useToast()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.hasRole('ADMIN'))

const submitting = ref(false)
const pageError = ref('')

const form = reactive({
  title: '',
  description: '',
  durationMinutes: 45,
})

const durationPresets = [0, 15, 30, 45, 60, 90, 120]

/** 已选题目项 */
interface SelectedItem {
  questionId: number
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE'
  objectiveType?: 'SINGLE' | 'MULTIPLE' | 'JUDGE' | 'FILL'
  difficulty?: string
  score: number
  title: string
}

const selected = ref<SelectedItem[]>([])
const formErrors = reactive({ title: '', questions: '' })
const titleInputRef = ref<HTMLInputElement | null>(null)

// 批量快速赋分
const batchObjScore = ref(5)
const batchPracScore = ref(20)

// ---- 题库选取 ----
type PickTab = 'SINGLE' | 'JUDGE' | 'MULTIPLE' | 'FILL' | 'DB' | 'ALGO' | 'ALL_OBJ'
const tab = ref<PickTab>('SINGLE')
const {
  keyword: tabKeyword,
  page: candidatePage,
  size: candidatePageSize,
  total: candidateTotal,
  candidates,
  loading: listLoading,
  error: candidateError,
  load: loadCandidates,
  cleanup: cleanupCandidates,
} = useQuestionCandidates({
  category: tab,
  defaultSize: 20,
})

const typeTabs: Array<{ value: PickTab; label: string }> = [
  { value: 'SINGLE', label: '单选题' },
  { value: 'JUDGE', label: '判断题' },
  { value: 'MULTIPLE', label: '多选题' },
  { value: 'FILL', label: '填空题' },
  { value: 'DB', label: '数据库 SQL' },
  { value: 'ALGO', label: '编程算法' },
  { value: 'ALL_OBJ', label: '全部客观题' },
]

function getItemBadge(diffOrType?: string, qType?: 'ALGO' | 'DB' | 'OBJECTIVE') {
  const d = (diffOrType || '').toUpperCase()
  if (d === 'EASY') return { label: '简单', cls: 'pill-easy' }
  if (d === 'MEDIUM') return { label: '中等', cls: 'pill-medium' }
  if (d === 'HARD') return { label: '困难', cls: 'pill-hard' }

  if (d === 'SELECT') return { label: 'SELECT 查询', cls: 'pill-select' }
  if (d === 'DML') return { label: 'DML 操作', cls: 'pill-dml' }

  if (d === 'SINGLE') return { label: '单选题', cls: 'pill-single' }
  if (d === 'MULTIPLE') return { label: '多选题', cls: 'pill-multiple' }
  if (d === 'JUDGE') return { label: '判断题', cls: 'pill-judge' }
  if (d === 'FILL') return { label: '填空题', cls: 'pill-fill' }

  if (qType === 'DB') return { label: 'SQL', cls: 'pill-select' }
  if (qType === 'OBJECTIVE') return { label: '客观题', cls: 'pill-single' }
  return { label: '编程', cls: 'pill-medium' }
}

async function loadSource() {
  await loadCandidates(tab.value, 1)
}

let tabKeywordTimer: ReturnType<typeof setTimeout> | null = null
watch(tabKeyword, () => {
  if (tabKeywordTimer !== null) clearTimeout(tabKeywordTimer)
  tabKeywordTimer = setTimeout(loadSource, 260)
})
watch(tab, loadSource)

onBeforeUnmount(() => {
  if (tabKeywordTimer !== null) clearTimeout(tabKeywordTimer)
  cleanupCandidates()
})

interface SourceRowItem {
  id: number
  label: string
  type: 'ALGO' | 'DB' | 'OBJECTIVE'
  objectiveType?: 'SINGLE' | 'MULTIPLE' | 'JUDGE' | 'FILL'
  difficulty: string
  defaultScore: number
}

function sourceRows(): SourceRowItem[] {
  return candidates.value.map((candidate) => ({
    id: candidate.id,
    label:
      candidate.type === 'OBJECTIVE' && candidate.title.length > 56
        ? `${candidate.title.slice(0, 56)}…`
        : candidate.title,
    type: candidate.type,
    objectiveType: candidate.subType,
    difficulty: candidate.difficulty || candidate.subType || 'MEDIUM',
    defaultScore: candidate.defaultScore ?? (candidate.subType === 'JUDGE' ? 2 : 5),
  }))
}

function goCandidatePage(nextPage: number) {
  void loadCandidates(undefined, nextPage)
}

function isSelected(row: SourceRowItem): boolean {
  return selected.value.some((s) => s.questionId === row.id && s.questionType === row.type)
}

function toggleSelectQuestion(row: SourceRowItem) {
  const idx = selected.value.findIndex(
    (s) => s.questionId === row.id && s.questionType === row.type,
  )
  if (idx >= 0) {
    selected.value.splice(idx, 1)
    return
  }
  selected.value.push({
    questionId: row.id,
    questionType: row.type,
    objectiveType: row.objectiveType,
    difficulty: row.difficulty,
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

const sectionStats = computed(() => {
  let objCount = 0
  let objScore = 0
  let dbCount = 0
  let dbScore = 0
  let algoCount = 0
  let algoScore = 0

  for (const s of selected.value) {
    const sc = Number(s.score) || 0
    if (s.questionType === 'OBJECTIVE') {
      objCount++
      objScore += sc
    } else if (s.questionType === 'DB') {
      dbCount++
      dbScore += sc
    } else {
      algoCount++
      algoScore += sc
    }
  }
  return {
    objCount,
    objScore,
    dbCount,
    dbScore,
    algoCount,
    algoScore,
    totalScore: objScore + dbScore + algoScore,
  }
})

function autoSortByStandards() {
  const getOrderWeight = (s: SelectedItem) => {
    if (s.questionType === 'OBJECTIVE') {
      const ot = (s.objectiveType || s.difficulty || '').toUpperCase()
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
  toast.success('已按「01 客观题 → 02 数据库 SQL → 03 算法编程」标准章节顺序重排')
}

function applyBatchObjScore() {
  const val = Math.max(1, Number(batchObjScore.value) || 5)
  let count = 0
  for (const s of selected.value) {
    if (s.questionType === 'OBJECTIVE') {
      s.score = val
      count++
    }
  }
  if (count > 0) {
    toast.success(`已将 ${count} 道客观题分值统一设为 ${val} 分`)
  } else {
    toast.info('当前试卷中暂无客观题')
  }
}

function applyBatchPracScore() {
  const val = Math.max(1, Number(batchPracScore.value) || 20)
  let count = 0
  for (const s of selected.value) {
    if (s.questionType === 'ALGO' || s.questionType === 'DB') {
      s.score = val
      count++
    }
  }
  if (count > 0) {
    toast.success(`已将 ${count} 道实操题分值统一设为 ${val} 分`)
  } else {
    toast.info('当前试卷中暂无 SQL 或算法实操题')
  }
}

async function save() {
  formErrors.title = form.title.trim() ? '' : '请填写课练标题'
  formErrors.questions = selected.value.length > 0 ? '' : '请至少选择一道题目'

  if (formErrors.title) {
    toast.error('请填写课练标题')
    titleInputRef.value?.focus()
    return
  }

  if (formErrors.questions) {
    toast.error(formErrors.questions)
    return
  }

  const dm = Number(form.durationMinutes)
  if (Number.isNaN(dm) || (dm !== 0 && (dm < 5 || dm > 300))) {
    const msg = '课上时长需设为 0（不限时）或 5 至 300 分钟之间'
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

    if (isEdit.value && editId.value) {
      await practiceApi.update(editId.value, {
        title: form.title.trim(),
        description: form.description.trim() || undefined,
        durationMinutes: dm,
        questions: questionsPayload,
      })
      toast.success('课堂练习配置与题目已无损更新')
      router.push(`/admin/practices/${editId.value}/overview`)
    } else {
      const res = await practiceApi.create({
        title: form.title.trim(),
        description: form.description.trim() || undefined,
        durationMinutes: dm,
        questions: questionsPayload,
      })
      toast.success(`课堂练习发起成功！6 位上机口令：${res.accessCode}`)
      router.push('/admin/practices')
    }
  } catch (e) {
    const errorMsg =
      e instanceof ApiError
        ? e.message
        : e instanceof Error
          ? e.message
          : '保存课练失败，请重试'
    pageError.value = errorMsg
    toast.error(errorMsg)
  } finally {
    submitting.value = false
  }
}

async function loadExistingPractice() {
  if (!editId.value) return
  try {
    const res = await practiceApi.overview(editId.value)
    form.title = res.title
    form.description = res.description || ''
    form.durationMinutes = res.durationMinutes ?? 45
    if (res.questionAccuracies) {
      selected.value = res.questionAccuracies.map((q) => ({
        questionId: q.questionId,
        questionType: q.questionType as 'ALGO' | 'DB' | 'OBJECTIVE',
        objectiveType:
          q.questionType === 'OBJECTIVE' && q.subType
            ? (q.subType as 'SINGLE' | 'MULTIPLE' | 'JUDGE' | 'FILL')
            : undefined,
        difficulty: q.subType || undefined,
        score: q.fullScore,
        title: q.title,
      }))
    }
  } catch {
    pageError.value = '加载已有课练信息失败'
  }
}

onMounted(async () => {
  await loadSource()
  if (isEdit.value) {
    await loadExistingPractice()
  }
})
</script>

<template>
  <div class="practice-composer container">
    <!-- 面包屑 -->
    <nav class="nav-trail">
      <RouterLink to="/admin/practices">随堂课练管理</RouterLink>
      <span class="sep">/</span>
      <span class="current">{{ isEdit ? '编辑课堂练习' : '发起课堂练习' }}</span>
    </nav>

    <!-- 顶栏：标题 + 主操作按钮 -->
    <div class="page-head">
      <div class="head-left">
        <h1 class="page-title">{{ isEdit ? '编辑随堂课练' : '随堂课练组卷工作台' }}</h1>
        <p class="muted head-sub">
          左侧检索题库点选入卷 · 右侧按「客观 / SQL / 算法」实时预览与批量赋分
        </p>
      </div>

      <div class="head-actions">
        <button class="btn btn-ghost" type="button" @click="router.push('/admin/practices')">
          取消返回
        </button>
        <button class="btn btn-primary" type="button" :disabled="submitting" @click="save">
          {{
            submitting
              ? '保存中…'
              : isEdit
                ? '保存修改 →'
                : '发起课练并生成口令 →'
          }}
        </button>
      </div>
    </div>

    <p v-if="pageError" class="error-banner">{{ pageError }}</p>

    <!-- 顶部紧凑基础配置栏 -->
    <div class="config-bar card">
      <div class="config-col">
        <label class="field-label">
          课练标题 <span class="req">*</span>
          <span v-if="formErrors.title" class="field-err">{{ formErrors.title }}</span>
        </label>
        <input
          ref="titleInputRef"
          v-model="form.title"
          class="input"
          type="text"
          placeholder="例如：第04次实验课：树与二叉树专项随堂课练"
        />

        <label class="field-label mt-8">课堂说明（可选）</label>
        <input
          v-model="form.description"
          class="input"
          type="text"
          placeholder="简要说明本次上机考核范围、重点算法或课堂注意事项…"
        />
      </div>

      <div class="config-col">
        <label class="field-label">课堂限时（分钟） <span class="req">*</span></label>
        <div class="duration-row">
          <input
            v-model.number="form.durationMinutes"
            class="input duration-input mono"
            type="number"
            min="0"
            max="300"
            placeholder="45"
          />
          <span class="duration-unit muted">分钟（0 代表不限时，或设 5 ~ 300 分钟）</span>
        </div>

        <label class="field-label mt-8">快捷时长预设</label>
        <div class="preset-row">
          <button
            v-for="mins in durationPresets"
            :key="mins"
            type="button"
            class="preset-btn mono"
            :class="{ active: form.durationMinutes === mins }"
            @click="form.durationMinutes = mins"
          >
            {{ mins === 0 ? '不限时' : `${mins}m` }}
          </button>
        </div>
      </div>
    </div>

    <!-- 左右双栏组卷工作台 -->
    <div class="composer-workbench">
      <!-- 左栏：题库检索与挑选池 -->
      <section class="workbench-panel card">
        <div class="panel-head">
          <div class="panel-head-left">
            <h2 class="panel-title">题库检索池</h2>
            <span class="scope-badge mono">
              {{ isAdmin ? '管理员 · 全站题库' : '教师 · 本人题库' }}
            </span>
          </div>

          <div class="panel-search-wrap">
            <input
              v-model="tabKeyword"
              class="input panel-search"
              type="search"
              placeholder="搜索当前题型题目…"
            />
          </div>
        </div>

        <div class="type-tabs-bar">
          <button
            v-for="t in typeTabs"
            :key="t.value"
            type="button"
            class="type-tab-btn"
            :class="{ active: tab === t.value }"
            @click="tab = t.value"
          >
            {{ t.label }}
          </button>
        </div>

        <div v-if="listLoading" class="loading-box muted">
          <span class="loading-spin"></span>
          <span>正在加载题库…</span>
        </div>
        <div v-else-if="candidateError" class="loading-box muted">
          <span>{{ candidateError }}</span>
          <button type="button" class="btn btn-ghost btn-sm" @click="loadSource">重试</button>
        </div>
        <ul v-else class="bank-list">
          <li
            v-for="row in sourceRows()"
            :key="`${row.type}-${row.id}`"
            class="bank-row"
            :class="{ 'is-picked': isSelected(row) }"
            @click="toggleSelectQuestion(row)"
          >
            <div class="bank-row-main">
              <span class="q-id mono">#{{ row.id }}</span>
              <span
                class="difficulty-pill"
                :class="getItemBadge(row.difficulty, row.type).cls"
              >
                {{ getItemBadge(row.difficulty, row.type).label }}
              </span>
              <span class="q-label" :title="row.label">{{ row.label }}</span>
            </div>

            <div class="bank-row-right">
              <span class="score-hint mono">{{ row.defaultScore }}分</span>
              <button
                type="button"
                class="pick-btn"
                :class="{ picked: isSelected(row) }"
                @click.stop="toggleSelectQuestion(row)"
              >
                {{ isSelected(row) ? '✓ 已选' : '+ 选入' }}
              </button>
            </div>
          </li>
          <li v-if="sourceRows().length === 0" class="panel-empty muted">
            {{
              isAdmin
                ? '该分类下暂无可选题目'
                : '暂无可用题目（教师仅可选择本人创建的题目）'
            }}
          </li>
        </ul>
        <Pagination
          v-if="candidateTotal > candidatePageSize"
          compact
          :page="candidatePage"
          :size="candidatePageSize"
          :total="candidateTotal"
          :disabled="listLoading"
          @update:page="goCandidatePage"
        />
      </section>

      <!-- 右栏：实时试卷结构预览与赋分台 -->
      <section class="workbench-panel sticky-panel card">
        <div class="panel-head">
          <div class="panel-head-left">
            <h2 class="panel-title">
              已选试卷结构
              <span class="mono">
                ({{ selected.length }} 题 · 满分 {{ sectionStats.totalScore }} 分)
              </span>
            </h2>
          </div>

          <button
            v-if="selected.length > 1"
            type="button"
            class="btn btn-ghost btn-sm sort-btn"
            title="按 01客观 → 02SQL → 03算法 标准顺序自动重排"
            @click="autoSortByStandards"
          >
            ⚡ 标准章节排序
          </button>
        </div>

        <!-- 章节统计与批量赋分条 -->
        <div v-if="selected.length > 0" class="batch-bar">
          <div class="sec-summary-row mono">
            <span v-if="sectionStats.objCount > 0" class="sec-chip">
              01 客观: {{ sectionStats.objCount }}题 ({{ sectionStats.objScore }}分)
            </span>
            <span v-if="sectionStats.dbCount > 0" class="sec-chip">
              02 SQL: {{ sectionStats.dbCount }}题 ({{ sectionStats.dbScore }}分)
            </span>
            <span v-if="sectionStats.algoCount > 0" class="sec-chip">
              03 算法: {{ sectionStats.algoCount }}题 ({{ sectionStats.algoScore }}分)
            </span>
          </div>

          <div class="batch-score-ops">
            <div v-if="sectionStats.objCount > 0" class="batch-group">
              <span class="batch-label">客观题统一</span>
              <input
                v-model.number="batchObjScore"
                type="number"
                min="1"
                class="batch-input mono"
              />
              <button type="button" class="batch-apply-btn" @click="applyBatchObjScore">
                设分
              </button>
            </div>
            <div v-if="sectionStats.dbCount + sectionStats.algoCount > 0" class="batch-group">
              <span class="batch-label">实操题统一</span>
              <input
                v-model.number="batchPracScore"
                type="number"
                min="1"
                class="batch-input mono"
              />
              <button type="button" class="batch-apply-btn" @click="applyBatchPracScore">
                设分
              </button>
            </div>
          </div>
        </div>

        <div v-if="selected.length === 0" class="panel-empty muted">
          尚未选择题目，请在左侧题库检索池点击「+ 选入」组卷
          <p v-if="formErrors.questions" class="field-err mt-8">{{ formErrors.questions }}</p>
        </div>

        <ul v-else class="paper-list">
          <li
            v-for="(s, i) in selected"
            :key="`${s.questionType}-${s.questionId}`"
            class="paper-row"
          >
            <div class="paper-row-main">
              <span class="paper-order mono">#{{ String(i + 1).padStart(2, '0') }}</span>
              <span
                class="difficulty-pill"
                :class="getItemBadge(s.difficulty || s.objectiveType, s.questionType).cls"
              >
                {{ getItemBadge(s.difficulty || s.objectiveType, s.questionType).label }}
              </span>
              <span class="paper-title" :title="s.title">{{ s.title }}</span>
            </div>

            <div class="paper-row-ops">
              <div class="score-box">
                <input
                  v-model.number="s.score"
                  type="number"
                  min="1"
                  class="score-input mono"
                />
                <span class="score-unit">分</span>
              </div>

              <button
                type="button"
                class="icon-op-btn"
                title="上移"
                :disabled="i === 0"
                @click="moveQuestion(i, -1)"
              >
                ↑
              </button>
              <button
                type="button"
                class="icon-op-btn"
                title="下移"
                :disabled="i === selected.length - 1"
                @click="moveQuestion(i, 1)"
              >
                ↓
              </button>
              <button
                type="button"
                class="icon-op-btn danger"
                title="移除题目"
                @click="removeQuestion(i)"
              >
                ×
              </button>
            </div>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.practice-composer {
  padding-top: 24px;
  padding-bottom: 72px;
}

.mono {
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.nav-trail {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  font-size: 12.5px;
  color: var(--ink-tertiary);
}
.nav-trail a {
  color: var(--ink-secondary);
  text-decoration: none;
}
.nav-trail a:hover {
  color: var(--ink);
}
.nav-trail .current {
  color: var(--ink);
  font-weight: 600;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.head-sub {
  margin: 0;
  font-size: 13.5px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.error-banner {
  padding: 10px 14px;
  margin-bottom: 16px;
  background: var(--danger-bg);
  border: 1px solid var(--danger);
  color: var(--danger);
  font-size: 13px;
  font-weight: 500;
}

/* 顶部紧凑基础配置栏 */
.config-bar {
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  gap: 24px;
  padding: 16px 20px;
  margin-bottom: 18px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
}
.config-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary);
}
.mt-8 {
  margin-top: 6px;
}
.req {
  color: var(--danger);
}
.field-err {
  color: var(--danger);
  font-weight: 500;
  margin-left: 6px;
}
.duration-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.duration-input {
  width: 110px;
  font-weight: 700;
}
.duration-unit {
  font-size: 12.5px;
}
.preset-row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.preset-btn {
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--ink-secondary);
  cursor: pointer;
  border-radius: 0;
  transition: all 0.12s ease;
}
.preset-btn:hover,
.preset-btn.active {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
}

/* 左右双栏组卷工作台 */
.composer-workbench {
  display: grid;
  grid-template-columns: 1.18fr 1fr;
  gap: 18px;
  align-items: start;
}
.workbench-panel {
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
  overflow: hidden;
}
.sticky-panel {
  position: sticky;
  top: 20px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}
.panel-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.panel-title {
  margin: 0;
  font-size: 14.5px;
  font-weight: 700;
}
.scope-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}
.panel-search-wrap {
  width: 200px;
}
.panel-search {
  width: 100%;
  height: 30px;
  font-size: 12.5px;
}

.type-tabs-bar {
  display: flex;
  gap: 6px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
  flex-wrap: wrap;
}
.type-tab-btn {
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--ink-secondary);
  cursor: pointer;
  border-radius: 0;
  transition: all 0.12s ease;
}
.type-tab-btn:hover {
  border-color: var(--ink);
  color: var(--ink);
}
.type-tab-btn.active {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
  font-weight: 600;
}

/* 左栏题库列表 */
.bank-list,
.paper-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 540px;
  overflow-y: auto;
}
.bank-row,
.paper-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
  transition: background 0.12s ease;
}
.bank-row {
  cursor: pointer;
}
.bank-row:hover {
  background: var(--bg);
}
.bank-row.is-picked {
  background: #f8fafc;
}
.bank-row:last-child,
.paper-row:last-child {
  border-bottom: none;
}

.bank-row-main,
.paper-row-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.q-id,
.paper-order {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
  flex-shrink: 0;
}
.q-label,
.paper-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink);
}

/* 题目徽标样式（与全局题集编辑及前台一致） */
.difficulty-pill {
  min-width: 58px;
  padding: 2px 8px;
  font-size: 11.5px;
  font-weight: 600;
  text-align: center;
  border-radius: 0;
  letter-spacing: 0.3px;
  white-space: nowrap;
  flex-shrink: 0;
}
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

.bank-row-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.score-hint {
  font-size: 11.5px;
  color: var(--ink-tertiary);
}
.pick-btn {
  padding: 3px 9px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
  border-radius: 0;
}
.pick-btn:hover {
  border-color: var(--ink);
}
.pick-btn.picked {
  background: #ebfbee;
  border-color: #b2f2bb;
  color: #2b8a3e;
}

/* 右栏试卷预览与批量设分条 */
.sort-btn {
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 0;
}
.batch-bar {
  padding: 10px 16px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sec-summary-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.sec-chip {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 8px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink);
}
.batch-score-ops {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.batch-group {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.batch-label {
  font-size: 11.5px;
  color: var(--ink-secondary);
}
.batch-input {
  width: 48px;
  height: 24px;
  border: 1px solid var(--line);
  text-align: center;
  font-size: 12px;
  font-weight: 600;
}
.batch-apply-btn {
  height: 24px;
  padding: 0 8px;
  font-size: 11.5px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: var(--surface);
  cursor: pointer;
}
.batch-apply-btn:hover {
  border-color: var(--ink);
}

.paper-row-ops {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}
.score-box {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: 4px;
}
.score-input {
  width: 52px;
  height: 26px;
  border: 1px solid var(--line);
  text-align: center;
  font-size: 12.5px;
  font-weight: 700;
}
.score-unit {
  font-size: 12px;
  color: var(--ink-secondary);
}
.icon-op-btn {
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-op-btn:hover:not(:disabled) {
  border-color: var(--ink);
}
.icon-op-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.icon-op-btn.danger {
  color: var(--danger);
}
.icon-op-btn.danger:hover {
  background: var(--danger-bg);
  border-color: var(--danger);
}

.panel-empty {
  padding: 36px 20px;
  text-align: center;
  font-size: 13px;
}

@media (max-width: 920px) {
  .config-bar,
  .composer-workbench {
    grid-template-columns: 1fr;
  }
  .sticky-panel {
    position: static;
  }
}
</style>
