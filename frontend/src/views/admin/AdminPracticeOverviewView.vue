<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { practiceApi } from '@/api/practice'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import { copyToClipboard } from '@/utils/clipboard'
import { formatMemory } from '@/utils/submission'
import type { PracticeOverview, StudentAuditDetail, StudentAuditQuestionItem } from '@/types/practice'

const route = useRoute()
const { success, error: toastError } = useToast()
const { confirm } = useConfirm()

const practiceId = computed(() => Number(route.params.id))
const overview = ref<PracticeOverview | null>(null)
const loading = ref(true)
const extending = ref(false)
const finishing = ref(false)
const exporting = ref(false)

// 学生检索
const studentSearchKeyword = ref('')

// 调阅学生作答抽屉
const auditDrawerVisible = ref(false)
const auditLoading = ref(false)
const studentAudit = ref<StudentAuditDetail | null>(null)

let timer: number | null = null

const filteredStudents = computed(() => {
  if (!overview.value?.students) return []
  const kw = studentSearchKeyword.value.trim().toLowerCase()
  if (!kw) return overview.value.students
  return overview.value.students.filter(
    (s) => s.username.toLowerCase().includes(kw) || s.nickname.toLowerCase().includes(kw),
  )
})

function stripOptionPrefix(opt: string): string {
  return (opt || '').replace(/^[A-Za-z][.、)）:：]\s*/, '').trim()
}

function normalizeJudgeValue(val?: string | null): '对' | '错' | null {
  if (!val) return null
  const v = val.trim().toUpperCase()
  if (['对', 'TRUE', 'T', '1', '正确', 'YES', '√', '✓'].includes(v)) return '对'
  if (['错', 'FALSE', 'F', '0', '错误', 'NO', '×', '✕', '✗'].includes(v)) return '错'
  return null
}

function parseAnswerTokens(val?: string | null): string[] {
  if (!val) return []
  return val
    .split(/[,，\s]+/)
    .map((s) => s.trim().toUpperCase())
    .filter(Boolean)
}

function getEffectiveOptions(item: StudentAuditQuestionItem): string[] {
  if (item.objectiveOptions && item.objectiveOptions.length > 0) {
    return item.objectiveOptions
  }
  if (normalizeJudgeValue(item.correctAnswer) !== null) {
    return ['对（正确）', '错（错误）']
  }
  return []
}

function matchesObjectiveOption(
  rawAnswer: string | undefined,
  opt: string,
  optIdx: number,
): boolean {
  if (!rawAnswer || !rawAnswer.trim()) return false
  const letter = String.fromCharCode(65 + optIdx)
  const tokens = parseAnswerTokens(rawAnswer)
  if (tokens.includes(letter)) return true
  if (rawAnswer.trim() === opt.trim() || rawAnswer.trim() === stripOptionPrefix(opt)) return true

  const ansJudge = normalizeJudgeValue(rawAnswer)
  const optJudge =
    normalizeJudgeValue(opt) ||
    (stripOptionPrefix(opt).startsWith('对')
      ? '对'
      : stripOptionPrefix(opt).startsWith('错')
        ? '错'
        : null)
  if (ansJudge && optJudge && ansJudge === optJudge) return true

  return false
}

async function fetchOverview() {
  loading.value = true
  try {
    const res = await practiceApi.overview(practiceId.value)
    overview.value = res
  } catch (e) {
    toastError(e instanceof Error ? e.message : '加载考务大盘数据失败')
  } finally {
    loading.value = false
  }
}

async function handleExtendTime(minutes: number) {
  if (!overview.value) return
  extending.value = true
  try {
    await practiceApi.extend(overview.value.accessCode, minutes)
    success(
      overview.value.status === 'ENDED'
        ? `已重新开启课练并延长 ${minutes} 分钟`
        : `已成功为全班加时 ${minutes} 分钟`,
    )
    await fetchOverview()
  } catch (e) {
    toastError(e instanceof Error ? e.message : '加时操作失败')
  } finally {
    extending.value = false
  }
}

