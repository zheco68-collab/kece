<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { databaseApi } from '@/api/database'
import { ApiError } from '@/api/request'
import FormField from '@/components/FormField.vue'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import DbSchemaViewer from '@/components/DbSchemaViewer.vue'
import DbSampleViewer from '@/components/DbSampleViewer.vue'
import { generateMarkdownTemplate } from '@/utils/sqlSchemaParser'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import type { DbQuestionType } from '@/types'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const { confirm } = useConfirm()

const questionId = computed(() => {
  const raw = route.params.id
  return raw ? Number(raw) : null
})
const isEdit = computed(() => questionId.value !== null)

const submitting = ref(false)
const loading = ref(false)
const pageError = ref('')
const showLivePreview = ref(false)

const form = reactive({
  title: '',
  description: '',
  dialect: 'MYSQL',
  type: 'SELECT' as DbQuestionType,
  status: 1,
  schemaSql: '',
  referenceSql: '',
  verifySql: '',
  orderSensitive: false,
})

const formErrors = reactive({
  title: '',
  description: '',
  schemaSql: '',
  referenceSql: '',
  verifySql: '',
})

/** 测试点：clientId 区分本地条目；保存时整包提交，后端整表替换。 */
interface SeedRow {
  clientId: number
  seedSql: string
  expectedOutput: string
  isSample: boolean
}

const seeds = ref<SeedRow[]>([])
let seedSeq = 0

function addSeed() {
  seeds.value.push({
    clientId: ++seedSeq,
    seedSql: '',
    expectedOutput: '',
    isSample: seeds.value.length === 0, // 首个测试点默认设为样例
  })
}

function removeSeed(clientId: number) {
  seeds.value = seeds.value.filter((s) => s.clientId !== clientId)
}

const sampleCasesList = computed(() => {
  return seeds.value
    .filter((s) => s.isSample && s.seedSql.trim())
    .map((s, idx) => ({
      id: s.clientId,
      caseOrder: idx + 1,
      seedSql: s.seedSql,
      expectedOutput: s.expectedOutput,
      isSample: 1,
    }))
})

async function handleGenerateTemplate() {
  if (!form.schemaSql.trim()) {
    toast.error('请先在上方填写建表 DDL，以便生成表结构与用例模板')
    return
  }
  const sampleSeed =
    seeds.value.find((s) => s.isSample && s.seedSql.trim()) ||
    seeds.value.find((s) => s.seedSql.trim())
  const template = generateMarkdownTemplate(
    form.schemaSql,
    sampleSeed?.seedSql,
    form.title,
    sampleSeed?.expectedOutput,
  )

  if (form.description.trim()) {
    const ok = await confirm({
      title: '生成标准题面模板',
      message: '检测到当前题目描述已有内容。是否覆盖现有内容？（点击「确定覆盖」替换，点击「取消」则追加到末尾）',
      confirmText: '确定覆盖',
      cancelText: '追加至末尾',
      danger: true,
    })
    if (ok) {
      form.description = template
      toast.success('已覆盖生成标准题面模板')
    } else {
      form.description += '\n\n' + template
      toast.success('已将标准模板追加至描述末尾')
    }
  } else {
    form.description = template
    toast.success('已自动生成标准题面与示例表格')
  }
}

