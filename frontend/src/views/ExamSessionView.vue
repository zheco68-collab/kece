<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { examApi, type SessionEnter, type ExamStudentInfo } from '@/api/exam'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import { useCountdown } from '@/shared/composables/useCountdown'

const route = useRoute()
const router = useRouter()
const { success, info, error: toastError } = useToast()
const { confirm } = useConfirm()

const examId = computed(() => Number(route.params.id))
const enter = ref<SessionEnter | null>(null)
const loading = ref(true)
const submittingExam = ref(false)

const showStudentModal = ref(false)
const savingStudentInfo = ref(false)
const studentForm = ref<ExamStudentInfo>({
  realName: '',
  studentNo: '',
  college: '',
  major: '',
  className: '',
})

const { remainingSec, isUrgent, start: startCountdown } = useCountdown({
  onExpire: () => {
    if (enter.value && enter.value.status !== 'ENDED') {
      enter.value.status = 'ENDED'
      info('考试时间已截止')
    }
  },
})

const objectiveQuestions = computed(() => {
  if (!enter.value) return []
  return enter.value.questions.filter((q) => q.questionType === 'OBJECTIVE')
})

const practicalQuestions = computed(() => {
  if (!enter.value) return []
  return enter.value.questions.filter((q) => q.questionType !== 'OBJECTIVE')
})

const objectiveFullScore = computed(() => {
  return objectiveQuestions.value.reduce((sum, q) => sum + (q.score || 0), 0)
})

const totalFullScore = computed(() => {
  return enter.value?.questions.reduce((sum, q) => sum + (q.score || 0), 0) ?? 0
})

const objectiveAnsweredCount = computed(() => {
  if (!enter.value?.myAnswers) return 0
  return objectiveQuestions.value.filter((q) => {
    const a = enter.value!.myAnswers?.[q.id]
    return a && a.answerContent && a.answerContent.trim().length > 0
  }).length
})

function getSavedAnswer(qId: number) {
  const ans = enter.value?.myAnswers?.[qId]
  if (!ans) return null
  const q = enter.value?.questions.find((item) => item.id === qId)
  if (!q) return ans

  let score = ans.score
  if (score != null) {
    if (ans.status === 'ACCEPTED') {
      score = q.score
    } else if (score > q.score) {
      // 防御：若沙箱或历史脏数据传递了原始百分制(0~100)，折算为快照分
      score = Math.min(q.score, Math.round((score * q.score) / 100))
    }
  }

  return {
    ...ans,
    score,
  }
}

const acSolvedCount = computed(() => {
  if (!enter.value?.questions) return 0
  return practicalQuestions.value.filter((q) => {
    const a = getSavedAnswer(q.id)
    return a && a.status === 'ACCEPTED'
  }).length
})

const myTotalScore = computed(() => {
  if (!enter.value?.questions) return 0
  let total = 0
  for (const q of enter.value.questions) {
    const a = getSavedAnswer(q.id)
    if (a && a.score != null) {
      total += a.score
    }
  }
  return total
})

const formattedRemaining = computed(() => {
  if (enter.value?.status === 'ENDED' || (remainingSec.value !== null && remainingSec.value <= 0)) {
    return '00:00 (已截止)'
  }
  if (remainingSec.value === null) return '--:--'
  const h = Math.floor(remainingSec.value / 3600)
  const m = Math.floor((remainingSec.value % 3600) / 60)
  const s = remainingSec.value % 60
  if (h > 0) {
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  }
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})

