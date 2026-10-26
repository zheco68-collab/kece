<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { questionApi } from '@/api/question'
import { ApiError } from '@/api/request'
import FormField from '@/components/FormField.vue'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import { useToast } from '@/composables/useToast'
import type { Difficulty, TestCase } from '@/types'

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

const testCaseCount = ref(0)
const sampleCasesCount = ref(0)
const totalDataBytes = ref(0)
const testCases = ref<TestCase[]>([])
const loadingCases = ref(false)
const togglingCaseId = ref<number | null>(null)
const settingSamples = ref(false)

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

async function loadTestCases() {
  if (!questionId.value) return
  loadingCases.value = true
  try {
    const list = await questionApi.listTestCases(questionId.value)
    testCases.value = list || []
    testCaseCount.value = testCases.value.length
    sampleCasesCount.value = testCases.value.filter((c) => c.isSample === 1).length
  } catch (err) {
    console.error('加载测试点列表失败', err)
  } finally {
    loadingCases.value = false
  }
}

async function toggleSample(item: TestCase) {
  if (!questionId.value || togglingCaseId.value !== null) return
  const targetSample = item.isSample === 1 ? false : true
  togglingCaseId.value = item.id
  try {
    await questionApi.toggleTestCaseSample(questionId.value, item.id, targetSample)
    item.isSample = targetSample ? 1 : 0
    sampleCasesCount.value = testCases.value.filter((c) => c.isSample === 1).length
    toast.success(`测试点 #${item.caseNum} 已${targetSample ? '设为' : '取消'}公开样例`)
  } catch (err: any) {
    toast.error(err.message || '修改样例状态失败')
  } finally {
    togglingCaseId.value = null
  }
}

async function applySamplePreset(preset: 'first1' | 'first2' | 'all' | 'none') {
  if (!questionId.value || testCases.value.length === 0 || settingSamples.value) return
  settingSamples.value = true
  let targetIds: number[] = []
  if (preset === 'first1') {
    targetIds = [testCases.value[0].id]
  } else if (preset === 'first2') {
    targetIds = testCases.value.slice(0, 2).map((c) => c.id)
  } else if (preset === 'all') {
    targetIds = testCases.value.map((c) => c.id)
  } else if (preset === 'none') {
    targetIds = []
  }
  try {
    await questionApi.setSampleCaseIds(questionId.value, targetIds)
    const targetSet = new Set(targetIds)
    testCases.value.forEach((c) => {
      c.isSample = targetSet.has(c.id) ? 1 : 0
    })
    sampleCasesCount.value = targetIds.length
    toast.success('样例设置已生效')
  } catch (err: any) {
    toast.error(err.message || '更新样例设置失败')
  } finally {
    settingSamples.value = false
  }
}

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
  } catch (err: any) {
    toast.error(err instanceof ApiError ? err.message : (err?.message || 'Zip 导入失败，请检查文件格式或网络连接'))
  } finally {
    zipUploading.value = false
  }
}

const zipExporting = ref(false)

