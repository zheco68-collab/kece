<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getAdminProblemSetDetail,
  createAdminProblemSet,
  updateAdminProblemSet,
  type SaveProblemSetRequest
} from '@/api/problemSet'
import { adminApi } from '@/api/admin'
import { ApiError } from '@/api/request'
import FormField from '@/components/FormField.vue'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'
import type { Question, DbQuestion, ObjectiveQuestion } from '@/types'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.hasRole('ADMIN'))

const problemSetId = computed(() => {
  const raw = route.params.id
  return raw ? Number(raw) : null
})
const isEdit = computed(() => problemSetId.value !== null)

const loading = ref(false)
const submitting = ref(false)
const pageError = ref('')

const form = reactive({
  title: '',
  description: '',
  category: 'ALGO',
  difficulty: 'MIXED',
  tagsText: '',
  isOfficial: 1,
  status: 1,
  version: 0
})

interface EditableItem {
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE'
  questionId: number
  title: string
  difficulty?: string
  sourceAlive?: boolean
}

interface EditableSection {
  title: string
  description: string
  items: EditableItem[]
}

const sections = ref<EditableSection[]>([
  {
    title: '第 1 节：核心基础训练',
    description: '',
    items: []
  }
])

const activeSectionIndex = ref(0)

function addSection() {
  sections.value.push({
    title: `第 ${sections.value.length + 1} 节：专题进阶`,
    description: '',
    items: []
  })
  activeSectionIndex.value = sections.value.length - 1
}

function removeSection(idx: number) {
  if (sections.value.length <= 1) {
    toast.error('题集至少需要保留一个章节')
    return
  }
  sections.value.splice(idx, 1)
  if (activeSectionIndex.value >= sections.value.length) {
    activeSectionIndex.value = sections.value.length - 1
  }
}

function moveSectionUp(idx: number) {
  if (idx <= 0) return
  const arr = sections.value
  const tmp = arr[idx - 1]!
  arr[idx - 1] = arr[idx]!
  arr[idx] = tmp
  activeSectionIndex.value = idx - 1
}

function moveSectionDown(idx: number) {
  if (idx >= sections.value.length - 1) return
  const arr = sections.value
  const tmp = arr[idx + 1]!
  arr[idx + 1] = arr[idx]!
  arr[idx] = tmp
  activeSectionIndex.value = idx + 1
}

// ---- 题库检索与选取（严格限定当前题集题型，单一题型不混排） ----
const tabKeyword = ref('')
const algoList = ref<Question[]>([])
const dbList = ref<DbQuestion[]>([])
const objList = ref<ObjectiveQuestion[]>([])
const listLoading = ref(false)

async function loadSource() {
  listLoading.value = true
  try {
    const keyword = tabKeyword.value.trim() || undefined
    if (form.category === 'ALGO') {
      algoList.value = (await adminApi.listQuestions({ keyword, page: 1, size: 100 })).list
    } else if (form.category === 'DB') {
      dbList.value = (await adminApi.listDbQuestions({ keyword, page: 1, size: 100 })).list
    } else {
      objList.value = (await adminApi.listObjectiveQuestions({ keyword, page: 1, size: 100 })).list
    }
  } finally {
    listLoading.value = false
  }
}

let tabKeywordTimer: ReturnType<typeof setTimeout> | null = null
watch(tabKeyword, () => {
  if (tabKeywordTimer !== null) clearTimeout(tabKeywordTimer)
  tabKeywordTimer = setTimeout(loadSource, 300)
})

function onCategoryChange() {
  if (totalSelectedCount.value > 0) {
    const ok = window.confirm('切换题集题型将清空当前已添加的所有题目，确定切换吗？')
    if (!ok) return
    sections.value.forEach((s) => (s.items = []))
  }
  loadSource()
}

interface SourceCandidate {
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE'
  questionId: number
  title: string
  difficulty: string
}