async function handleFinishPractice() {
  if (!overview.value) return
  const ok = await confirm({
    title: '确认提前收卷',
    message: '提前收卷后，所有尚未交卷的学生答卷将被统一锁定归档，确定要立即收卷吗？',
    confirmText: '立即收卷',
    cancelText: '取消',
  })
  if (!ok) return

  finishing.value = true
  try {
    await practiceApi.end(overview.value.accessCode)
    success('课练已成功提前收卷并封盘')
    await fetchOverview()
  } catch (e) {
    toastError(e instanceof Error ? e.message : '收卷失败')
  } finally {
    finishing.value = false
  }
}

async function openStudentAudit(targetUserId: number) {
  auditDrawerVisible.value = true
  auditLoading.value = true
  studentAudit.value = null
  try {
    const res = await practiceApi.auditStudent(practiceId.value, targetUserId)
    studentAudit.value = res
  } catch (e) {
    toastError(e instanceof Error ? e.message : '调阅学生作答详情失败')
  } finally {
    auditLoading.value = false
  }
}

function closeStudentAudit() {
  auditDrawerVisible.value = false
  studentAudit.value = null
}

async function handleExportCsv() {
  if (exporting.value) return
  exporting.value = true
  try {
    await practiceApi.exportCsv(practiceId.value, overview.value?.accessCode)
    success('成绩单 CSV 已导出下载')
  } catch (e) {
    toastError(e instanceof Error ? e.message : '导出成绩单失败')
  } finally {
    exporting.value = false
  }
}

async function copyRoomCode() {
  if (!overview.value?.accessCode) return
  const ok = await copyToClipboard(overview.value.accessCode)
  if (ok) {
    success(`房间口令 ${overview.value.accessCode} 已复制到剪贴板`)
  } else {
    toastError('复制失败，请手动复制')
  }
}