async function loadQuestion() {
  if (!questionId.value) return
  loading.value = true
  pageError.value = ''
  try {
    const q = await databaseApi.detail(questionId.value)
    form.title = q.title
    form.description = q.description
    form.dialect = q.dialect
    form.type = q.type
    form.status = q.status
    form.schemaSql = q.schemaSql
    form.referenceSql = q.referenceSql ?? ''
    form.verifySql = q.verifySql ?? ''
    form.orderSensitive = q.orderSensitive
    seeds.value = q.testCases.map((tc) => ({
      clientId: ++seedSeq,
      seedSql: tc.seedSql,
      expectedOutput: tc.expectedOutput ?? '',
      isSample: tc.isSample === 1,
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
  formErrors.schemaSql = form.schemaSql.trim() ? '' : '建表语句不能为空'
  formErrors.referenceSql = form.referenceSql.trim() ? '' : '标准答案 SQL 不能为空'
  formErrors.verifySql = form.type === 'DML' && !form.verifySql.trim() ? 'DML 题需要校验查询' : ''
  const badSeed = seeds.value.find((s) => !s.seedSql.trim())
  if (badSeed) {
    toast.error('测试点的灌数据 SQL 不能为空')
    return false
  }
  return (
    !formErrors.title &&
    !formErrors.description &&
    !formErrors.schemaSql &&
    !formErrors.referenceSql &&
    !formErrors.verifySql
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
      dialect: form.dialect,
      type: form.type,
      status: form.status,
      schemaSql: form.schemaSql,
      referenceSql: form.referenceSql,
      verifySql: form.type === 'DML' ? form.verifySql : undefined,
      orderSensitive: form.orderSensitive,
      testCases: seeds.value.map((s) => ({
        seedSql: s.seedSql,
        expectedOutput: s.expectedOutput.trim() || undefined,
        isSample: s.isSample ? 1 : 0,
      })),
    }
    if (questionId.value) {
      await databaseApi.update(questionId.value, body)
    } else {
      await databaseApi.create(body)
    }
    toast.success(isEdit.value ? '题目已更新' : '题目已创建')
    router.push('/admin/db-questions')
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
    <RouterLink class="back" to="/admin/db-questions">← 返回数据库题管理</RouterLink>
    <h1 class="page-title">{{ isEdit ? '编辑数据库题' : '新建数据库题' }}</h1>

    <div v-if="loading" class="state-card card"><div class="loading-spinner"></div><p class="muted">正在加载数据库题配置…</p></div>
    <p v-if="pageError" class="submit-error">{{ pageError }}</p>

    <div v-if="!loading" class="form-cards">
      <!-- 1. 基本信息卡片 -->
      <div class="card form-card">
        <h2 class="card-title">基本信息</h2>
        <FormField label="标题" required :error="formErrors.title">
          <input v-model="form.title" class="input" placeholder="题目标题，例如：查询研发部员工" />
        </FormField>
        <div class="field-row">
          <FormField label="题型" required>
            <select v-model="form.type" class="input">
              <option value="SELECT">查询题 (SELECT)</option>
              <option value="DML">数据变更题 (UPDATE / INSERT / DELETE)</option>
            </select>
          </FormField>
          <FormField label="SQL 方言">
            <select v-model="form.dialect" class="input">
              <option value="MYSQL">MySQL 8.0</option>
            </select>
          </FormField>
          <FormField label="发布状态">
            <select v-model="form.status" class="input">
              <option :value="1">公开 (学生端可见)</option>
              <option :value="0">私有 (仅出题人可见)</option>
            </select>
          </FormField>
        </div>
      </div>

      <!-- 2. SQL 定义与沙箱环境 -->
      <div class="card form-card">
        <div class="card-head">
          <h2 class="card-title">SQL 定义与环境</h2>
          <label class="order-check">
            <input v-model="form.orderSensitive" type="checkbox" />
            <span>行序敏感（判题时要求结果集行顺序完全一致）</span>
          </label>
        </div>

        <FormField
          label="建表 DDL"
          required
          :error="formErrors.schemaSql"
          hint="初始化表结构 SQL，该题所有测试用例共用此结构，学生题面完全可见"
        >
          <textarea
            v-model="form.schemaSql"
            class="input textarea sql-area"
            placeholder="CREATE TABLE emp (id INT PRIMARY KEY, name VARCHAR(32), dept VARCHAR(16), salary DECIMAL(10,2));"
          />
        </FormField>

        <FormField
          label="标准答案 SQL"
          required
          :error="formErrors.referenceSql"
          hint="出题人标准 SQL，判题机据此在沙箱中动态计算测试点标准结果集（仅管理员与出题人可见）"
        >
          <textarea
            v-model="form.referenceSql"
            class="input textarea sql-area"
            placeholder="SELECT id, name FROM emp WHERE dept = 'RD'"
          />
        </FormField>

        <FormField
          v-if="form.type === 'DML'"
          label="校验查询 SQL"
          required
          :error="formErrors.verifySql"
          hint="数据变更题专用：在用户 SQL 执行后运行该校验 SELECT，通过比对数据表查询结果判定作答是否正确"
        >
          <textarea
            v-model="form.verifySql"
            class="input textarea sql-area"
            placeholder="SELECT id, name, dept, salary FROM emp ORDER BY id"
          />
        </FormField>
      </div>

      <!-- 3. 数据测试点 -->
      <div class="card form-card">
        <div class="card-head">
          <div>
            <h2 class="card-title">数据测试点</h2>
            <p class="card-desc">为评测沙箱提供初始化数据（INSERT 等）。标记为“公开样例”的数据将展示在学生题面上。</p>
          </div>
          <button class="btn btn-outline btn-sm" type="button" @click="addSeed">+ 添加测试点</button>
        </div>

        <p v-if="seeds.length === 0" class="muted empty-tip">暂无测试点，请点击右上角按钮添加。</p>

        <div v-for="s in seeds" :key="s.clientId" class="case-card">
          <div class="case-head">
            <div class="case-title-wrap">
              <span class="case-num">测试点 #{{ s.clientId }}</span>
              <label class="sample-check">
                <input v-model="s.isSample" type="checkbox" />
                <span>公开样例（展示在学生题面中）</span>
              </label>
            </div>
            <button class="btn btn-ghost btn-sm case-remove" type="button" @click="removeSeed(s.clientId)">
              删除
            </button>
          </div>

          <FormField label="初始数据 SQL" required hint="评测时先执行该语句向表中写入数据">
            <textarea
              v-model="s.seedSql"
              class="input textarea sql-area"
              placeholder="INSERT INTO emp VALUES (1, 'Alice', 'RD', 10000.00), (2, 'Bob', 'RD', 12000.00);"
            />
          </FormField>

          <FormField
            label="期望输出结果 (可选)"
            hint="公开样例建议填写：可直接填写 Markdown 表格（如 | id | name |）或标准 JSON，题面将直接渲染输出表格供学生对照"
          >
            <textarea
              v-model="s.expectedOutput"
              class="input textarea sql-area"
              placeholder='| id | name |&#10;| 1  | Alice |&#10;| 2  | Bob   |'
            />
          </FormField>
        </div>
      </div>

      <!-- 4. 题目描述 -->
      <div class="card form-card">
        <div class="card-head">
          <div>
            <h2 class="card-title">题目描述</h2>
            <p class="card-desc">编写题目的业务背景、查询要求与说明。</p>
          </div>
          <button
            type="button"
            class="btn btn-outline btn-sm"
            title="根据上方填写的建表 DDL 与测试点数据，自动生成包含表结构说明与样例表格的规范题面"
            @click="handleGenerateTemplate"
          >
            一键导入上方表结构与用例模板
          </button>
        </div>
        <FormField label="题面正文" required :error="formErrors.description">
          <MarkdownEditor v-model="form.description" placeholder="支持 Markdown 语法编写题目描述" />
        </FormField>
      </div>

      <!-- 5. 渲染效果实时预览卡片 (可折叠) -->
      <div v-if="form.schemaSql.trim() || sampleCasesList.length > 0" class="card form-card preview-card">
        <div class="card-head preview-head">
          <div>
            <h2 class="card-title">题面渲染效果预览</h2>
            <p class="card-desc">预览学生端看到的表结构字典与测试样例表格渲染效果</p>
          </div>
          <button
            type="button"
            class="btn btn-outline btn-sm"
            @click="showLivePreview = !showLivePreview"
          >
            {{ showLivePreview ? '收起预览' : '展开预览' }}
          </button>
        </div>

        <div v-if="showLivePreview" class="preview-content">
          <div v-if="form.schemaSql.trim()" class="preview-item">
            <DbSchemaViewer compact :schema-sql="form.schemaSql" />
          </div>

          <div v-if="sampleCasesList.length > 0 && form.schemaSql.trim()" class="preview-item">
            <DbSampleViewer :sample-cases="sampleCasesList" :schema-sql="form.schemaSql" />
          </div>
        </div>
      </div>

      <!-- 底部提交操作栏 -->
      <div class="form-actions">
        <button class="btn btn-primary" type="button" :disabled="submitting" @click="save">
          {{ submitting ? '保存中…' : '保存题目' }}
        </button>
        <button class="btn btn-outline" type="button" :disabled="submitting" @click="router.back()">
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
  border-radius: 0;
}
.card-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.card-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ink-secondary);
  font-weight: normal;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
  flex-wrap: wrap;
}
.field-row {
  display: flex;
  gap: 20px;
}
.field-row > * {
  flex: 1;
}
.order-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-secondary);
  cursor: pointer;
}
.order-check input {
  cursor: pointer;
}
.empty-tip {
  margin: 8px 0 14px;
  font-size: 13px;
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
  justify-content: space-between;
  margin-bottom: 12px;
}
.case-title-wrap {
  display: flex;
  align-items: center;
  gap: 14px;
}
.case-num {
  font-size: 13px;
  font-weight: 700;
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
.case-remove {
  color: var(--danger);
}
.textarea {
  height: auto;
  min-height: 84px;
  padding: 10px 12px;
  resize: vertical;
  line-height: 1.5;
  font-family: inherit;
  border-radius: 0;
}
.sql-area {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
}
.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
.preview-card {
  border: 1px dashed var(--line);
  background: var(--bg);
}
.preview-head {
  margin-bottom: 0;
}
.preview-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
}
.preview-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.preview-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
}
</style>