const sourceCandidates = computed<SourceCandidate[]>(() => {
  if (form.category === 'ALGO') {
    return algoList.value.map((q) => ({
      questionType: 'ALGO',
      questionId: q.id,
      title: q.title,
      difficulty: q.difficulty || 'MEDIUM'
    }))
  }
  if (form.category === 'DB') {
    return dbList.value.map((q) => ({
      questionType: 'DB',
      questionId: q.id,
      title: q.title,
      difficulty: 'MEDIUM'
    }))
  }
  return objList.value.map((q) => ({
    questionType: 'OBJECTIVE',
    questionId: q.id,
    title: q.content ? q.content.replace(/\s+/g, ' ').slice(0, 64) : `客观题 #${q.id}`,
    difficulty: 'EASY'
  }))
})

function isInProblemSet(type: 'ALGO' | 'DB' | 'OBJECTIVE', id: number): boolean {
  return sections.value.some((sec) =>
    sec.items.some((it) => it.questionType === type && it.questionId === id)
  )
}

function addQuestionToActiveSection(cand: SourceCandidate) {
  if (isInProblemSet(cand.questionType, cand.questionId)) {
    toast.info('该题已在当前题集中')
    return
  }
  const sec = sections.value[activeSectionIndex.value]
  if (!sec) return
  sec.items.push({
    questionType: cand.questionType,
    questionId: cand.questionId,
    title: cand.title,
    difficulty: cand.difficulty,
    sourceAlive: true
  })
}

function removeItem(secIdx: number, itemIdx: number) {
  sections.value[secIdx]?.items.splice(itemIdx, 1)
}

function moveItemUp(secIdx: number, itemIdx: number) {
  if (itemIdx <= 0) return
  const items = sections.value[secIdx]?.items
  if (!items) return
  const tmp = items[itemIdx - 1]!
  items[itemIdx - 1] = items[itemIdx]!
  items[itemIdx] = tmp
}

function moveItemDown(secIdx: number, itemIdx: number) {
  const items = sections.value[secIdx]?.items
  if (!items || itemIdx >= items.length - 1) return
  const tmp = items[itemIdx + 1]!
  items[itemIdx + 1] = items[itemIdx]!
  items[itemIdx] = tmp
}

const totalSelectedCount = computed(() =>
  sections.value.reduce((acc, s) => acc + s.items.length, 0)
)