async function exportZip() {
  if (!questionId.value || zipExporting.value) return
  zipExporting.value = true
  try {
    await questionApi.exportTestCasesZip(questionId.value)
    toast.success('测试点 Zip 导出成功')
  } catch (err: any) {
    toast.error(err.message || '导出 Zip 失败，请检查登录状态')
  } finally {
    zipExporting.value = false
  }
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
    const summary = await questionApi.getTestCaseSummary(questionId.value)
    testCaseCount.value = summary.totalCount || 0
    sampleCasesCount.value = summary.sampleCount || 0
    totalDataBytes.value = summary.totalBytes || 0
    await loadTestCases()
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
    if (questionId.value) {
      await questionApi.update(questionId.value, body)
    } else {
      await questionApi.create(body)
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
            <span v-if="testCaseCount > 0" class="muted case-count-tag">
              共 {{ testCaseCount }} 组测试点<span v-if="sampleCasesCount > 0">（含 {{ sampleCasesCount }} 组公开样例）</span>
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
              v-if="isEdit && testCaseCount > 0"
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="zipExporting"
              @click="exportZip"
            >
              {{ zipExporting ? '导出中…' : '导出 Zip' }}
            </button>
          </div>
        </div>

        <div v-if="!isEdit" class="muted testcase-empty">
          请先创建并保存题目基本信息，随后即可上传测试点 Zip 压缩包。
        </div>

        <div v-else-if="testCaseCount === 0" class="testcase-empty-box">
          <p class="muted">
            暂未上传测试点。请点击右上角「导入 Zip」上传成对的测试数据包（*.in / *.out）。
          </p>
        </div>

        <div v-else class="testcase-status-box">
          <div class="testcase-meta-line">
            <span>托管测试点：<strong>{{ testCaseCount }}</strong> 组</span>
            <span>公开样例：<strong>{{ sampleCasesCount }}</strong> 组</span>
            <span v-if="totalDataBytes > 0">数据总体积：<strong>{{ formatBytes(totalDataBytes) }}</strong></span>
          </div>
          <p class="muted testcase-desc">
            测试点数据已统一由服务器文件系统直接托管。为提升后台响应与编辑性能，页面不展示详细用例文本；如需查看或编辑测试用例，请点击右上角「导出 Zip」下载数据包在本地查看，编辑后点击「导入 Zip」重新上传即可。
          </p>

          <!-- 题面公开样例配置面板 -->
          <div class="sample-manage-panel">
            <div class="sample-toolbar">
              <div class="sample-title-wrap">
                <span class="sample-title">题面公开样例配置</span>
                <span class="sample-hint muted">勾选的测试点将作为参考样例公开展示在学生端题面中</span>
              </div>
              <div class="sample-presets">
                <span class="preset-label muted">快捷设置：</span>
                <button
                  type="button"
                  class="btn-preset"
                  :disabled="settingSamples"
                  @click="applySamplePreset('first1')"
                >
                  仅首组
                </button>
                <button
                  type="button"
                  class="btn-preset"
                  :disabled="settingSamples"
                  @click="applySamplePreset('first2')"
                >
                  前 2 组
                </button>
                <button
                  type="button"
                  class="btn-preset"
                  :disabled="settingSamples"
                  @click="applySamplePreset('all')"
                >
                  全部设为样例
                </button>
                <button
                  type="button"
                  class="btn-preset btn-preset-danger"
                  :disabled="settingSamples"
                  @click="applySamplePreset('none')"
                >
                  清空样例
                </button>
              </div>
            </div>

            <div v-if="loadingCases" class="muted sample-loading">
              正在加载测试点列表…
            </div>

            <div v-else class="sample-grid">
              <div
                v-for="tc in testCases"
                :key="tc.id"
                class="sample-item-card"
                :class="{ 'is-active-sample': tc.isSample === 1 }"
              >
                <label class="sample-checkbox-label">
                  <input
                    type="checkbox"
                    class="sample-checkbox"
                    :checked="tc.isSample === 1"
                    :disabled="togglingCaseId === tc.id"
                    @change="toggleSample(tc)"
                  />
                  <span class="sample-case-num">#{{ tc.caseNum }}</span>
                </label>

                <div class="sample-item-info">
                  <span
                    class="sample-status-tag"
                    :class="tc.isSample === 1 ? 'tag-sample' : 'tag-private'"
                  >
                    {{ tc.isSample === 1 ? '公开样例' : '私有评测点' }}
                  </span>
                  <span class="sample-item-size muted">
                    {{ formatBytes(tc.inputSize) }} / {{ formatBytes(tc.outputSize) }}
                  </span>
                </div>

                <button
                  type="button"
                  class="btn-sample-toggle"
                  :class="{ 'btn-sample-active': tc.isSample === 1 }"
                  :disabled="togglingCaseId === tc.id"
                  @click="toggleSample(tc)"
                >
                  {{ togglingCaseId === tc.id ? '同步中…' : (tc.isSample === 1 ? '取消' : '设为样例') }}
                </button>
              </div>
            </div>
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
.sample-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-secondary);
  cursor: pointer;
}
.testcase-empty {
  font-size: 13px;
  line-height: 1.6;
}
.testcase-empty-box {
  border: 1px dashed var(--line);
  padding: 24px;
  text-align: center;
  background: var(--bg);
}
.testcase-empty-box p {
  margin: 0;
  font-size: 13px;
}
.testcase-status-box {
  border: 1px solid var(--line);
  padding: 16px 20px;
  background: var(--bg);
}
.testcase-meta-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 20px;
  font-size: 13px;
  color: var(--ink);
  margin-bottom: 10px;
}
.testcase-meta-line strong {
  font-weight: 600;
}
.testcase-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
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
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn-preset:hover:not(:disabled) {
  background: var(--button);
  color: #fff;
  border-color: var(--button);
}
.btn-preset-danger:hover:not(:disabled) {
  background: var(--danger);
  color: #fff;
  border-color: var(--danger);
}
.btn-preset:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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

/* 题面公开样例管理面板（无大文本，极简直角设计） */
.sample-manage-panel {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sample-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}
.sample-title-wrap {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.sample-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}
.sample-hint {
  font-size: 12px;
  color: var(--ink-secondary);
}
.sample-presets {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.preset-label {
  font-size: 12px;
}
.sample-loading {
  font-size: 13px;
  padding: 12px 0;
}
.sample-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 10px;
  max-height: 380px;
  overflow-y: auto;
  padding-right: 2px;
}
.sample-item-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  transition: all 0.15s ease;
}
.sample-item-card:hover {
  border-color: var(--ink-secondary);
}
.sample-item-card.is-active-sample {
  border-color: var(--ink);
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}
.sample-checkbox-label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.sample-checkbox {
  width: 15px;
  height: 15px;
  accent-color: var(--button);
  cursor: pointer;
}
.sample-case-num {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-weight: 700;
  font-size: 13px;
  color: var(--ink);
}
.sample-item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}
.sample-status-tag {
  align-self: flex-start;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 0;
  line-height: 1.2;
}
.sample-status-tag.tag-sample {
  background: var(--button);
  color: #fff;
}
.sample-status-tag.tag-private {
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-tertiary);
}
.sample-item-size {
  font-size: 11px;
  white-space: nowrap;
}
.btn-sample-toggle {
  padding: 3px 8px;
  font-size: 12px;
  border: 1px solid var(--line);
  background: var(--bg);
  color: var(--ink-secondary);
  border-radius: 0;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}
.btn-sample-toggle:hover:not(:disabled) {
  border-color: var(--ink);
  color: var(--ink);
}
.btn-sample-toggle.btn-sample-active {
  background: var(--button);
  color: #fff;
  border-color: var(--button);
}
.btn-sample-toggle:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
