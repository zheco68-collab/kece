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

const totalDataBytes = computed(() => {
  return cases.value.reduce((acc, c) => acc + (c.inputSize || 0) + (c.outputSize || 0), 0)
})

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
      <div class="card form-card testcase-panel">
        <div class="card-head testcase-head">
          <div class="testcase-title-area">
            <h2 class="card-title">测试用例与评测点</h2>
            <div class="testcase-stats-pills">
              <span class="stat-pill">
                <span class="stat-label">总测试点</span>
                <span class="stat-val">{{ cases.length }} 组</span>
              </span>
              <span class="stat-pill" v-if="sampleCasesCount > 0">
                <span class="stat-label">公开样例</span>
                <span class="stat-val sample-val">{{ sampleCasesCount }} 组</span>
              </span>
              <span class="stat-pill" v-if="totalDataBytes > 0">
                <span class="stat-label">数据集总体积</span>
                <span class="stat-val">{{ formatBytes(totalDataBytes) }}</span>
              </span>
            </div>
          </div>
          <div class="testcase-actions">
            <button
              v-if="isEdit"
              class="btn btn-secondary btn-sm btn-icon"
              type="button"
              @click="openZipModal"
            >
              <span class="icon">📦</span> 批量导入 Zip
            </button>
            <button
              v-if="isEdit && cases.length > 0"
              class="btn btn-ghost btn-sm btn-icon"
              type="button"
              @click="exportZip"
            >
              <span class="icon">📥</span> 导出测试包
            </button>
            <button class="btn btn-primary btn-sm btn-icon" type="button" @click="addCase">
              <span class="icon">➕</span> 添加测试点
            </button>
          </div>
        </div>

        <!-- 存储说明条 -->
        <div class="testcase-banner">
          <span class="banner-icon">💡</span>
          <span class="banner-text">
            系统已全面升级为<b>本地文件流式存储架构</b>。评测点数据独立保存在服务器磁盘中，无论测试点多大都不会拖慢数据库与消息队列。支持单文件测试或上传 Zip 压缩包（成对的 <code>*.in</code> 与 <code>*.out</code>）一键导入。
          </span>
        </div>

        <p v-if="cases.length === 0" class="muted empty-tip">
          暂无测试点，你可以点击上方“批量导入 Zip”或“添加测试点”创建。
        </p>

        <div
          v-for="(c, idx) in cases"
          :key="c.clientId"
          class="case-card"
          :class="{ 'is-sample-card': c.isSample, 'is-large-card': c.isLarge }"
        >
          <div class="case-head">
            <div class="case-num-badge">
              <span class="case-seq-tag">#{{ c.caseNum || idx + 1 }}</span>
            </div>

            <label class="sample-toggle" title="勾选后此用例将作为示例展示在学生题目详情页中">
              <input v-model="c.isSample" type="checkbox" class="toggle-checkbox" />
              <span class="toggle-label" :class="{ active: c.isSample }">
                {{ c.isSample ? '🌟 题面公开样例' : '🔒 隐藏评测点' }}
              </span>
            </label>

            <div class="case-meta-group">
              <div class="score-input-wrap" title="该测试点评测分值">
                <span class="score-label">分值</span>
                <input
                  v-model.number="c.scoreWeight"
                  type="number"
                  min="1"
                  max="100"
                  class="input score-input"
                />
              </div>

              <div class="file-size-badge" v-if="c.inputSize || c.outputSize">
                <span>In: {{ formatBytes(c.inputSize) }}</span>
                <span class="sep">|</span>
                <span>Out: {{ formatBytes(c.outputSize) }}</span>
              </div>

              <span v-if="c.isLarge" class="large-file-tag">
                ⚡ 超大数据集
              </span>
            </div>

            <div class="case-ops">
              <button
                v-if="c.id"
                class="btn btn-ghost btn-xs file-dl-btn"
                type="button"
                title="下载输入文件"
                @click="downloadInput(c.id)"
              >
                📥 输入 (.in)
              </button>
              <button
                v-if="c.id"
                class="btn btn-ghost btn-xs file-dl-btn"
                type="button"
                title="下载输出文件"
                @click="downloadOutput(c.id)"
              >
                📥 输出 (.out)
              </button>
              <button class="btn btn-ghost btn-sm case-remove" type="button" @click="removeCase(c.clientId)">
                删除
              </button>
            </div>
          </div>

          <!-- 大文件提示区 -->
          <div v-if="c.isLarge" class="large-case-notice">
            <div class="notice-info">
              <span class="notice-icon">💾</span>
              <div>
                <div class="notice-title">该测试点数据量庞大（{{ formatBytes(Math.max(c.inputSize || 0, c.outputSize || 0)) }}），已由服务器文件系统直接托管</div>
                <div class="notice-desc">为避免浏览器主线程由于加载数万行文本而卡死崩溃，网页端不作全量渲染。评测机将在判题时通过文件流管道直接接入。</div>
              </div>
            </div>
            <div class="notice-actions" v-if="c.id">
              <button class="btn btn-secondary btn-sm" type="button" @click="downloadInput(c.id)">
                📥 查看完整输入文件
              </button>
              <button class="btn btn-secondary btn-sm" type="button" @click="downloadOutput(c.id)">
                📥 查看完整输出文件
              </button>
            </div>
          </div>

          <!-- 常规文本编辑区 -->
          <div v-else class="case-inputs-grid">
            <FormField label="标准输入 (stdin)" hint="可为空">
              <textarea
                v-model="c.input"
                class="input textarea mono-area"
                placeholder="测试用例标准输入数据（若为空表示该测试点无输入）"
              />
            </FormField>
            <FormField label="期望输出 (stdout)" required>
              <textarea
                v-model="c.expectedOutput"
                class="input textarea mono-area"
                placeholder="标准预期输出数据（判题机将忽略末尾空格与空行换行）"
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
    <div v-if="showZipModal" class="modal-backdrop" @click.self="showZipModal = false">
      <div class="modal-card">
        <div class="modal-head">
          <div class="modal-title-row">
            <span class="modal-icon">📦</span>
            <h3 class="modal-title">批量导入测试点压缩包 (Zip)</h3>
          </div>
          <button class="btn-close" type="button" @click="showZipModal = false">✕</button>
        </div>

        <div class="modal-body">
          <!-- 拖拽上传区域 -->
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
              <span class="upload-icon">📁</span>
              <span class="upload-main-text">点击选择文件，或将 Zip 压缩包拖拽至此</span>
              <span class="upload-sub-text">支持任意大小的测试数据压缩包（.zip 格式）</span>
            </div>
            <div v-else class="file-selected-box">
              <span class="zip-tag">ZIP</span>
              <div class="file-selected-info">
                <span class="file-selected-name">{{ zipFile.name }}</span>
                <span class="file-selected-size">{{ formatBytes(zipFile.size) }}</span>
              </div>
              <button class="btn btn-ghost btn-xs remove-file-btn" type="button" @click.stop="zipFile = null">
                重新选择
              </button>
            </div>
          </div>

          <!-- 导入选项 -->
          <div class="import-options">
            <label class="opt-row">
              <input v-model="zipReplace" type="checkbox" class="opt-checkbox" />
              <span class="opt-text">
                <b>覆盖已有测试点</b>（清空该题目原有旧测试集，以本次上传的 Zip 文件完全重建）
              </span>
            </label>

            <label class="opt-row">
              <input v-model="zipFirstAsSample" type="checkbox" class="opt-checkbox" />
              <span class="opt-text">
                <b>首组测试点设为公开样例</b>（或文件名含有 sample 的用例自动公开供题面展示）
              </span>
            </label>
          </div>

          <!-- 说明帮助 -->
          <div class="zip-help-card">
            <div class="help-title">💡 压缩包文件命名规范：</div>
            <ul class="help-list">
              <li>成对包含输入与期望输出文件，例如：<code>1.in</code> 与 <code>1.out</code>（或 <code>1.ans</code>）。</li>
              <li>亦支持标准命名：<code>input1.txt</code> 与 <code>output1.txt</code>，或 <code>data1.in</code> 与 <code>data1.out</code>。</li>
              <li>支持嵌套在子文件夹内，系统会自动提取并按文件名数字自然排序（1, 2, ... 10）。</li>
            </ul>
          </div>
        </div>

        <div class="modal-foot">
          <button class="btn btn-ghost" type="button" :disabled="zipUploading" @click="showZipModal = false">
            取消
          </button>
          <button
            class="btn btn-primary"
            type="button"
            :disabled="!zipFile || zipUploading"
            @click="submitZipImport"
          >
            {{ zipUploading ? '正在解压并解析中…' : '确认上传并导入' }}
          </button>
        </div>
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
/* 测试用例与评测点模块增强样式 */
.testcase-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.testcase-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 0;
}
.testcase-title-area {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.testcase-stats-pills {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.stat-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 9999px;
  font-size: 12px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}
.stat-label {
  opacity: 0.75;
}
.stat-val {
  font-weight: 600;
  color: var(--ink);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.stat-val.sample-val {
  color: #10b981;
}
.testcase-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.btn-icon {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.testcase-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 14px;
  background: rgba(99, 102, 241, 0.06);
  border: 1px solid rgba(99, 102, 241, 0.2);
  border-radius: 8px;
  font-size: 13px;
  color: var(--ink);
  line-height: 1.5;
}
.banner-icon {
  font-size: 16px;
  line-height: 1.2;
}
.banner-text code {
  padding: 1px 4px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
  font-family: monospace;
}
.empty-tip {
  padding: 32px 16px;
  text-align: center;
  background: var(--bg);
  border: 1px dashed var(--line);
  border-radius: 8px;
  font-size: 14px;
}

.case-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 14px;
  background: var(--surface);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  transition: all 0.2s ease;
}
.case-card:hover {
  border-color: rgba(99, 102, 241, 0.4);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}
.case-card.is-sample-card {
  border-left: 3.5px solid #10b981;
}
.case-card.is-large-card {
  border-left: 3.5px solid #f59e0b;
}

.case-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}
.case-num-badge {
  display: flex;
  align-items: center;
}
.case-seq-tag {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
  padding: 2px 8px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.sample-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}