onMounted(() => {
  fetchOverview()
  timer = window.setInterval(() => {
    if (overview.value?.status === 'ACTIVE') {
      fetchOverview()
    }
  }, 10000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="admin-overview-page container">
    <!-- 极简面包屑 -->
    <nav class="nav-trail">
      <router-link to="/admin/practices">随堂课练管理</router-link>
      <span class="sep">/</span>
      <span class="current">考务总览与作答审计</span>
    </nav>

    <!-- 顶栏信息与考务控制操作行 -->
    <div class="header-card card">
      <div class="header-top-row">
        <div class="header-left">
          <span class="status-indicator">
            <span class="status-dot" :class="{ ended: overview?.status === 'ENDED' }" />
            {{ overview?.status === 'ENDED' ? '已结束' : '进行中' }}
          </span>
          <button class="room-code-badge mono" title="点击复制口令" @click="copyRoomCode">
            口令: #{{ overview?.accessCode }} ⧉
          </button>
          <span class="meta-tag mono">
            时长: {{ overview?.durationMinutes ? `${overview.durationMinutes} 分钟` : '不限时' }}
          </span>
        </div>

        <div class="header-actions">
          <button class="btn btn-ghost btn-sm" @click="fetchOverview">↻ 刷新数据</button>
          <button class="btn btn-ghost btn-sm" :disabled="exporting" @click="handleExportCsv">
            {{ exporting ? '导出中…' : '导出成绩单 (CSV)' }}
          </button>
          <router-link
            v-if="overview?.accessCode"
            :to="`/practice/${overview.accessCode}`"
            class="btn btn-ghost btn-sm"
          >
            学生视角 ↗
          </router-link>
          <router-link :to="`/admin/practices/${practiceId}/edit`" class="btn btn-ghost btn-sm">
            编辑课练
          </router-link>

          <button
            v-if="overview && (overview.durationMinutes > 0 || overview.status === 'ENDED')"
            class="btn btn-ghost btn-sm"
            :disabled="extending"
            @click="handleExtendTime(5)"
          >
            {{ overview.status === 'ENDED' ? '+5分钟重开' : '+5分钟加时' }}
          </button>
          <button
            v-if="overview?.status === 'ACTIVE'"
            class="btn btn-danger-outline btn-sm"
            :disabled="finishing"
            @click="handleFinishPractice"
          >
            提前收卷
          </button>
        </div>
      </div>

      <h1 class="practice-title">{{ overview?.title }}</h1>
      <p v-if="overview?.description" class="practice-desc">{{ overview.description }}</p>
    </div>

    <div v-if="loading && !overview" class="loading-box card muted">
      <span class="loading-spin"></span>
      <span>正在加载考务大盘数据…</span>
    </div>

    <template v-else-if="overview">
      <!-- 教师考务专属：宏观四宫格指标卡 -->
      <div v-if="overview.metrics" class="metrics-grid">
        <div class="metric-card card" :style="{ '--drop-index': 0 }">
          <div class="metric-head">
            <span class="metric-title">参练规模</span>
            <span class="metric-tag mono">
              在练中: {{ Math.max(0, overview.metrics.joinedCount - overview.metrics.submittedCount) }} 人
            </span>
          </div>
          <div class="metric-num mono">
            {{ overview.metrics.joinedCount }} <span class="metric-unit">人实到</span>
          </div>
          <div class="metric-sub">
            <span>已正式交卷: {{ overview.metrics.submittedCount }} 人</span>
            <span class="mono" style="color: #2b8a3e; font-weight: 600">
              交卷率: {{ overview.metrics.submitRate }}%
            </span>
          </div>
        </div>

        <div class="metric-card card" :style="{ '--drop-index': 1 }">
          <div class="metric-head">
            <span class="metric-title">班级基准</span>
            <span class="metric-tag mono">全班动态均值</span>
          </div>
          <div class="metric-num mono">
            {{ overview.metrics.avgScore }} <span class="metric-unit">分</span>
          </div>
          <div class="metric-sub mono">
            <span>最高分: {{ overview.metrics.maxScore }}</span>
            <span>最低分: {{ overview.metrics.minScore }}</span>
          </div>
        </div>

        <div class="metric-card card" :style="{ '--drop-index': 2 }">
          <div class="metric-head">
            <span class="metric-title">提交强度</span>
            <span class="metric-tag mono">实时评测负载</span>
          </div>
          <div class="metric-num mono">
            {{ overview.metrics.totalSubmissions }} <span class="metric-unit">次累计评测</span>
          </div>
          <div class="metric-sub">
            <span>满分通关人数:</span>
            <span class="mono" style="font-weight: 600; color: var(--ink)">
              {{ overview.metrics.perfectScoreCount }} 人
            </span>
          </div>
        </div>

        <div class="metric-card card" :style="{ '--drop-index': 3 }">
          <div class="metric-head">
            <span class="metric-title">及格达标 (60%+)</span>
            <span class="metric-tag mono">教学达标考核</span>
          </div>
          <div class="metric-num mono">
            {{ overview.metrics.passRate }} <span class="metric-unit">%</span>
          </div>
          <div class="metric-sub">
            <span>及格人数: {{ overview.metrics.passCount }} 人</span>
            <span
              class="mono"
              :style="{ color: overview.metrics.passRate >= 80 ? '#2b8a3e' : '#e67700' }"
            >
              {{ overview.metrics.passRate >= 80 ? '教学优良' : '重点讲评' }}
            </span>
          </div>
        </div>
      </div>

      <!-- 逐题正答率与考点通过透视 -->
      <div
        v-if="overview.questionAccuracies && overview.questionAccuracies.length > 0"
        class="accuracy-section"
      >
        <h2 class="section-title">逐题作答统计</h2>
        <div class="accuracy-list">
          <div
            v-for="(acc, idx) in overview.questionAccuracies"
            :key="acc.practiceQuestionId"
            class="accuracy-row"
          >
            <div class="acc-top">
              <span class="acc-num mono">#{{ String(idx + 1).padStart(2, '0') }}</span>
              <span class="acc-type mono">
                {{
                  acc.questionType === 'OBJECTIVE'
                    ? '客观题'
                    : acc.questionType === 'DB'
                      ? 'SQL分析'
                      : '编程算法'
                }}
              </span>
              <span class="acc-rate mono" :class="{ 'rate-low': acc.submitCount > 0 && acc.passRate < 50 }">
                {{ acc.submitCount > 0 ? acc.passRate + '%' : '未作答' }}
              </span>
            </div>
            <div class="acc-title" :title="acc.title">{{ acc.title }}</div>
            <div class="acc-meta mono">
              <span>通过: {{ acc.passCount }} / 作答 {{ acc.submitCount }} 人</span>
              <span>满分: {{ acc.fullScore }}分</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 参练学生全息名单与作答审计 -->
      <div class="students-section card">
        <div class="students-header">
          <h2 class="section-title" style="margin: 0">
            全班作答明细与代码快照审计 ({{ overview.students?.length ?? 0 }}人)
          </h2>
          <div class="students-search">
            <input
              v-model="studentSearchKeyword"
              type="text"
              class="input input-search mono"
              placeholder="搜索参练学生账号..."
            />
          </div>
        </div>

        <div class="table-container">
          <table class="students-table">
            <thead>
              <tr>
                <th style="width: 60px; text-align: center">排名</th>
                <th>参练学生 (账号)</th>
                <th style="width: 90px; text-align: center">实得分</th>
                <th style="width: 80px; text-align: center">AC 题数</th>
                <th style="width: 90px; text-align: center">状态</th>
                <th>首次入房时间</th>
                <th>交卷时间</th>
                <th style="width: 120px; text-align: right">考务审计</th>
              </tr>
            </thead>

            <tbody>
              <tr v-for="(stu, idx) in filteredStudents" :key="stu.userId">
                <td class="mono" style="text-align: center">{{ idx + 1 }}</td>
                <td class="mono" style="font-weight: 600">{{ stu.username }}</td>
                <td class="mono" style="font-weight: 700; text-align: center">
                  {{ stu.totalScore }}
                </td>
                <td class="mono" style="text-align: center">{{ stu.solvedCount }}</td>
                <td style="text-align: center">
                  <span v-if="stu.submitted" class="badge-tag submitted">已交卷</span>
                  <span v-else class="badge-tag active">作答中</span>
                </td>
                <td class="mono time-cell">
                  {{ stu.joinedAt ? stu.joinedAt.replace('T', ' ').substring(0, 19) : '-' }}
                </td>
                <td class="mono time-cell">
                  {{ stu.submittedAt ? stu.submittedAt.replace('T', ' ').substring(0, 19) : '-' }}
                </td>
                <td style="text-align: right">
                  <button class="btn btn-ghost btn-sm" @click="openStudentAudit(stu.userId)">
                    调阅作答明细
                  </button>
                </td>
              </tr>
              <tr v-if="filteredStudents.length === 0">
                <td colspan="8" class="empty-td muted">暂无匹配参练学生</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <!-- 学生作答详情全息审计抽屉 -->
    <div
      v-if="auditDrawerVisible"
      class="drawer-backdrop"
      :class="{ active: auditDrawerVisible }"
      @click.self="closeStudentAudit"
    >
      <div class="drawer-content">
        <div class="drawer-header">
          <div class="drawer-title-wrap">
            <span class="mono" style="font-size: 12px; color: var(--ink-secondary)">
              学生作答代码快照与评测审计
            </span>
            <h3 style="margin: 4px 0 0; font-size: 16px; font-weight: 600">
              {{ studentAudit?.username }} · 总分: {{ studentAudit?.totalScore }}分
            </h3>
          </div>
          <button class="btn btn-ghost btn-sm" @click="closeStudentAudit">关闭 ✕</button>
        </div>

        <div class="drawer-body">
          <div v-if="auditLoading" class="loading-box muted">
            <span class="loading-spin"></span>
            <span>正在调阅该学生作答代码与评测堆栈…</span>
          </div>

          <div v-else-if="studentAudit?.items" class="audit-items-list">
            <div
              v-for="(item, idx) in studentAudit.items"
              :key="item.practiceQuestionId"
              class="audit-item-card card"
            >
              <div class="audit-item-head">
                <div class="item-head-left">
                  <span class="item-idx mono">#{{ String(idx + 1).padStart(2, '0') }}</span>
                  <span class="item-title">{{ item.title }}</span>
                </div>
                <div class="item-head-right mono">
                  <span
                    :class="{
                      'text-ac': item.status === 'ACCEPTED',
                      'text-partial': item.status === 'PARTIAL',
                      'text-wa':
                        item.status !== 'ACCEPTED' &&
                        item.status !== 'PARTIAL' &&
                        item.status !== 'UNATTEMPTED',
                    }"
                  >
                    {{ item.earnedScore }} / {{ item.fullScore }} 分 ({{ item.status }})
                  </span>
                </div>
              </div>

              <!-- 客观题选项与填空对照核对 -->
              <div v-if="item.questionType === 'OBJECTIVE'" class="audit-objective-content">
                <div class="audit-question-stem">{{ item.objectiveContent }}</div>

                <div v-if="getEffectiveOptions(item).length > 0" class="audit-options">
                  <div
                    v-for="(opt, optIdx) in getEffectiveOptions(item)"
                    :key="optIdx"
                    class="audit-opt-row"
                    :class="{
                      'is-user': matchesObjectiveOption(item.userAnswer, opt, optIdx),
                      'is-correct': matchesObjectiveOption(item.correctAnswer, opt, optIdx),
                    }"
                  >
                    <span class="opt-prefix mono">{{ String.fromCharCode(65 + optIdx) }}</span>
                    <span class="opt-text">{{ stripOptionPrefix(opt) }}</span>
                    <div class="opt-tags-group">
                      <span
                        v-if="matchesObjectiveOption(item.userAnswer, opt, optIdx)"
                        class="opt-tag user"
                      >
                        学生选择
                      </span>
                      <span
                        v-if="matchesObjectiveOption(item.correctAnswer, opt, optIdx)"
                        class="opt-tag correct"
                      >
                        标准答案
                      </span>
                    </div>
                  </div>
                  <div class="audit-answer-summary mono">
                    <span>学生作答: {{ item.userAnswer || '（未作答）' }}</span>
                    <span>标准答案: {{ item.correctAnswer || '-' }}</span>
                  </div>
                </div>

                <!-- 填空题 / 无选项客观题对照卡片 -->
                <div v-else class="audit-fill-compare">
                  <div class="fill-box">
                    <span class="fill-label">学生填写答案</span>
                    <div
                      class="fill-val mono"
                      :class="item.status === 'ACCEPTED' ? 'is-ok' : 'is-err'"
                    >
                      {{ item.userAnswer || '（未作答）' }}
                    </div>
                  </div>
                  <div class="fill-box">
                    <span class="fill-label">标准参考答案</span>
                    <div class="fill-val mono is-std">
                      {{ item.correctAnswer || '-' }}
                    </div>
                  </div>
                </div>
              </div>

              <!-- 实操题最高分/最新代码快照 -->
              <div v-else class="audit-code-content">
                <div class="code-meta-row mono">
                  <span>语言: {{ item.language || 'text' }}</span>
                  <span>耗时: {{ item.timeMs != null ? `${item.timeMs}ms` : '-' }}</span>
                  <span v-if="item.memoryKb != null">内存: {{ formatMemory(item.memoryKb) }}</span>
                  <span>
                    提交时间:
                    {{ item.submittedAt ? item.submittedAt.replace('T', ' ').substring(11, 19) : '-' }}
                  </span>
                </div>
                <pre class="code-view mono"><code>{{ item.code || '// 该学生尚未提交任何源码或作答' }}</code></pre>
              </div>
            </div>
          </div>
        </div>

        <div class="drawer-footer">
          <span class="mono" style="font-size: 12px; color: var(--ink-secondary)">
            展示学生在课练有效期内锁存的最佳得分代码快照
          </span>
          <button class="btn btn-primary btn-sm" @click="closeStudentAudit">完成查阅</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-overview-page {
  padding-top: 24px;
  padding-bottom: 72px;
}

.mono {
  font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-feature-settings: 'tnum' 1;
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
.nav-trail .sep {
  color: var(--line);
}
.nav-trail .current {
  color: var(--ink);
  font-weight: 600;
}

/* 顶栏卡片 */
.header-card {
  border: 1px solid var(--line);
  padding: 18px 22px;
  background: var(--surface);
  border-radius: 0;
  margin-bottom: 20px;
}
.header-top-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 12px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.status-indicator {
  display: inline-flex;
  align-items: center;
  font-size: 12.5px;
  font-weight: 600;
}
.status-dot {
  width: 7px;
  height: 7px;
  background: #2b8a3e;
  display: inline-block;
  margin-right: 6px;
}
.status-dot.ended {
  background: var(--ink-tertiary);
}
.room-code-badge {
  font-size: 12px;
  font-weight: 700;
  background: var(--bg);
  border: 1px solid var(--line);
  padding: 2px 9px;
  cursor: pointer;
  border-radius: 0;
  color: var(--ink);
  transition: all 0.15s ease;
}
.room-code-badge:hover {
  border-color: var(--ink);
}
.meta-tag {
  font-size: 12px;
  padding: 2px 8px;
  background: var(--bg);
  border: 1px solid var(--line);
  color: var(--ink-secondary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.practice-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.4px;
}
.practice-desc {
  margin: 0;
  font-size: 13px;
  color: var(--ink-secondary);
}

/* 考务四宫格大盘 + 掉落动效 */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 24px;
}
.metric-card {
  border: 1px solid var(--line);
  padding: 16px 18px;
  background: var(--surface);
  border-radius: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  animation: cardDropIn 0.44s cubic-bezier(0.25, 1.15, 0.5, 1) backwards;
  animation-delay: calc(var(--drop-index, 0) * 45ms);
}
.metric-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.metric-title {
  font-size: 12px;
  color: var(--ink-secondary);
  font-weight: 600;
}
.metric-tag {
  font-size: 11px;
  color: var(--ink-tertiary);
}
.metric-num {
  font-size: 26px;
  font-weight: 700;
  color: var(--ink);
  line-height: 1.2;
}
.metric-unit {
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-secondary);
}
.metric-sub {
  font-size: 11.5px;
  color: var(--ink-secondary);
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
}

/* 逐题正答率 */
.accuracy-section {
  margin-bottom: 24px;
}
.section-title {
  font-size: 15px;
  font-weight: 700;
  margin: 0 0 12px;
  color: var(--ink);
}
.accuracy-list { border-top:1px solid var(--line); background:var(--bg); }
.accuracy-row { display:grid; grid-template-columns:180px minmax(0,1fr) 240px; gap:20px; align-items:center; padding:18px 16px; border-bottom:1px solid var(--line); }
.accuracy-row:hover { background:var(--surface); }
.acc-top { display:grid; grid-template-columns:36px 64px 1fr; gap:10px; align-items:center; }
.acc-num { color:var(--ink-secondary); font-size:13px; }
.acc-type { font-size:12px; color:var(--ink-secondary); }
.acc-rate { font-size:14px; font-weight:600; color:var(--ink); text-align:right; }
.acc-rate.rate-low { color:#a34d00; }
.acc-title { min-width:0; overflow-wrap:anywhere; font-size:15px; line-height:1.65; font-weight:600; }
.acc-meta { display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px 16px; font-size:13px; color:var(--ink-secondary); font-variant-numeric:tabular-nums; }
@media(max-width:1200px) {
  .accuracy-row { grid-template-columns:160px minmax(0,1fr); gap:8px 20px; }
  .acc-meta { grid-column:2; justify-content:flex-start; }
}
@media(max-width:700px) {
  .accuracy-row { grid-template-columns:1fr; padding:16px 12px; gap:10px; }
  .acc-top { grid-template-columns:36px 1fr auto; }
  .acc-meta { grid-column:1; }
}

/* 全班作答名单表格 */
.students-section {
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 0;
}
.students-header {
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
  background: var(--bg);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.input-search {
  width: 260px;
  height: 32px;
  font-size: 12.5px;
}

.table-container {
  overflow-x: auto;
}
.students-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.students-table th {
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  padding: 10px 14px;
  font-weight: 600;
  color: var(--ink-secondary);
  text-align: left;
  font-size: 12px;
}
.students-table td {
  border-bottom: 1px solid var(--line);
  padding: 11px 14px;
  vertical-align: middle;
  background: var(--bg);
}
.students-table tr:hover td {
  background: var(--surface);
}
.time-cell {
  font-size: 12px;
  color: var(--ink-secondary);
}
.empty-td {
  text-align: center;
  padding: 32px !important;
}

.btn-danger-outline {
  background: transparent;
  color: var(--danger);
  border: 1px solid rgba(220, 38, 38, 0.35);
}
.btn-danger-outline:hover {
  background: var(--danger-bg);
  border-color: var(--danger);
}

.badge-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border: 1px solid var(--line);
}
.badge-tag.submitted {
  background: #ebfbee;
  color: #2b8a3e;
  border-color: #b2f2bb;
}
.badge-tag.active {
  background: var(--surface);
  color: var(--ink-secondary);
}

/* 抽屉 */
.drawer-backdrop {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(17, 17, 17, 0.38);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}
.drawer-content {
  width: 720px;
  max-width: 90vw;
  background: var(--bg);
  height: 100%;
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
}
.drawer-header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
  background: var(--surface);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.drawer-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}
.drawer-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--line);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--surface);
}

