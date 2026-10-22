<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { questionApi } from '@/api/question'
import { ApiError } from '@/api/request'
import AdminNav from '@/components/AdminNav.vue'
import FormField from '@/components/FormField.vue'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import { useToast } from '@/composables/useToast'
import type { Difficulty } from '@/types'

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

interface JudgerOption {
  key: string
  label: string
  desc: string
  badge: string
}

const JUDGER_OPTIONS: JudgerOption[] = [
  { key: 'cpp', label: 'C++', desc: 'GCC 13 · C++17 强隔离沙箱', badge: 'GCC 13' },
  { key: 'java', label: 'Java', desc: 'Eclipse Temurin 17 (JDK 17)', badge: 'JDK 17' },
  { key: 'python', label: 'Python', desc: 'Python 3.12 解释器', badge: 'Py 3.12' },
  { key: 'arkts', label: 'ArkTS', desc: 'Node / TSC 鸿蒙 ArkTS 沙箱', badge: 'TS 2022' },
]

const selectedLanguages = ref<string[]>(['cpp', 'java', 'python', 'arkts'])

const allJudgerOptions = computed(() => {
  const list = [...JUDGER_OPTIONS]
  for (const lang of selectedLanguages.value) {
    if (!list.some((o) => o.key === lang)) {
      list.push({
        key: lang,
        label: lang.toUpperCase(),
        desc: '自定义沙箱评测环境',
        badge: lang,
      })
    }
  }
  return list
})

function selectAllJudgers() {
  selectedLanguages.value = allJudgerOptions.value.map((o) => o.key)
  formErrors.languages = ''
}

function selectClassicJudgers() {
  selectedLanguages.value = ['cpp', 'java', 'python']
  formErrors.languages = ''
}

function selectArktsOnly() {
  selectedLanguages.value = ['arkts']
  formErrors.languages = ''
}

function clearJudgers() {
  selectedLanguages.value = []
}

const form = reactive({
  title: '',
  description: '',
  difficulty: 'EASY' as Difficulty,
  status: 1,
  timeLimit: 1000,
  memoryLimit: 256,
})

const formErrors = reactive({
  title: '',
  description: '',
  timeLimit: '',
  memoryLimit: '',
  languages: '',
})

/** 测试用例：clientId 区分本地条目，id 存在表示后端已有记录。 */
interface CaseRow {
  clientId: number
  id?: number
  caseNum?: number
  scoreWeight?: number
  inputPath?: string | null
  outputPath?: string | null
  inputSize?: number
  outputSize?: number
  input: string
  expectedOutput: string
  isSample: boolean
  isLarge?: boolean
}

const cases = ref<CaseRow[]>([])
let caseSeq = 0
/** 编辑态下被删除的既有用例 id，保存时逐个调删除接口。 */
const removedCaseIds: number[] = []

function addCase() {
  const nextNum = cases.value.length > 0 ? (cases.value[cases.value.length - 1].caseNum || cases.value.length) + 1 : 1
  cases.value.push({
    clientId: ++caseSeq,
    caseNum: nextNum,
    scoreWeight: 10,
    input: '',
    expectedOutput: '',
    isSample: false,
    inputSize: 0,
    outputSize: 0,
    isLarge: false
  })
}

function removeCase(clientId: number) {
  const target = cases.value.find((c) => c.clientId === clientId)
  if (target?.id !== undefined) removedCaseIds.push(target.id)
  cases.value = cases.value.filter((c) => c.clientId !== clientId)
}

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}


const sampleCasesCount = computed(() => {
  return cases.value.filter((c) => c.isSample).length
})

// === Zip 批量导入/导出状态与方法 ===
const showZipModal = ref(false)
const zipFile = ref<File | null>(null)
const zipReplace = ref(true)
const zipFirstAsSample = ref(true)
const zipUploading = ref(false)
const isZipDragOver = ref(false)