async function fetchEnter() {
  loading.value = true
  try {
    const res = await examApi.enter(examId.value)
    enter.value = res
    startCountdown(res.status === 'ENDED' ? null : res.remainingSeconds)
    if (res.studentInfo) {
      studentForm.value = {
        realName: res.studentInfo.realName || '',
        studentNo: res.studentInfo.studentNo || '',
        college: res.studentInfo.college || '',
        major: res.studentInfo.major || '',
        className: res.studentInfo.className || '',
      }
    }
    if (res.requireStudentInfo && !res.hasFilledStudentInfo) {
      showStudentModal.value = true
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '加载考试失败'
    if (msg.includes('已交卷')) {
      info('您已交卷，正在前往成绩战报…')
      router.replace(`/exams/${examId.value}/result`)
      return
    }
    toastError(msg)
  } finally {
    loading.value = false
  }
}

async function saveStudentInfoAction() {
  if (!studentForm.value.realName || !studentForm.value.realName.trim()) {
    toastError('请填写您的真实姓名')
    return
  }
  if (!studentForm.value.studentNo || !studentForm.value.studentNo.trim()) {
    toastError('请填写您的学号')
    return
  }
  savingStudentInfo.value = true
  try {
    const res = await examApi.saveStudentInfo(examId.value, studentForm.value)
    if (enter.value) {
      enter.value.studentInfo = res
      enter.value.hasFilledStudentInfo = true
    }
    showStudentModal.value = false
    success('考生信息核验成功，祝您考试取得优异成绩！')
  } catch (e) {
    toastError(e instanceof Error ? e.message : '保存考生信息失败')
  } finally {
    savingStudentInfo.value = false
  }
}

async function handleSubmitExam() {
  const ok = await confirm({
    title: '确认提交整场考试',
    message: '交卷后本次考试的所有作答将被锁定并归档评测，确定现在交卷吗？',
    confirmText: '确认交卷',
    cancelText: '继续作答',
  })
  if (!ok) return

  submittingExam.value = true
  try {
    await examApi.submit(examId.value)
    success('交卷成功！正在为您生成成绩战报…')
    router.push(`/exams/${examId.value}/result`)
  } catch (e) {
    toastError(e instanceof Error ? e.message : '交卷失败')
  } finally {
    submittingExam.value = false
  }
}

onMounted(async () => {
  await fetchEnter()
})
</script>

<template>
  <div class="practice-detail-page container">
    <!-- 加载中 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在加载试卷题目与作答档案…</p>
    </div>

    <template v-else-if="enter">
      <!-- 顶部信息栏 -->
      <header class="detail-header card">
        <div class="header-top">
          <button class="btn-back" @click="router.push('/exams')">
            ← 返回考试大厅
          </button>
          <div class="header-actions">
            <span class="status-pill" :class="enter.status.toLowerCase()">
              {{ enter.status === 'ACTIVE' || enter.status === 'ONGOING' ? '● 进行中' : '已结束' }}
            </span>
            <span class="code-pill">赛制: <strong>{{ enter.mode }}</strong></span>
          </div>
        </div>

        <div class="title-row">
          <h1 class="practice-title">{{ enter.examTitle }}</h1>
          <div
            v-if="enter.durationMinutes > 0"
            class="countdown-badge"
            :class="{
              urgent: isUrgent && enter.status !== 'ENDED',
              ended: enter.status === 'ENDED'
            }"
          >
            <span class="cd-label">
              {{ enter.status === 'ENDED' ? '作答状态' : '剩余作答时间' }}
            </span>
            <span class="cd-time">
              {{ enter.status === 'ENDED' ? '已截止收卷' : formattedRemaining }}
            </span>
          </div>
        </div>

        <!-- 考生信息归档密封条 -->
        <div v-if="enter.studentInfo" class="student-seal-bar">
          <div class="seal-pill">
            <span class="seal-icon">🪪</span>
            <span class="seal-title">考生归档信息（已加盖密封线）</span>
          </div>
          <div class="seal-info-items">
            <span class="s-field"><strong>姓名:</strong> {{ enter.studentInfo.realName }}</span>
            <span class="s-field"><strong>学号:</strong> {{ enter.studentInfo.studentNo }}</span>
            <span v-if="enter.studentInfo.college" class="s-field"><strong>院系:</strong> {{ enter.studentInfo.college }}</span>
            <span v-if="enter.studentInfo.major" class="s-field"><strong>专业:</strong> {{ enter.studentInfo.major }}</span>
            <span v-if="enter.studentInfo.className" class="s-field"><strong>班级:</strong> {{ enter.studentInfo.className }}</span>
          </div>
          <button
            v-if="enter.status !== 'ENDED'"
            class="btn-edit-student"
            @click="showStudentModal = true"
          >
            修改信息
          </button>
        </div>
        <div v-else-if="enter.requireStudentInfo" class="student-seal-bar unverified">
          <div class="seal-pill warn">
            <span class="seal-icon">⚠️</span>
            <span class="seal-title">尚未登记考生信息</span>
          </div>
          <span class="warn-msg">本场考试要求实名归档，请在作答前填报核验</span>
          <button class="btn-edit-student primary" @click="showStudentModal = true">
            立即登记考生信息 →
          </button>
        </div>

        <!-- Bento 指标方块 -->
        <div class="bento-strip">
          <div class="bento-item">
            <span class="b-label">考核模式</span>
            <span class="b-value">{{ enter.mode }} 赛制</span>
          </div>
          <div class="bento-item">
            <span class="b-label">试卷总分值</span>
            <span class="b-value">{{ totalFullScore }} 分</span>
          </div>
          <div class="bento-item">
            <span class="b-label">我的当前得分</span>
            <span class="b-value highlight">{{ myTotalScore }} 分</span>
          </div>
          <div class="bento-item">
            <span class="b-label">实战 AC 通过</span>
            <span class="b-value success-text">{{ acSolvedCount }} 题</span>
          </div>
          <div class="bento-item action-item">
            <button
              class="btn btn-submit-practice"
              :disabled="submittingExam || enter.status === 'ENDED'"
              @click="handleSubmitExam"
            >
              {{ submittingExam ? '交卷中…' : '提交考试卷 ✓' }}
            </button>
          </div>
        </div>
      </header>

      <!-- 板块 1: 客观题集中答卷入口 -->
      <section v-if="objectiveQuestions.length > 0" class="objective-section card">
        <div class="obj-card-left">
          <div class="obj-icon-square">OBJ</div>
          <div class="obj-text-wrap">
            <div class="obj-badge-row">
              <span class="obj-tag">客观题综合卷</span>
              <span class="obj-score-tag">总分值: {{ objectiveFullScore }} 分</span>
            </div>
            <h2 class="obj-title">客观题集中答题区（共 {{ objectiveQuestions.length }} 道）</h2>
            <p class="obj-desc">
              本场所有单选、多选与判断/填空题已汇聚在同一答卷中，支持即刻集中作答与自动保存。
            </p>
          </div>
        </div>
        <div class="obj-card-right">
          <div class="obj-progress-info">
            <span class="progress-txt">完成进度: {{ objectiveAnsweredCount }} / {{ objectiveQuestions.length }} 题</span>
          </div>
          <button
            class="btn btn-enter-obj"
            :class="{ 'btn-view-mode': enter.status === 'ENDED' }"
            @click="router.push(`/exams/${examId}/objective`)"
          >
            {{
              enter.status === 'ENDED'
                ? '查看客观题答卷 →'
                : objectiveAnsweredCount > 0
                  ? '查看或修改客观题 →'
                  : '进入集中答卷 →'
            }}
          </button>
        </div>
      </section>

      <!-- 板块 2: 编程与 SQL 实战题目列表 -->
      <section v-if="practicalQuestions.length > 0" class="practical-section">
        <div class="section-header">
          <div class="sec-left">
            <h2 class="section-title">上机实操试题清单</h2>
            <span class="sec-count">共 {{ practicalQuestions.length }} 题</span>
          </div>
          <span class="sec-hint">点击题目即可进入标准做题与评测工作台</span>
        </div>

        <div class="practical-list">
          <div
            v-for="(q, idx) in practicalQuestions"
            :key="q.id"
            class="practical-item card"
            :class="{
              solved: getSavedAnswer(q.id)?.status === 'ACCEPTED',
              partial: (getSavedAnswer(q.id)?.score ?? 0) > 0 && getSavedAnswer(q.id)?.status !== 'ACCEPTED'
            }"
          >
            <div class="status-col">
              <div v-if="getSavedAnswer(q.id)?.status === 'ACCEPTED'" class="status-circle solved" title="已满分通过">✓</div>
              <div v-else-if="(getSavedAnswer(q.id)?.score ?? 0) > 0" class="status-circle partial" title="获得部分分">◐</div>
              <div v-else-if="getSavedAnswer(q.id)?.status === 'WRONG_ANSWER'" class="status-circle failed" title="未完全通过">✕</div>
              <div v-else class="status-circle unsolved" title="未解答">○</div>
            </div>

            <!-- 题号与标题 -->
            <div class="info-col">
              <div class="title-top">
                <span class="q-number">#{{ idx + 1 }}</span>
                <span class="q-type-badge" :class="q.questionType.toLowerCase()">
                  {{ q.questionType === 'ALGO' ? '算法编程' : '数据库 SQL' }}
                </span>
                <span class="q-score-badge">{{ q.score }} 分</span>
              </div>
              <h3
                class="q-title"
                @click="
                  q.questionType === 'ALGO'
                    ? router.push(`/exams/${examId}/question/${q.id}`)
                    : router.push(`/exams/${examId}/db-question/${q.id}`)
                "
              >
                {{ q.title }}
              </h3>
              <div class="q-meta">
                <span v-if="q.questionType === 'ALGO'" class="meta-spec">
                  规格: {{ q.timeLimitMs || 1000 }}ms / {{ q.memoryLimitMb || 256 }}MB
                </span>
                <span v-else class="meta-spec">
                  引擎: MySQL 8.0 标准环境
                </span>
                <span v-if="(getSavedAnswer(q.id)?.score ?? 0) > 0" class="meta-user-score" :class="{ 'is-partial': getSavedAnswer(q.id)?.status !== 'ACCEPTED' }">
                  本题得分: {{ getSavedAnswer(q.id)?.score }} / {{ q.score }} 分 ({{ getSavedAnswer(q.id)?.status === 'ACCEPTED' ? '已满分' : '已得部分分' }})
                </span>
              </div>
            </div>

            <!-- CTA 按钮 -->
            <div class="action-col">
              <button
                class="btn btn-start"
                :class="{
                  'btn-view-mode': enter.status === 'ENDED',
                  'btn-partial': (getSavedAnswer(q.id)?.score ?? 0) > 0 && getSavedAnswer(q.id)?.status !== 'ACCEPTED'
                }"
                @click="
                  q.questionType === 'ALGO'
                    ? router.push(`/exams/${examId}/question/${q.id}`)
                    : router.push(`/exams/${examId}/db-question/${q.id}`)
                "
              >
                {{
                  enter.status === 'ENDED'
                    ? (getSavedAnswer(q.id)?.status === 'ACCEPTED' ? '查看测评结果 →' : ((getSavedAnswer(q.id)?.score ?? 0) > 0 ? '查看部分分结果 →' : '查看题目 →'))
                    : (getSavedAnswer(q.id)?.status === 'ACCEPTED' ? '重新作答 →' : ((getSavedAnswer(q.id)?.score ?? 0) > 0 ? '继续冲满分 →' : '开始做题 →'))
                }}
              </button>
            </div>
          </div>
        </div>
      </section>
    </template>

    <!-- 考生信息登记核验弹窗（根据教师设置要求填写） -->
    <div v-if="showStudentModal" class="modal-backdrop">
      <div class="modal-box card">
        <div class="modal-header">
          <div class="modal-title-wrap">
            <span class="modal-tag">考生身份核验</span>
            <h3 class="modal-title">期末标准化考试考生档案登记</h3>
          </div>
        </div>
        <p class="modal-desc">
          本场考试已开启标准化实名阅卷规范。作答前请填写真实身份信息，该信息将直接加盖于期末考试 A 卷密封线内与成绩归档档案中。
        </p>

        <form class="modal-form" @submit.prevent="saveStudentInfoAction">
          <div class="form-row">
            <div class="form-group required">
              <label>真实姓名 <span class="req">*</span></label>
              <input
                v-model="studentForm.realName"
                type="text"
                class="form-input"
                placeholder="例如：张三"
                maxlength="50"
                required
              />
            </div>
            <div class="form-group required">
              <label>学号 / 考号 <span class="req">*</span></label>
              <input
                v-model="studentForm.studentNo"
                type="text"
                class="form-input"
                placeholder="例如：2022030101"
                maxlength="50"
                required
              />
            </div>
          </div>

          <div class="form-row">
            <div class="form-group">
              <label>所在学院 / 系部</label>
              <input
                v-model="studentForm.college"
                type="text"
                class="form-input"
                placeholder="例如：电子信息工程学院"
                maxlength="100"
              />
            </div>
            <div class="form-group">
              <label>所学专业</label>
              <input
                v-model="studentForm.major"
                type="text"
                class="form-input"
                placeholder="例如：软件工程"
                maxlength="100"
              />
            </div>
          </div>

          <div class="form-group">
            <label>行政班级</label>
            <input
              v-model="studentForm.className"
              type="text"
              class="form-input"
              placeholder="例如：2022软工1班"
              maxlength="100"
            />
          </div>

          <div class="modal-actions">
            <button
              v-if="enter?.hasFilledStudentInfo"
              type="button"
              class="btn btn-secondary"
              @click="showStudentModal = false"
            >
              取消修改
            </button>
            <button
              v-else
              type="button"
              class="btn btn-secondary"
              @click="router.push('/exams')"
            >
              暂不作答，退出考试
            </button>
            <button
              type="submit"
              class="btn btn-primary"
              :disabled="savingStudentInfo"
            >
              {{ savingStudentInfo ? '保存核验中…' : '确认考生档案并开始作答 →' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.practice-detail-page {
  padding-top: 24px;
  padding-bottom: 60px;
  max-width: 1100px;
  margin: 0 auto;
}

.detail-header {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 0;
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
}

.header-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.btn-back {
  background: transparent;
  border: none;
  font-size: 13px;
  font-weight: 700;
  color: #495057;
  cursor: pointer;
  padding: 0;
}

.btn-back:hover {
  color: #1e1e1e;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.status-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 3px 8px;
  color: #fff;
}

.status-pill.active,
.status-pill.ongoing {
  background: #2b8a3e;
}

.status-pill.ended {
  background: #495057;
}

.code-pill {
  background: #f1f3f5;
  border: 1px solid var(--line);
  padding: 3px 8px;
  font-size: 12px;
  font-family: monospace;
}

.title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.practice-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ink);
  margin: 0;
}