.audit-items-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.audit-item-card {
  border: 1px solid var(--line);
  padding: 14px 16px;
  background: var(--surface);
  border-radius: 0;
}
.audit-item-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 10px;
}
.item-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.item-idx {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
}
.item-title {
  font-size: 13.5px;
  font-weight: 600;
}
.text-ac {
  color: #2b8a3e;
  font-weight: 600;
}
.text-partial {
  color: #e67700;
  font-weight: 600;
}
.text-wa {
  color: #c92a2a;
  font-weight: 600;
}

.audit-question-stem {
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--ink);
  margin-bottom: 10px;
  white-space: pre-wrap;
}

.audit-opt-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  background: var(--bg);
  margin-bottom: 6px;
  font-size: 12.5px;
}
.audit-opt-row.is-user {
  border-color: var(--ink);
  border-left: 3px solid var(--ink);
}
.audit-opt-row.is-correct {
  border-color: #b2f2bb;
  background: #ebfbee;
}
.opt-prefix {
  font-weight: 700;
  flex-shrink: 0;
}
.opt-text {
  flex: 1;
}
.opt-tags-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  flex-shrink: 0;
}
.opt-tag {
  font-size: 10.5px;
  padding: 1px 6px;
  font-weight: 600;
}
.opt-tag.user {
  background: var(--ink);
  color: #ffffff;
}
.opt-tag.correct {
  background: #2b8a3e;
  color: #ffffff;
}