function openZipModal() {
  if (!questionId.value) {
    toast.error('请先保存题目基本信息后再导入测试集 Zip')
    return
  }
  zipFile.value = null
  showZipModal.value = true
}

function handleZipFileSelect(e: Event) {
  const target = e.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    zipFile.value = target.files[0]
  }
}

function handleZipDrop(e: DragEvent) {
  isZipDragOver.value = false
  if (e.dataTransfer?.files && e.dataTransfer.files.length > 0) {
    const f = e.dataTransfer.files[0]
    if (f.name.toLowerCase().endsWith('.zip')) {
      zipFile.value = f
    } else {
      toast.error('仅支持上传 .zip 格式的压缩包')
    }
  }
}

async function submitZipImport() {
  if (!zipFile.value || !questionId.value || zipUploading.value) return
  zipUploading.value = true
  try {
    const imported = await questionApi.importTestCasesZip(
      questionId.value,
      zipFile.value,
      zipReplace.value,
      zipFirstAsSample.value
    )
    toast.success(`成功导入 ${imported.length} 组测试点！`)
    showZipModal.value = false
    await loadQuestion()
  } catch (err) {
    toast.error(err instanceof ApiError ? err.message : 'Zip 导入失败，请检查文件格式')
  } finally {
    zipUploading.value = false
  }
}

function exportZip() {
  if (!questionId.value) return
  const url = questionApi.exportTestCasesZipUrl(questionId.value)
  window.open(url, '_blank')
}

function downloadInput(id?: number) {
  if (!id) return
  window.open(questionApi.downloadInputUrl(id), '_blank')
}

function downloadOutput(id?: number) {
  if (!id) return
  window.open(questionApi.downloadOutputUrl(id), '_blank')
}