async function loadExisting() {
  if (!isEdit.value || !problemSetId.value) return
  loading.value = true
  pageError.value = ''
  try {
    const detail = await getAdminProblemSetDetail(problemSetId.value)
    form.title = detail.title || ''
    form.description = detail.description || ''
    form.category = detail.category || 'ALGO'
    form.difficulty = detail.difficulty || 'MIXED'
    form.tagsText = (detail.tags || []).join(', ')
    form.isOfficial = detail.isOfficial ?? 1
    form.status = detail.status ?? 1
    form.version = detail.version ?? 0
    if (detail.sections && detail.sections.length > 0) {
      sections.value = detail.sections.map((sec) => ({
        title: sec.title,
        description: sec.description || '',
        items: (sec.items || []).map((it) => ({
          questionType: it.questionType as 'ALGO' | 'DB' | 'OBJECTIVE',
          questionId: it.questionId,
          title: it.title,
          difficulty: it.difficulty,
          sourceAlive: it.sourceAlive
        }))
      }))
    }
  } catch (e) {
    pageError.value = e instanceof ApiError ? e.message : '加载题集详情失败'
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  if (!form.title.trim()) {
    toast.error('请填写题集标题')
    return
  }
  const tags = form.tagsText
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .join(',')

  const payload: SaveProblemSetRequest = {
    title: form.title.trim(),
    description: form.description.trim(),
    category: form.category,
    difficulty: form.difficulty,
    tags,
    isOfficial: isAdmin.value ? form.isOfficial : 0,
    status: form.status,
    version: form.version,
    sections: sections.value.map((sec, sIdx) => ({
      title: sec.title.trim() || `第 ${sIdx + 1} 节`,
      description: sec.description.trim(),
      sortOrder: sIdx + 1,
      items: sec.items.map((it, iIdx) => ({
        questionType: it.questionType,
        questionId: it.questionId,
        title: it.title,
        difficulty: it.difficulty,
        sortOrder: iIdx + 1
      }))
    }))
  }

  submitting.value = true
  try {
    if (isEdit.value && problemSetId.value) {
      await updateAdminProblemSet(problemSetId.value, payload)
      toast.success('题集已更新')
    } else {
      await createAdminProblemSet(payload)
      toast.success('题集已创建')
    }
    router.push('/admin/problem-sets')
  } catch (e) {
    toast.error(e instanceof ApiError ? e.message : '保存题集失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadSource()
  loadExisting()
})
</script>

<template>
  <div class="pset-edit container">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ isEdit ? `编辑题集 #${problemSetId}` : '新建题集' }}</h1>
        <p class="page-sub">
          支持将编程题、数据库 SQL 题与客观题按章节自由编排，自带题目标题快照容灾保护。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn btn-ghost" type="button" @click="router.push('/admin/problem-sets')">
          返回列表
        </button>
        <button
          class="btn btn-primary"
          type="button"
          :disabled="submitting || loading"
          @click="handleSubmit"
        >
          {{ submitting ? '保存中…' : '保存题集' }}
        </button>
      </div>
    </div>

    <p v-if="pageError" class="submit-error">{{ pageError }}</p>

    <div v-if="loading" class="loading-box">
      <div class="loading-spinner"></div>
      <span>正在加载题集配置…</span>
    </div>

    <template v-else>
      <!-- 基本信息面板 -->
      <section class="panel">
        <h2 class="panel-title">01 / 基本信息</h2>
        <div class="form-grid">
          <FormField label="题集标题" required>
            <input
              v-model="form.title"
              class="input"
              type="text"
              placeholder="例如：算法与数据结构 · 经典必刷 30 题"
            />
          </FormField>

          <FormField label="题集专用题型 (单题型)" required>
            <select v-model="form.category" class="input" @change="onCategoryChange">
              <option value="ALGO">算法编程题集 (ALGO)</option>
              <option value="DB">数据库 SQL 题集 (DB)</option>
              <option value="OBJECTIVE">标准客观题集 (OBJECTIVE)</option>
            </select>
          </FormField>

          <FormField label="整体难度">
            <select v-model="form.difficulty" class="input">
              <option value="EASY">入门 (EASY)</option>
              <option value="MEDIUM">进阶 (MEDIUM)</option>
              <option value="HARD">挑战 (HARD)</option>
              <option value="MIXED">循序渐进 / 综合 (MIXED)</option>
            </select>
          </FormField>

          <FormField label="可见状态">
            <select v-model.number="form.status" class="input">
              <option :value="1">公开（前台可见）</option>
              <option :value="0">隐藏（仅后台可见）</option>
            </select>
          </FormField>

          <FormField v-if="isAdmin" label="题集属性">
            <select v-model.number="form.isOfficial" class="input">
              <option :value="1">官方精选</option>
              <option :value="0">教师自建</option>
            </select>
          </FormField>

          <FormField label="标签（逗号分隔）">
            <input
              v-model="form.tagsText"
              class="input"
              type="text"
              placeholder="例如：双指针, 动态规划, 期末冲刺"
            />
          </FormField>
        </div>

        <FormField label="题集导读 / 描述">
          <textarea
            v-model="form.description"
            class="input textarea"
            rows="3"
            placeholder="简要介绍本题集的目标人群、涵盖知识点与刷题建议…"
          ></textarea>
        </FormField>
      </section>

      <!-- 章节与题目编排 -->
      <div class="composer-grid">
        <!-- 左侧：章节与已选题目 -->
        <section class="panel sections-panel">
          <div class="panel-header-row">
            <h2 class="panel-title">
              02 / 章节目录编排（共 {{ sections.length }} 节，{{ totalSelectedCount }} 题）
            </h2>
            <button class="btn btn-ghost btn-sm" type="button" @click="addSection">
              + 新增章节
            </button>
          </div>

          <div class="section-list">
            <div
              v-for="(sec, sIdx) in sections"
              :key="sIdx"
              class="section-box"
              :class="{ active: activeSectionIndex === sIdx }"
              @click="activeSectionIndex = sIdx"
            >
              <div class="section-box-head">
                <span class="sec-index mono">#{{ sIdx + 1 }}</span>
                <input
                  v-model="sec.title"
                  class="input sec-title-input"
                  type="text"
                  placeholder="章节标题…"
                />
                <div class="sec-btns">
                  <button
                    class="icon-btn"
                    type="button"
                    :disabled="sIdx === 0"
                    @click.stop="moveSectionUp(sIdx)"
                    title="上移章节"
                  >
                    ↑
                  </button>
                  <button
                    class="icon-btn"
                    type="button"
                    :disabled="sIdx === sections.length - 1"
                    @click.stop="moveSectionDown(sIdx)"
                    title="下移章节"
                  >
                    ↓
                  </button>
                  <button
                    class="icon-btn danger"
                    type="button"
                    @click.stop="removeSection(sIdx)"
                    title="删除章节"
                  >
                    ×
                  </button>
                </div>
              </div>

              <input
                v-model="sec.description"
                class="input sec-desc-input"
                type="text"
                placeholder="章节副标题或学习提示（可选）…"
              />

              <div v-if="sec.items.length === 0" class="sec-empty">
                当前章节暂无题目 — 点击右侧题库列表中的「+ 加入第 {{ sIdx + 1 }} 节」添加
              </div>

              <div v-else class="sec-items">
                <div
                  v-for="(item, iIdx) in sec.items"
                  :key="`${item.questionType}-${item.questionId}`"
                  class="sec-item-row"
                >
                  <span class="item-order mono">{{ iIdx + 1 }}.</span>
                  <span class="type-pill" :class="`type-${item.questionType.toLowerCase()}`">
                    {{
                      item.questionType === 'ALGO'
                        ? '编程'
                        : item.questionType === 'DB'
                          ? 'SQL'
                          : '客观'
                    }}
                  </span>
                  <span class="item-id mono">#{{ item.questionId }}</span>
                  <span class="item-title">{{ item.title }}</span>
                  <span v-if="item.sourceAlive === false" class="graveyard-pill">原题已归档</span>
                  <div class="item-actions">
                    <button
                      class="icon-btn"
                      type="button"
                      :disabled="iIdx === 0"
                      @click.stop="moveItemUp(sIdx, iIdx)"
                    >
                      ↑
                    </button>
                    <button
                      class="icon-btn"
                      type="button"
                      :disabled="iIdx === sec.items.length - 1"
                      @click.stop="moveItemDown(sIdx, iIdx)"
                    >
                      ↓
                    </button>
                    <button
                      class="icon-btn danger"
                      type="button"
                      @click.stop="removeItem(sIdx, iIdx)"
                    >
                      ×
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 右侧：全题库检索器 -->
        <section class="panel picker-panel">
          <div class="panel-header-row">
            <h2 class="panel-title">03 / 题库检索器</h2>
            <span class="target-hint">
              目标：<strong>第 {{ activeSectionIndex + 1 }} 节</strong>
            </span>
          </div>

          <div class="picker-type-banner">
            <span class="mono">专属题型：</span>
            <strong>{{ form.category === 'ALGO' ? '算法编程题' : form.category === 'DB' ? '数据库 SQL 题' : '标准客观题' }}</strong>
            <span class="banner-sub">（本题集内题目均严格限定为此单一题型）</span>
          </div>

          <input
            v-model="tabKeyword"
            class="input picker-search"
            type="text"
            placeholder="输入题号或标题关键词检索…"
          />

          <div v-if="listLoading" class="loading-box compact">
            <div class="loading-spinner"></div>
            <span>检索题库中…</span>
          </div>

          <div v-else class="candidate-list">
            <div
              v-for="cand in sourceCandidates"
              :key="`${cand.questionType}-${cand.questionId}`"
              class="candidate-row"
            >
              <div class="cand-info">
                <span class="cand-id mono">#{{ cand.questionId }}</span>
                <span class="cand-title">{{ cand.title }}</span>
              </div>
              <button
                type="button"
                class="btn btn-sm"
                :class="
                  isInProblemSet(cand.questionType, cand.questionId)
                    ? 'btn-ghost'
                    : 'btn-primary'
                "
                :disabled="isInProblemSet(cand.questionType, cand.questionId)"
                @click="addQuestionToActiveSection(cand)"
              >
                {{
                  isInProblemSet(cand.questionType, cand.questionId)
                    ? '已加入'
                    : `+ 加入第 ${activeSectionIndex + 1} 节`
                }}
              </button>
            </div>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pset-edit {
  padding-top: 28px;
  padding-bottom: 64px;
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
}