.toggle-checkbox {
  width: 15px;
  height: 15px;
  accent-color: #10b981;
  cursor: pointer;
}
.toggle-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-secondary);
}
.toggle-label.active {
  color: #10b981;
}

.case-meta-group {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.score-input-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-secondary);
}
.score-input {
  width: 58px;
  padding: 2px 6px;
  text-align: center;
  font-size: 12px;
  font-weight: 600;
  height: 28px;
}
.file-size-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  padding: 2px 8px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 4px;
  color: var(--ink-secondary);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.file-size-badge .sep {
  opacity: 0.4;
}
.large-file-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(245, 158, 11, 0.12);
  color: #d97706;
  border: 1px solid rgba(245, 158, 11, 0.3);
}

.case-ops {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.file-dl-btn {
  font-size: 12px;
  padding: 2px 8px;
  height: 26px;
  color: var(--ink-secondary);
}
.file-dl-btn:hover {
  color: var(--button);
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
.mono-area {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, Courier, monospace;
  font-size: 13px;
  min-height: 96px;
}

.large-case-notice {
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.notice-info {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex: 1;
}
.notice-icon {
  font-size: 24px;
  line-height: 1;
}
.notice-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.notice-desc {
  font-size: 12px;
  color: var(--ink-secondary);
  line-height: 1.4;
}
.notice-actions {
  display: flex;
  gap: 8px;
}

.textarea {
  height: auto;
  min-height: 88px;
  padding: 10px 12px;
  resize: vertical;
  line-height: 1.5;
  font-family: inherit;
}
.form-actions {
  display: flex;
  gap: 12px;
}

/* Modal 弹窗样式 */
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  animation: fadeIn 0.15s ease-out;
}
.modal-card {
  width: 100%;
  max-width: 580px;
  background: var(--surface);
  border-radius: 14px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.2);
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: scaleUp 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes scaleUp {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
}
.modal-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.modal-icon {
  font-size: 20px;
}
.modal-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.btn-close {
  background: transparent;
  border: none;
  font-size: 18px;
  color: var(--ink-tertiary);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.15s ease;
}
.btn-close:hover {
  background: var(--bg);
  color: var(--ink);
}

.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.drop-zone {
  border: 2px dashed var(--line);
  border-radius: 12px;
  padding: 32px 20px;
  text-align: center;
  background: var(--bg);
  cursor: pointer;
  transition: all 0.2s ease;
  user-select: none;
}
.drop-zone:hover,
.drop-zone.is-dragover {
  border-color: var(--button);
  background: rgba(99, 102, 241, 0.04);
}
.drop-zone.has-file {
  border-color: #10b981;
  background: rgba(16, 185, 129, 0.04);
  padding: 18px 20px;
}
.hidden-file-input {
  display: none;
}
.drop-prompt {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.upload-icon {
  font-size: 36px;
}
.upload-main-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.upload-sub-text {
  font-size: 12px;
  color: var(--ink-secondary);
}

.file-selected-box {
  display: flex;
  align-items: center;
  gap: 12px;
}
.zip-tag {
  font-size: 12px;
  font-weight: 700;
  padding: 4px 8px;
  background: #10b981;
  color: #fff;
  border-radius: 6px;
  font-family: monospace;
}
.file-selected-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  flex: 1;
  min-width: 0;
}
.file-selected-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
.file-selected-size {
  font-size: 12px;
  color: var(--ink-secondary);
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.remove-file-btn {
  font-size: 12px;
}

.import-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  background: var(--bg);
  border-radius: 8px;
  border: 1px solid var(--line);
}
.opt-row {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.opt-checkbox {
  width: 16px;
  height: 16px;
  accent-color: var(--button);
  cursor: pointer;
}
.opt-text {
  font-size: 13px;
  color: var(--ink);
}

.zip-help-card {
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px 14px;
}
.help-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 6px;
}
.help-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--ink-secondary);
  line-height: 1.6;
}
.help-list code {
  background: var(--bg);
  padding: 1px 4px;
  border-radius: 4px;
  border: 1px solid var(--line);
  font-family: monospace;
}

.modal-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--line);
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