.audit-answer-summary {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
  font-size: 12px;
  color: var(--ink-secondary);
}

.audit-fill-compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.fill-box {
  border: 1px solid var(--line);
  background: var(--bg);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.fill-label {
  font-size: 11px;
  color: var(--ink-tertiary);
  font-weight: 600;
}
.fill-val {
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
}
.fill-val.is-ok {
  color: #2b8a3e;
}
.fill-val.is-err {
  color: #c92a2a;
}
.fill-val.is-std {
  color: var(--ink);
}

.code-meta-row {
  display: flex;
  gap: 14px;
  font-size: 11.5px;
  color: var(--ink-secondary);
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.code-view {
  background: #18181b;
  color: #e4e4e7;
  padding: 12px;
  font-size: 12px;
  line-height: 1.5;
  overflow-x: auto;
  margin: 0;
}

@keyframes cardDropIn {
  0% {
    opacity: 0;
    transform: translateY(-38px) scale(0.985);
  }
  60% {
    opacity: 1;
    transform: translateY(3px) scale(1.002);
  }
  82% {
    opacity: 1;
    transform: translateY(-1.5px) scale(0.999);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@media (max-width: 960px) {
  .metrics-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .audit-fill-compare {
    grid-template-columns: 1fr;
  }
}
</style>