.countdown-badge {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--ink);
  padding: 6px 14px;
  border-radius: 0;
}

.countdown-badge.urgent {
  background: var(--danger-bg);
  border-color: var(--danger);
  color: var(--danger);
}

.countdown-badge.ended {
  background: var(--bg);
  border-color: var(--line);
  color: var(--ink-secondary);
}

.cd-label {
  font-size: 10px;
  color: var(--ink-tertiary);
  letter-spacing: 0.5px;
}

.cd-time {
  font-size: 18px;
  font-weight: 800;
  font-family: monospace;
}

.countdown-badge.ended .cd-time {
  font-size: 14px;
  color: var(--ink-secondary);
}

.btn-view-mode {
  background: var(--surface) !important;
  color: var(--ink) !important;
  border: 1px solid var(--line) !important;
}

.btn-view-mode:hover {
  background: var(--bg) !important;
  border-color: var(--ink) !important;
}

.bento-strip {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  background: #f8f9fa;
  border: 1px solid #e9ecef;
  padding: 12px 16px;
}

.bento-item {
  display: flex;
  flex-direction: column;
}

.b-label {
  font-size: 11px;
  color: #868e96;
  margin-bottom: 4px;
}

.b-value {
  font-size: 15px;
  font-weight: 800;
  color: #212529;
}