async function loadQuestion() {
  if (!questionId.value) return
  loading.value = true
  pageError.value = ''
  try {
    const q = await questionApi.detail(questionId.value)
    form.title = q.title
    form.description = q.description
    form.difficulty = q.difficulty
    form.status = q.status
    form.timeLimit = q.timeLimit ?? 1000
    form.memoryLimit = q.memoryLimit ?? 256
    if (Array.isArray(q.languages) && q.languages.length > 0) {
      selectedLanguages.value = [...q.languages]
    } else {
      selectedLanguages.value = ['cpp', 'java', 'python', 'arkts']
    }
    const testCases = await questionApi.listTestCases(questionId.value)
    cases.value = testCases.map((tc) => ({
      clientId: ++caseSeq,
      id: tc.id,
      caseNum: tc.caseNum,
      scoreWeight: tc.scoreWeight ?? 10,
      inputPath: tc.inputPath,
      outputPath: tc.outputPath,
      inputSize: tc.inputSize ?? 0,
      outputSize: tc.outputSize ?? 0,
      input: tc.input ?? '',
      expectedOutput: tc.expectedOutput ?? '',
      isSample: tc.isSample === 1,
      isLarge: tc.isLarge ?? false,
    }))
  } catch (e) {
    pageError.value = e instanceof ApiError ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  formErrors.title = form.title.trim() ? '' : '标题不能为空'
  formErrors.description = form.description.trim() ? '' : '题目描述不能为空'
  const t = form.timeLimit
  formErrors.timeLimit = t >= 1 && t <= 60000 ? '' : '时间限制需在 1-60000 毫秒之间'
  const m = form.memoryLimit
  formErrors.memoryLimit = m >= 1 && m <= 4096 ? '' : '内存限制需在 1-4096 MB 之间'
  formErrors.languages = selectedLanguages.value.length > 0 ? '' : '请至少勾选一种评测机语言'
  if (selectedLanguages.value.length === 0) {
    toast.error('请至少勾选一种支持的评测机语言')
    return false
  }
  const badCase = cases.value.find((c) => !c.isLarge && !c.expectedOutput.trim())
  if (badCase) {
    toast.error('测试用例的期望输出不能为空')
    return false
  }
  return (
    !formErrors.title &&
    !formErrors.description &&
    !formErrors.timeLimit &&
    !formErrors.memoryLimit &&
    !formErrors.languages
  )
}

async function save() {
  if (submitting.value || !validate()) return
  submitting.value = true
  pageError.value = ''
  try {
    const body = {
      title: form.title.trim(),
      description: form.description,
      difficulty: form.difficulty,
      status: form.status,
      timeLimit: form.timeLimit,
      memoryLimit: form.memoryLimit,
      languages: selectedLanguages.value.join(','),
    }
    let targetId: number
    if (questionId.value) {
      await questionApi.update(questionId.value, body)
      targetId = questionId.value
    } else {
      const created = await questionApi.create(body)
      targetId = created.id
    }
    // 用例逐条同步：老用例更新、新用例创建、删除掉的逐个移除。
    for (const c of cases.value) {
      if (c.isLarge) {
        // 大文件测试点已经由文件系统维护，只更新元数据
        if (c.id !== undefined) {
          await questionApi.updateTestCase(c.id, {
            caseNum: c.caseNum,
            scoreWeight: c.scoreWeight ?? 10,
            isSample: c.isSample ? 1 : 0,
            expectedOutput: c.expectedOutput || 'LARGE_FILE',
          })
        }
        continue
      }
      const caseBody = {
        input: c.input || undefined,
        expectedOutput: c.expectedOutput,
        caseNum: c.caseNum,
        scoreWeight: c.scoreWeight ?? 10,
        isSample: c.isSample ? 1 : 0,
      }
      if (c.id !== undefined) {
        await questionApi.updateTestCase(c.id, caseBody)
      } else {
        await questionApi.addTestCase(targetId, caseBody)
      }
    }
    for (const id of removedCaseIds) {
      await questionApi.removeTestCase(id)
    }
    toast.success(isEdit.value ? '题目已更新' : '题目已创建')
    router.push('/admin/questions')
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
    <RouterLink class="back" to="/admin/questions">← 返回编程题管理</RouterLink>
    <h1 class="page-title">{{ isEdit ? '编辑编程题' : '新建编程题' }}</h1>
    <AdminNav />

    <p v-if="loading" class="muted">加载中…</p>
    <p v-if="pageError" class="submit-error">{{ pageError }}</p>

    <div v-if="!loading" class="form-cards">
      <div class="card form-card">
        <h2 class="card-title">基本信息</h2>
        <FormField label="标题" required :error="formErrors.title">
          <input v-model="form.title" class="input" placeholder="题目标题" />
        </FormField>
        <div class="field-row">
          <FormField label="难度" required>
            <select v-model="form.difficulty" class="input">
              <option value="EASY">简单</option>
              <option value="MEDIUM">中等</option>
              <option value="HARD">困难</option>
            </select>
          </FormField>
          <FormField label="状态">
            <select v-model="form.status" class="input">
              <option :value="1">公开</option>
              <option :value="0">私有</option>
            </select>
          </FormField>
        </div>
        <div class="field-row">
          <FormField label="时间限制" :error="formErrors.timeLimit" hint="毫秒，1-60000">
            <input v-model.number="form.timeLimit" class="input" type="number" min="1" max="60000" />
          </FormField>
          <FormField label="内存限制" :error="formErrors.memoryLimit" hint="MB，1-4096">
            <input v-model.number="form.memoryLimit" class="input" type="number" min="1" max="4096" />
          </FormField>
        </div>
        <FormField
          label="测评机选择"
          required
          :error="formErrors.languages"
          hint="多选勾选本题允许提交与执行评测的语言沙箱"
        >
          <div class="judger-select-box">
            <div class="judger-toolbar">
              <span class="judger-count">
                已启用 <b>{{ selectedLanguages.length }}</b> / {{ allJudgerOptions.length }} 个评测机
              </span>
              <div class="judger-presets">
                <button type="button" class="btn-preset" @click="selectAllJudgers">全选</button>
                <button type="button" class="btn-preset" @click="selectClassicJudgers">常用 (C++/Java/Python)</button>
                <button type="button" class="btn-preset" @click="selectArktsOnly">仅 ArkTS</button>
                <button type="button" class="btn-preset btn-preset-danger" @click="clearJudgers">清空</button>
              </div>
            </div>

            <div class="judger-grid">
              <label
                v-for="opt in allJudgerOptions"
                :key="opt.key"
                class="judger-card"
                :class="{ active: selectedLanguages.includes(opt.key) }"
              >
                <input
                  v-model="selectedLanguages"
                  type="checkbox"
                  class="judger-checkbox"
                  :value="opt.key"
                  @change="formErrors.languages = ''"
                />
                <div class="judger-body">
                  <div class="judger-title-row">
                    <span class="judger-name">{{ opt.label }}</span>
                    <span class="judger-badge">{{ opt.badge }}</span>
                  </div>
                  <span class="judger-desc">{{ opt.desc }}</span>
                </div>
              </label>
            </div>
          </div>
        </FormField>
      </div>

      <div class="card form-card">
        <h2 class="card-title">题目描述</h2>
        <FormField label="描述（Markdown）" required :error="formErrors.description">
          <MarkdownEditor v-model="form.description" placeholder="支持 Markdown 语法" />
        </FormField>
      </div>

      <!-- 测试用例与评测点管理 -->
      <div class="card form-card">
        <div class="card-head">
          <div class="card-title-group">
            <h2 class="card-title">测试用例</h2>
            <span v-if="cases.length > 0" class="muted case-count-tag">
              共 {{ cases.length }} 组用例<span v-if="sampleCasesCount > 0">（含 {{ sampleCasesCount }} 组公开样例）</span>
            </span>
          </div>
          <div class="card-head-actions">
            <button
              v-if="isEdit"
              class="btn btn-ghost btn-sm"
              type="button"
              @click="openZipModal"
            >
              导入 Zip
            </button>
            <button
              v-if="isEdit && cases.length > 0"
              class="btn btn-ghost btn-sm"
              type="button"
              @click="exportZip"
            >
              导出 Zip
            </button>
            <button class="btn btn-ghost btn-sm" type="button" @click="addCase">
              添加测试点
            </button>
          </div>
        </div>

        <p class="muted testcase-hint">
          测试点数据由服务器文件系统独立存储。支持在此逐组编辑或通过 Zip 压缩包（成对的 *.in 与 *.out 文件）批量导入。
        </p>

        <p v-if="cases.length === 0" class="muted">
          暂无测试点，请点击上方“添加测试点”或“导入 Zip”创建。
        </p>

        <div
          v-for="(c, idx) in cases"
          :key="c.clientId"
          class="case-card"
        >
          <div class="case-head">
            <span class="case-seq muted">测试点 #{{ c.caseNum || idx + 1 }}</span>

            <label class="sample-check">
              <input v-model="c.isSample" type="checkbox" />
              样例用例
            </label>

            <label class="score-field">
              <span class="muted">分值</span>
              <input
                v-model.number="c.scoreWeight"
                type="number"
                min="1"
                max="100"
                class="input score-input"
              />
            </label>

            <span v-if="c.inputSize || c.outputSize" class="case-size-info muted">
              In: {{ formatBytes(c.inputSize) }} / Out: {{ formatBytes(c.outputSize) }}
            </span>

            <span v-if="c.isLarge" class="badge">大文件托管</span>

            <div class="case-actions">
              <button
                v-if="c.id"
                class="btn btn-ghost btn-xs"
                type="button"
                @click="downloadInput(c.id)"
              >
                下载 .in
              </button>
              <button
                v-if="c.id"
                class="btn btn-ghost btn-xs"
                type="button"
                @click="downloadOutput(c.id)"
              >
                下载 .out
              </button>
              <button
                class="btn btn-ghost btn-sm btn-danger case-remove"
                type="button"
                @click="removeCase(c.clientId)"
              >
                删除
              </button>
            </div>
          </div>

          <div v-if="c.isLarge" class="large-notice">
            <span class="muted">
              该测试点数据量庞大（{{ formatBytes(Math.max(c.inputSize || 0, c.outputSize || 0)) }}），已由服务器文件系统直接托管。网页端仅展示预览，保存时不会覆盖原始文件。
            </span>
          </div>

          <div class="case-inputs-grid">
            <FormField label="标准输入 (stdin)" hint="可为空">
              <textarea
                v-model="c.input"
                class="input textarea mono-area"
                :disabled="c.isLarge"
                placeholder="标准输入数据"
              />
            </FormField>
            <FormField label="期望输出 (stdout)" required>
              <textarea
                v-model="c.expectedOutput"
                class="input textarea mono-area"
                :disabled="c.isLarge"
                placeholder="期望输出数据"
              />
            </FormField>
          </div>
        </div>
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

    <!-- Zip 批量导入测试点弹窗 -->
    <Teleport to="body">
      <div v-if="showZipModal" class="modal-overlay" @click.self="showZipModal = false">
        <div class="modal-dialog" role="dialog" aria-modal="true">
          <div class="modal-header">
            <h3 class="modal-title">导入测试点压缩包 (Zip)</h3>
            <button class="modal-close" type="button" @click="showZipModal = false">&times;</button>
          </div>

          <div class="modal-body">
            <div
              class="drop-zone"
              :class="{ 'is-dragover': isZipDragOver, 'has-file': !!zipFile }"
              @dragover.prevent="isZipDragOver = true"
              @dragleave.prevent="isZipDragOver = false"
              @drop="handleZipDrop"
              @click="($refs.zipFileInput as HTMLInputElement)?.click()"
            >
              <input
                ref="zipFileInput"
                type="file"
                accept=".zip"
                class="hidden-file-input"
                @change="handleZipFileSelect"
              />
              <div v-if="!zipFile" class="drop-prompt">
                <div class="drop-title">点击选择文件，或将 Zip 压缩包拖拽至此</div>
                <div class="drop-hint muted">支持包含 .in 与 .out 测试数据的 .zip 压缩包</div>
              </div>
              <div v-else class="file-selected-box">
                <span class="file-name">{{ zipFile.name }} ({{ formatBytes(zipFile.size) }})</span>
                <button class="btn btn-ghost btn-xs" type="button" @click.stop="zipFile = null">
                  重新选择
                </button>
              </div>
            </div>

            <div class="modal-options">
              <label class="sample-check">
                <input v-model="zipReplace" type="checkbox" />
                覆盖已有测试点（清空原有旧用例，以 Zip 内容完全重建）
              </label>
              <label class="sample-check">
                <input v-model="zipFirstAsSample" type="checkbox" />
                首组测试点设为公开样例
              </label>
            </div>

            <div class="rules-box muted">
              <div class="rules-title">命名规范说明：</div>
              <div>1. 压缩包内请成对包含输入与输出文件，如 <code>1.in</code> / <code>1.out</code> 或 <code>input1.txt</code> / <code>output1.txt</code>。</div>
              <div>2. 系统会自动按文件名数字自然排序（1, 2, ... 10）编号导入。</div>
            </div>
          </div>

          <div class="modal-footer">
            <button class="btn btn-ghost btn-sm" type="button" :disabled="zipUploading" @click="showZipModal = false">
              取消
            </button>
            <button
              class="btn btn-primary btn-sm"
              type="button"
              :disabled="!zipFile || zipUploading"
              @click="submitZipImport"
            >
              {{ zipUploading ? '导入中…' : '开始导入' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
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
.card-title-group {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.case-count-tag {
  font-size: 13px;
  font-weight: normal;
}
.card-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.testcase-hint {
  font-size: 13px;
  margin: -4px 0 16px;
  line-height: 1.5;
}
.case-card {
  border: 1px solid var(--line);
  padding: 14px 16px;
  margin-bottom: 14px;
  background: var(--bg);
}
.case-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 12px;
}
.case-seq {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.sample-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-secondary);
  cursor: pointer;
}
.score-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.score-input {
  width: 52px;
  padding: 2px 6px;
  text-align: center;
  font-size: 13px;
}
.case-size-info {
  font-size: 12px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.case-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.large-notice {
  padding: 8px 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  font-size: 13px;
  margin-bottom: 12px;
}
.case-inputs-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 768px) {
  .case-inputs-grid {
    grid-template-columns: 1fr;
  }
}
.textarea {
  height: auto;
  min-height: 88px;
  padding: 10px 12px;
  resize: vertical;
  line-height: 1.5;
  font-family: inherit;
}
.mono-area {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, Courier, monospace;
  font-size: 13px;
  min-height: 96px;
}
.form-actions {
  display: flex;
  gap: 12px;
}

/* Zip 导入弹窗样式（对齐 ConfirmModal / EditProfileModal） */
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 17, 17, 0.45);
}
.modal-dialog {
  width: 100%;
  min-width: 320px;
  max-width: 500px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  box-shadow: var(--shadow-md);
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
  background: var(--bg);
}
.modal-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}
.modal-close {
  font-size: 20px;
  line-height: 1;
  color: var(--ink-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0 4px;
}
.modal-close:hover {
  color: var(--ink);
}
.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.drop-zone {
  border: 1px dashed var(--line);
  background: var(--bg);
  padding: 24px 16px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.drop-zone:hover,
.drop-zone.is-dragover {
  border-color: var(--ink);
  background: var(--surface-hover);
}
.drop-zone.has-file {
  border-color: var(--button);
  background: var(--bg);
  padding: 16px;
}
.drop-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 4px;
  color: var(--ink);
}
.drop-hint {
  font-size: 12px;
}
.file-selected-box {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 13px;
}
.file-name {
  color: var(--ink);
  font-weight: 500;
}
.hidden-file-input {
  display: none;
}
.modal-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.rules-box {
  padding: 10px 12px;
  border: 1px solid var(--line);
  background: var(--bg);
  font-size: 12px;
  line-height: 1.6;
}
.rules-title {
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 4px;
}
.rules-box code {
  background: var(--surface);
  padding: 1px 4px;
  border: 1px solid var(--line);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--line);
  background: var(--bg);
}

/* 测评机多选卡片样式 */
.judger-select-box {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.judger-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg);
  border: 1px solid var(--line);
}
.judger-count {
  font-size: 13px;
  color: var(--ink-secondary);
}
.judger-count b {
  color: var(--ink);
  font-weight: 600;
}
.judger-presets {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.btn-preset {
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
  border-radius: var(--pill);
  transition: all 0.15s ease;
}
.btn-preset:hover {
  background: var(--button);
  color: #fff;
  border-color: var(--button);
}
.btn-preset-danger:hover {
  background: var(--danger);
  color: #fff;
  border-color: var(--danger);
}
.judger-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
}
.judger-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1.5px solid var(--line);
  background: var(--surface);
  cursor: pointer;
  user-select: none;
  transition: all 0.15s ease;
}
.judger-card:hover {
  border-color: var(--ink-tertiary);
}
.judger-card.active {
  border-color: var(--button);
  background: #fdfdfd;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}
.judger-checkbox {
  margin-top: 3px;
  width: 16px;
  height: 16px;
  accent-color: var(--button);
  cursor: pointer;
}
.judger-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}
.judger-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.judger-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.judger-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.judger-card.active .judger-badge {
  background: var(--button);
  color: #fff;
  border-color: var(--button);
}
.judger-desc {
  font-size: 12px;
  color: var(--ink-tertiary);
  line-height: 1.3;
}
</style>