.page-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
}

.page-sub {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
}

.head-actions {
  display: flex;
  gap: 10px;
}

.panel {
  background: #ffffff;
  border: 1px solid var(--line);
  padding: 20px;
  margin-bottom: 20px;
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.panel-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.panel-header-row .panel-title {
  margin: 0;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.textarea {
  width: 100%;
  resize: vertical;
}

.composer-grid {
  display: grid;
  grid-template-columns: 1.25fr 1fr;
  gap: 20px;
  align-items: start;
}

.section-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.section-box {
  border: 1px solid var(--line);
  padding: 14px;
  background: #fafafa;
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.section-box.active {
  border-color: #111111;
  border-width: 2px;
  background: #ffffff;
}

.section-box-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.sec-index {
  font-weight: 700;
  font-size: 13px;
  color: #111111;
}

.sec-title-input {
  flex: 1;
  font-weight: 600;
}

.sec-desc-input {
  width: 100%;
  font-size: 12px;
  margin-bottom: 10px;
}

.sec-btns,
.item-actions {
  display: inline-flex;
  gap: 4px;
}

.icon-btn {
  width: 26px;
  height: 26px;
  border: 1px solid var(--line);
  background: #ffffff;
  cursor: pointer;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.icon-btn:hover:not(:disabled) {
  border-color: #111111;
}

.icon-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.icon-btn.danger:hover {
  color: var(--danger);
  border-color: var(--danger);
}

.sec-empty {
  padding: 16px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-tertiary);
  border: 1px dashed var(--line);
}

.sec-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sec-item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: #f9fafb;
  border: 1px solid var(--line);
  font-size: 13px;
}

.item-order {
  color: var(--ink-tertiary);
  width: 22px;
}

.type-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 6px;
}

.type-algo {
  background: #111111;
  color: #ffffff;
}

.type-db {
  background: #eff6ff;
  color: #1d4ed8;
}

.type-objective {
  background: #f3f4f6;
  color: #374151;
}

.item-id {
  color: var(--ink-tertiary);
  font-size: 12px;
}

.item-title {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.graveyard-pill {
  background: #fef2f2;
  color: #dc2626;
  font-size: 11px;
  padding: 1px 6px;
}

.target-hint {
  font-size: 12px;
  color: var(--ink-secondary);
}

.picker-type-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  background: #f5f5f5;
  border: 1px solid var(--line);
  margin-bottom: 12px;
  font-size: 13px;
}
.banner-sub {
  color: var(--ink-tertiary);
  font-size: 12px;
}
.picker-tabs {
  display: flex;
  border: 1px solid var(--line);
  margin-bottom: 12px;
}

.picker-tab {
  flex: 1;
  padding: 8px;
  background: #fafafa;
  border: none;
  border-right: 1px solid var(--line);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.picker-tab:last-child {
  border-right: none;
}

.picker-tab.active {
  background: #111111;
  color: #ffffff;
}

.picker-search {
  width: 100%;
  margin-bottom: 12px;
}

.candidate-list {
  max-height: 520px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.candidate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
}

.cand-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.cand-id {
  color: var(--ink-tertiary);
  font-size: 12px;
}

.cand-title {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px 0;
  color: var(--ink-secondary);
  font-size: 13px;
}

.loading-box.compact {
  padding: 28px 0;
}

@media (max-width: 960px) {
  .form-grid,
  .composer-grid {
    grid-template-columns: 1fr;
  }
}
</style>