.b-value.highlight {
  color: #1971c2;
}

.b-value.success-text {
  color: #2b8a3e;
}

.action-item {
  justify-content: center;
}

.btn-submit-practice {
  background: #1e1e1e;
  color: #fff;
  border: 1px solid #1e1e1e;
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.btn-submit-practice:hover:not(:disabled) {
  background: #343a40;
}

.btn-submit-practice:disabled {
  background: #adb5bd;
  border-color: #adb5bd;
  cursor: not-allowed;
}

.objective-section {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--surface);
  border: 1px solid #1e1e1e;
  border-left: 5px solid #7048e8;
  padding: 20px 24px;
  margin-bottom: 24px;
}

.obj-card-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.obj-icon-square {
  width: 50px;
  height: 50px;
  background: #7048e8;
  color: #fff;
  font-weight: 900;
  font-size: 16px;
  font-family: monospace;
  display: flex;
  align-items: center;
  justify-content: center;
}

.obj-badge-row {
  display: flex;
  gap: 8px;
  margin-bottom: 4px;
}

.obj-tag {
  background: #7048e8;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
}

.obj-score-tag {
  background: #f1f3f5;
  border: 1px solid #ced4da;
  font-size: 11px;
  font-weight: 700;
  padding: 1px 6px;
}

.obj-title {
  font-size: 17px;
  font-weight: 800;
  color: #1e1e1e;
  margin: 0 0 4px 0;
}

