<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getAdminProblemSetDetail,
  createAdminProblemSet,
  updateAdminProblemSet,
  type SaveProblemSetRequest
} from '@/api/problemSet'
import { ApiError } from '@/api/request'
import FormField from '@/components/FormField.vue'
import QuestionPicker from '@/components/problem-sets/QuestionPicker.vue'
import QuestionBadge from '@/components/problem-sets/QuestionBadge.vue'
import type { QuestionCandidate } from '@/components/problem-sets/types'
import { useToast } from '@/composables/useToast'
import { useUserStore } from '@/stores/user'

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

const loading = ref(isEdit.value)
const submitting = ref(false)
const pageError = ref('')

const form = reactive({
  title: '',
  description: '',
  category: 'ALGO',
  difficulty: 'MEDIUM',
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

let previousCategory = form.category
const selectedKeys = computed(() => sections.value.flatMap(section =>
  section.items.map(item => `${item.questionType}-${item.questionId}`)
))

function onCategoryChange() {
  if (totalSelectedCount.value > 0) {
    if (!window.confirm('切换题集题型将清空当前已添加的所有题目，确定切换吗？')) {
      form.category = previousCategory
      return
    }
    sections.value.forEach(section => { section.items = [] })
  }
  previousCategory = form.category
}

function isInProblemSet(type: 'ALGO' | 'DB' | 'OBJECTIVE', id: number): boolean {
  return sections.value.some((sec) =>
    sec.items.some((it) => it.questionType === type && it.questionId === id)
  )
}

function addQuestionToActiveSection(cand: QuestionCandidate) {
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
    previousCategory = form.category
    form.difficulty = detail.difficulty || 'MEDIUM'
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

onMounted(loadExisting)
</script>

<template>
  <div class="pset-edit">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ isEdit ? `编辑题集 #${problemSetId}` : '新建题集' }}</h1>
        <p class="page-sub">
          选择一种题型，按章节编排题目。点击章节后，在题库中搜索并添加。
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
        <div class="panel-header-row basic-heading">
          <div><h2 class="panel-title">基本信息</h2><p class="panel-description">设置题集名称、题型和可见范围</p></div>
        </div>
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

          <FormField label="题集难度" required>
            <select v-model="form.difficulty" class="input">
              <option value="EASY">简单 (EASY)</option>
              <option value="MEDIUM">中等 (MEDIUM)</option>
              <option value="HARD">困难 (HARD)</option>
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
            <div><h2 class="panel-title">章节编排</h2><p class="panel-description">{{ sections.length }} 个章节 · {{ totalSelectedCount }} 道题目</p></div>
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
                <button class="section-select" type="button" :aria-pressed="activeSectionIndex === sIdx" @click.stop="activeSectionIndex = sIdx">
                  第 {{ sIdx + 1 }} 节 <span v-if="activeSectionIndex === sIdx">· 当前章节</span>
                </button>
                <input
                  v-model="sec.title"
                  class="input sec-title-input"
                  type="text"
                  :aria-label="`第 ${sIdx + 1} 节标题`"
                  placeholder="章节标题…"
                />
                <div class="sec-btns">
                  <button
                    class="icon-btn"
                    type="button"
                    :disabled="sIdx === 0"
                    title="上移章节"
                    @click.stop="moveSectionUp(sIdx)"
                  >
                    ↑
                  </button>
                  <button
                    class="icon-btn"
                    type="button"
                    :disabled="sIdx === sections.length - 1"
                    title="下移章节"
                    @click.stop="moveSectionDown(sIdx)"
                  >
                    ↓
                  </button>
                  <button
                    class="icon-btn danger"
                    type="button"
                    title="删除章节"
                    @click.stop="removeSection(sIdx)"
                  >
                    ×
                  </button>
                </div>
              </div>

              <input
                v-model="sec.description"
                class="input sec-desc-input"
                type="text"
                :aria-label="`第 ${sIdx + 1} 节学习提示`"
                placeholder="章节副标题或学习提示（可选）…"
              />

              <div v-if="sec.items.length === 0" class="sec-empty">
                当前章节暂无题目，请在题库检索中选择「加入章节」。
              </div>

              <div v-else class="sec-items">
                <div
                  v-for="(item, iIdx) in sec.items"
                  :key="`${item.questionType}-${item.questionId}`"
                  class="sec-item-row"
                >
                  <span class="item-order mono">{{ iIdx + 1 }}.</span>
                  <QuestionBadge :value="item.difficulty" :question-type="item.questionType" />
                  <span class="item-id mono">#{{ item.questionId }}</span>
                  <span class="item-title" :title="item.title">{{ item.title }}</span>
                  <span v-if="item.sourceAlive === false" class="graveyard-pill">原题已归档</span>
                  <div class="item-actions">
                    <button
                      class="icon-btn"
                      type="button"
                      :disabled="iIdx === 0"
                      :aria-label="`上移题目 ${item.title}`"
                      @click.stop="moveItemUp(sIdx, iIdx)"
                    >
                      ↑
                    </button>
                    <button
                      class="icon-btn"
                      type="button"
                      :disabled="iIdx === sec.items.length - 1"
                      :aria-label="`下移题目 ${item.title}`"
                      @click.stop="moveItemDown(sIdx, iIdx)"
                    >
                      ↓
                    </button>
                    <button
                      class="icon-btn danger"
                      type="button"
                      :aria-label="`移除题目 ${item.title}`"
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

        <QuestionPicker
          :category="form.category"
          :section-number="activeSectionIndex + 1"
          :selected-keys="selectedKeys"
          @select="addQuestionToActiveSection"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.pset-edit { width: 100%; min-width: 0; padding-bottom: 40px; font-size: 16px; container-type: inline-size; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 20px; margin-bottom: 28px; }
.page-title { margin: 0 0 10px; font-size: 28px; font-weight: 700; }
.page-sub { margin: 0; color: var(--ink-secondary); font-size: 16px; line-height: 1.7; }
.head-actions { display: flex; gap: 12px; }
.panel { min-width: 0; background: var(--bg); border: 1px solid var(--line); padding: 24px; margin-bottom: 24px; }
.panel-title { margin: 0; font-size: 20px; font-weight: 700; }
.panel-description { margin: 6px 0 0; color: var(--ink-secondary); font-size: 14px; line-height: 1.5; }
.basic-heading { padding-bottom: 18px; border-bottom: 1px solid var(--line); }
.panel-header-row { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 14px; margin-bottom: 20px; }
.panel-header-row .panel-title { margin: 0; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px; margin-bottom: 24px; }
.input { min-width: 0; max-width: 100%; min-height: 46px; font-size: 16px; }
.textarea { width: 100%; resize: vertical; line-height: 1.7; }
.btn { min-height: 44px; font-size: 15px; }
.composer-grid { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); gap: 24px; align-items: start; }
.section-list { display: flex; flex-direction: column; gap: 16px; }
.section-box { min-width: 0; border: 1px solid var(--line); padding: 18px; background: var(--surface); }
.section-box.active { border-color: var(--ink); box-shadow: inset 0 0 0 1px var(--ink); background: var(--bg); }
.section-box-head { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.section-select { border: 1px solid var(--line); padding: 6px 10px; min-height: 36px; background: var(--bg); color: var(--ink); font-size: 14px; font-weight: 600; cursor: pointer; }
.section-select[aria-pressed='true'] { background: var(--ink); border-color: var(--ink); color: white; }
.sec-title-input { flex: 1 1 160px; width: 0; min-width: 0; font-weight: 600; }
.sec-desc-input { width: 100%; margin-bottom: 16px; }
.sec-btns, .item-actions { display: inline-flex; gap: 6px; flex-shrink: 0; }
.icon-btn { width: 36px; height: 36px; border: 1px solid var(--line); background: var(--surface); color: var(--ink); cursor: pointer; font-size: 16px; display: inline-flex; align-items: center; justify-content: center; }
.icon-btn:hover:not(:disabled) { border-color: var(--ink); background: var(--bg); }
.icon-btn:disabled { opacity: .35; cursor: not-allowed; }
.icon-btn.danger:hover { color: var(--danger); border-color: var(--danger); }
.sec-empty { padding: 24px 16px; text-align: center; font-size: 14px; line-height: 1.7; color: var(--ink-secondary); border: 1px dashed var(--line); }
.sec-items { display: flex; flex-direction: column; gap: 10px; }
.sec-item-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 14px; background: var(--bg); border: 1px solid var(--line); font-size: 15px; min-width: 0; }
.item-order { color: var(--ink-secondary); min-width: 24px; }
.item-id { color: var(--ink-secondary); font-size: 14px; }
.item-title { order: 2; flex-basis: 100%; min-width: 0; white-space: normal; overflow-wrap: anywhere; line-height: 1.7; }
.item-actions { margin-left: auto; }
.graveyard-pill { background: #fef2f2; color: #ac2933; font-size: 13px; padding: 3px 8px; }
.loading-box { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 48px 0; color: var(--ink-secondary); font-size: 16px; }
button:focus-visible, input:focus-visible { outline: 2px solid var(--ink); outline-offset: 3px; }
:deep(.field-label) { font-size: 15px; }
@container (max-width: 980px) { .composer-grid { grid-template-columns: minmax(0, 1fr); } .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .form-grid { grid-template-columns: minmax(0, 1fr); } .panel { padding: 16px; } .section-box { padding: 12px; } .head-actions { width: 100%; } .head-actions .btn { flex: 1; } .page-title { font-size: 24px; } }
</style>