.obj-desc {
  font-size: 13px;
  color: #868e96;
  margin: 0;
}

.obj-card-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.obj-progress-info {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  font-size: 12px;
  color: #495057;
}

.btn-enter-obj {
  background: #7048e8;
  color: #fff;
  border: 1px solid #5f3dc4;
  padding: 8px 20px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border-radius: 0;
}

.practical-section {
  margin-top: 28px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 14px;
}

.sec-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.section-title {
  font-size: 20px;
  font-weight: 800;
  color: #1e1e1e;
  margin: 0;
}

.sec-count {
  font-size: 13px;
  font-weight: 700;
  color: #868e96;
}

.sec-hint {
  font-size: 12px;
  color: #868e96;
}

.practical-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.practical-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  background: var(--surface);
  border: 1px solid #ced4da;
  border-radius: 0;
  transition: all 0.15s ease;
}

.practical-item:hover {
  background: #ebeef2;
  border-color: #1e1e1e;
  box-shadow: 2px 2px 0 #1e1e1e;
}

.practical-item.solved {
  border-left: 4px solid #2b8a3e;
}

.practical-item.partial {
  border-left: 4px solid #f59f00;
}

.status-col {
  width: 28px;
}

.status-circle {
  width: 24px;
  height: 24px;
  border-radius: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 900;
}

.status-circle.solved {
  background: #2b8a3e;
  color: #fff;
}

.status-circle.partial {
  background: #f59f00;
  color: #fff;
}

.status-circle.failed {
  background: #c92a2a;
  color: #fff;
}

.status-circle.unsolved {
  border: 1px solid #ced4da;
  color: #adb5bd;
}

.info-col {
  flex: 1;
  min-width: 0;
}

.title-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.q-number {
  font-size: 12px;
  font-weight: 800;
  font-family: monospace;
  color: #495057;
}

.q-type-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  color: #fff;
}

.q-type-badge.algo {
  background: #2b8a3e;
}

.q-type-badge.db,
.q-type-badge.database {
  background: #1e1e1e;
}

.q-score-badge {
  background: #f8f9fa;
  border: 1px solid #ced4da;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
}

.q-title {
  font-size: 15px;
  font-weight: 700;
  color: #1e1e1e;
  margin: 0 0 6px 0;
  cursor: pointer;
}

.q-title:hover {
  color: #1971c2;
}

.q-meta {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #868e96;
}

.meta-spec {
  font-family: monospace;
}

.meta-user-score {
  color: #2b8a3e;
  font-weight: 700;
}

.meta-user-score.is-partial {
  color: #e67700;
}

.btn-start {
  background: #1e1e1e;
  color: #fff;
  border: 1px solid #1e1e1e;
  padding: 8px 18px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border-radius: 0;
}

.btn-start:hover {
  background: #343a40;
}

.btn-start.btn-partial {
  background: #f59f00;
  border-color: #f59f00;
  color: #fff;
}

.btn-start.btn-partial:hover {
  background: #e67700;
}

.state-card {
  padding: 60px;
  text-align: center;
}

/* 考生信息密封条 */
.student-seal-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 10px 14px;
  background: #f8f9fa;
  border: 1px dashed #ced4da;
  margin-bottom: 20px;
  font-size: 13px;
}

.student-seal-bar.unverified {
  background: #fff9db;
  border-color: #f59f00;
}

.seal-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #1e1e1e;
  color: #fff;
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 700;
}

.seal-pill.warn {
  background: #e67700;
}

.seal-info-items {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: #495057;
}

.s-field strong {
  color: #1e1e1e;
  margin-right: 4px;
}

.warn-msg {
  color: #d9480f;
  font-weight: 600;
}

.btn-edit-student {
  margin-left: auto;
  background: #fff;
  border: 1px solid #1e1e1e;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.btn-edit-student:hover {
  background: #1e1e1e;
  color: #fff;
}

.btn-edit-student.primary {
  background: #1e1e1e;
  color: #fff;
}

.btn-edit-student.primary:hover {
  background: #343a40;
}

/* 弹窗样式 (KECE 极简硬朗风格) */
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.65);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 20px;
}

.modal-box {
  width: 100%;
  max-width: 580px;
  background: #fff;
  border: 2px solid #1e1e1e;
  padding: 28px;
  box-shadow: 8px 8px 0px rgba(0, 0, 0, 0.9);
}

.modal-header {
  margin-bottom: 12px;
}

.modal-tag {
  display: inline-block;
  background: #1e1e1e;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  margin-bottom: 8px;
}

.modal-title {
  font-size: 20px;
  font-weight: 800;
  margin: 0;
  color: #1e1e1e;
}

.modal-desc {
  font-size: 13px;
  color: #495057;
  line-height: 1.6;
  margin: 0 0 20px 0;
  padding-bottom: 16px;
  border-bottom: 1px solid #e9ecef;
}

.modal-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: 12px;
  font-weight: 700;
  color: #212529;
}

.form-group label .req {
  color: #fa5252;
}

.form-input {
  border: 1px solid #1e1e1e;
  padding: 8px 12px;
  font-size: 14px;
  border-radius: 0;
  outline: none;
  background: #fff;
}

.form-input:focus {
  border-color: #228be6;
  box-shadow: 0 0 0 2px rgba(34, 139, 230, 0.2);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 12px;
  padding-top: 16px;
  border-top: 1px solid #e9ecef;
}

.modal-actions .btn {
  padding: 9px 18px;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border-radius: 0;
}

.modal-actions .btn-secondary {
  background: #f1f3f5;
  border: 1px solid #ced4da;
  color: #495057;
}

.modal-actions .btn-secondary:hover {
  background: #e9ecef;
  color: #212529;
}

.modal-actions .btn-primary {
  background: #1e1e1e;
  border: 1px solid #1e1e1e;
  color: #fff;
}

.modal-actions .btn-primary:hover:not(:disabled) {
  background: #343a40;
}

.modal-actions .btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
