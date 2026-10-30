<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { examApi, type ExamStudentPaper, type ExamStudentPaperQuestion } from '@/api/exam';
import { ApiError } from '@/api/request';
import { useToast } from '@/composables/useToast';

const route = useRoute();
const router = useRouter();
const toast = useToast();

const examId = computed(() => Number(route.params.id));
const userId = computed(() => Number(route.params.userId));

const paper = ref<ExamStudentPaper | null>(null);
const loading = ref(false);
const error = ref('');

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const res = await examApi.getStudentPaper(examId.value, userId.value);
    paper.value = res;
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载学生答卷明细失败';
    toast.error(error.value);
  } finally {
    loading.value = false;
  }
}

// 分大题计算
interface SectionGroup {
  sectionTitle: string;
  questions: ExamStudentPaperQuestion[];
  fullScore: number;
  earnedScore: number;
}

const sections = computed<SectionGroup[]>(() => {
  if (!paper.value) return [];
  const groups: Record<string, ExamStudentPaperQuestion[]> = {
    '选择题': [],
    '判断题': [],
    '填空题': [],
    '数据库 SQL 实操题': [],
    '编程算法实操题': [],
  };

  for (const q of paper.value.questions) {
    if (q.questionType === 'OBJECTIVE') {
      if (q.objectiveType === 'SINGLE' || q.objectiveType === 'MULTIPLE') {
        groups['选择题'].push(q);
      } else if (q.objectiveType === 'JUDGE') {
        groups['判断题'].push(q);
      } else if (q.objectiveType === 'FILL') {
        groups['填空题'].push(q);
      } else {
        groups['选择题'].push(q);
      }
    } else if (q.questionType === 'DB') {
      groups['数据库 SQL 实操题'].push(q);
    } else {
      groups['编程算法实操题'].push(q);
    }
  }

  const chineseNums = ['一', '二', '三', '四', '五', '六'];
  const res: SectionGroup[] = [];
  let sIdx = 0;
  for (const [name, qList] of Object.entries(groups)) {
    if (qList.length > 0) {
      const full = qList.reduce((sum, item) => sum + item.fullScore, 0);
      const earned = qList.reduce((sum, item) => sum + (item.studentScore ?? 0), 0);
      res.push({
        sectionTitle: `${chineseNums[sIdx] || sIdx + 1}、${name}`,
        questions: qList,
        fullScore: full,
        earnedScore: earned,
      });
      sIdx++;
    }
  }
  return res;
});

function exportWord() {
  examApi.downloadWord(examId.value, userId.value);
}

function printPdf() {
  window.print();
}

function cleanOptionText(opt: string) {
  return opt.replace(/^[A-Za-z][.、)）:：]\s*/, '');
}

function escapeHtml(text: string) {
  if (!text) return '';
  return text.replace(/&/g, '&amp;')
             .replace(/</g, '&lt;')
             .replace(/>/g, '&gt;')
             .replace(/"/g, '&quot;')
             .replace(/'/g, '&#39;');
}

function formatObjectiveTitle(q: ExamStudentPaperQuestion) {
  let title = (q.content && q.content.trim()) || q.title || '';
  title = escapeHtml(title).replace(/`([^`]+)`/g, '$1').replace(/\*\*([^*]+)\*\*/g, '$1');
  const studentAns = q.studentAnswer?.trim() || '未作答';
  const ansHtml = `<span class="student-choice">${escapeHtml(studentAns)}</span>`;

  if (q.objectiveType === 'SINGLE' || q.objectiveType === 'MULTIPLE' || q.objectiveType === 'JUDGE') {
    if (/[（(]\s*[)）]/.test(title)) {
      title = title.replace(/[（(]\s*[)）]/, `（ ${ansHtml} ）`);
    } else {
      title += `（ ${ansHtml} ）`;
    }
  } else if (q.objectiveType === 'FILL') {
    title += `&nbsp;&nbsp;<b>【考生作答：<u>&nbsp;${escapeHtml(studentAns)}&nbsp;</u>】</b>`;
  }

  if (!/[（(]\s*\d+\s*分[)）]/.test(title)) {
    title += `（${q.fullScore}分）`;
  }
  return title;
}

function getOptionsClass(options: string[]) {
  if (!options) return 'opt-row-1';
  const total = options.reduce((sum, o) => sum + cleanOptionText(o).length, 0);
  if (total <= 65 && options.length <= 4) return 'opt-row-4';
  if (total <= 110 && options.length <= 4) return 'opt-row-2';
  return 'opt-row-1';
}

onMounted(load);
</script>

<template>
  <div class="student-paper-view-page">
    <!-- 顶部非打印操作工具栏 -->
    <header class="paper-toolbar no-print">
      <div class="toolbar-inner container">
        <button class="btn btn-ghost" @click="router.push(`/admin/exams/${examId}/papers`)">
          ← 返回全场考生试卷列表
        </button>
        <div class="toolbar-right">
          <button class="btn btn-outline" @click="exportWord">
            <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>导出 Word 文档 (.doc)</span>
          </button>
          <button class="btn btn-primary" @click="printPdf">
            <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="6 9 6 2 18 2 18 9" />
              <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
              <rect x="6" y="14" width="12" height="8" />
            </svg>
            <span>打印 / 导出为 PDF</span>
          </button>
        </div>
      </div>
    </header>

    <div v-if="loading" class="state-card card container" style="margin-top: 40px;">
      <div class="loading-spinner"></div>
      <p class="muted">正在生成考生试卷与作答评定单…</p>
    </div>

    <div v-else-if="error" class="state-card card container" style="margin-top: 40px;">
      <p class="error-text">{{ error }}</p>
      <button class="btn btn-primary" @click="load">重试</button>
    </div>

    <!-- 标准 A4 仿真实卷容器 -->
    <main v-else-if="paper" class="paper-sheet container">
      <!-- 左侧密封线与考生信息栏（严格对齐学校标准试卷左页边距与装订密封规范） -->
      <aside class="paper-sidebar-left">
        <div class="sidebar-student-info">
          姓名：<u>&nbsp;{{ paper.studentInfo?.realName || paper.username || '' }}&nbsp;</u>&nbsp;&nbsp;&nbsp;&nbsp;学号：<u>&nbsp;{{ paper.studentInfo?.studentNo || '—' }}&nbsp;</u>&nbsp;&nbsp;&nbsp;&nbsp;学院：<u>&nbsp;{{ paper.studentInfo?.college || '—' }}&nbsp;</u>&nbsp;&nbsp;&nbsp;&nbsp;年级、专业：<u>&nbsp;{{ [paper.studentInfo?.major, paper.studentInfo?.className].filter(Boolean).join(' ') || '—' }}&nbsp;</u>
        </div>
        <div class="sidebar-seal-line">
          <div class="seal-line-text">密封线内禁止答题</div>
        </div>
      </aside>

      <!-- 附件头 -->
      <div class="top-tag">附件6</div>

      <!-- 试卷大标题 -->
      <h2 class="school-header">
        {{ paper.exam.schoolName || '湖北科技职业学院' }} {{ paper.exam.semester || '2025 - 2026学年度第一学期期末考试' }}
      </h2>
      <h1 class="exam-title">
        《{{ paper.exam.title }}》试题 A 卷（{{ paper.exam.mode || '开卷' }}）
      </h1>

      <!-- 大题得分表 -->
      <table class="exam-score-table">
        <thead>
          <tr>
            <th class="th-title">题&nbsp;&nbsp;号</th>
            <th v-for="(sec, idx) in sections" :key="idx">
              {{ sec.sectionTitle.slice(0, 1) }}
            </th>
            <th class="th-total">总&nbsp;&nbsp;分</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td class="td-row-name">卷面满分</td>
            <td v-for="(sec, idx) in sections" :key="idx">
              {{ sec.fullScore }}
            </td>
            <td class="td-total-full">{{ paper.fullScore }}</td>
          </tr>
          <tr>
            <td class="td-row-name">考生实得</td>
            <td v-for="(sec, idx) in sections" :key="idx" class="earned-score-cell">
              {{ sec.earnedScore }}
            </td>
            <td class="td-total-earned">{{ paper.totalScore }}</td>
          </tr>
          <tr>
            <td class="td-row-name">评卷人</td>
            <td v-for="(_, idx) in sections" :key="idx">&nbsp;</td>
            <td>&nbsp;</td>
          </tr>
        </tbody>
      </table>

      <!-- 试卷大题内容 -->
      <div class="sections-container">
        <section v-for="(sec, sIdx) in sections" :key="sIdx" class="paper-section">
          <!-- 评卷人小分表与大题标题 -->
          <div class="section-heading-box">
            <table class="mini-score-table">
              <thead>
                <tr><th>得 分</th><th>评卷人</th></tr>
              </thead>
              <tbody>
                <tr>
                  <td class="mini-score-cell">{{ sec.earnedScore }}</td>
                  <td>&nbsp;</td>
                </tr>
              </tbody>
            </table>
            <span class="section-title-text">
              {{ sec.sectionTitle }}（共 {{ sec.questions.length }} 题，满分 {{ sec.fullScore }} 分，考生实得 {{ sec.earnedScore }} 分）
            </span>
          </div>

          <article v-for="(q, qIdx) in sec.questions" :key="q.examQuestionId" class="paper-q-card">
            <template v-if="q.questionType === 'OBJECTIVE'">
              <div class="q-title">
                <span class="q-seq-no">{{ qIdx + 1 }}、</span>
                <span class="q-title-html" v-html="formatObjectiveTitle(q)"></span>
                <span v-if="q.isCorrect" class="ans-eval eval-ac">[✔ 得满分: {{ q.studentScore ?? q.fullScore }}分]</span>
                <span v-else-if="(q.studentScore ?? 0) > 0" class="ans-eval eval-partial">[◐ 得{{ q.studentScore }}/{{ q.fullScore }}分，正确答案: {{ q.standardAnswer }}]</span>
                <span v-else class="ans-eval eval-fail">[✕ 扣{{ q.fullScore }}分{{ !q.studentAnswer ? ' (未作答)' : '' }}，正确答案: {{ q.standardAnswer || '—' }}]</span>
              </div>

              <!-- 客观题选项 -->
              <div
v-if="q.options && q.options.length && (q.objectiveType === 'SINGLE' || q.objectiveType === 'MULTIPLE')"
                   class="q-options" :class="getOptionsClass(q.options)">
                <div v-for="(opt, optIdx) in q.options" :key="optIdx" class="option-item">
                  <span class="opt-letter">{{ String.fromCharCode(65 + optIdx) }}.</span>
                  <span class="opt-desc">{{ cleanOptionText(opt) }}</span>
                </div>
              </div>
            </template>

            <template v-else>
              <div class="q-title">
                <span class="q-seq-no">{{ qIdx + 1 }}、</span>
                <strong>{{ q.title }}</strong>
                <span>（{{ q.fullScore }}分）</span>
                <span v-if="q.isCorrect" class="ans-eval eval-ac">[✔ 得满分: {{ q.studentScore ?? q.fullScore }}分]</span>
                <span v-else-if="(q.studentScore ?? 0) > 0" class="ans-eval eval-partial">[◐ 得{{ q.studentScore }}/{{ q.fullScore }}分]</span>
                <span v-else class="ans-eval eval-fail">[✕ 得0分]</span>
              </div>

              <div v-if="q.content && q.content.trim() !== (q.title ? q.title.trim() : '')" class="q-content">
                {{ q.content }}
              </div>

              <!-- 实践题作答与解答 -->
              <div class="code-box">
                <div class="code-header">【考生作答内容快照】<span v-if="q.language" class="lang-tag mono">(语言: {{ q.language }})</span>：</div>
                <pre class="code-content mono">{{ q.studentAnswer || '（考生未提交作答）' }}</pre>
              </div>

              <div v-if="q.standardAnswer" class="std-box">
                <div class="code-header">【参考解答 / 判分基准】：</div>
                <pre class="code-content mono">{{ q.standardAnswer }}</pre>
              </div>
            </template>
          </article>
        </section>
      </div>

      <!-- 评分汇总表 -->
      <div class="paper-footer-criteria">
        <h4 class="footer-criteria-title">试卷评分标准与分值配比汇总</h4>
        <table class="criteria-table">
          <thead>
            <tr>
              <th style="width: 160px;">评分项</th>
              <th>评分标准与规则说明</th>
              <th style="width: 100px; text-align: center;">分值</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(sec, idx) in sections" :key="idx">
              <td><strong>{{ sec.sectionTitle }}</strong></td>
              <td>共 {{ sec.questions.length }} 题，采用标准机考隔离沙箱与自动化测试集核验。</td>
              <td style="text-align: center; font-weight: bold;">{{ sec.fullScore }} 分</td>
            </tr>
            <tr class="total-row">
              <td><strong>合&nbsp;&nbsp;计</strong></td>
              <td>全卷综合标准化实操考评</td>
              <td style="text-align: center; font-weight: bold; color: #1971c2;">{{ paper.fullScore }} 分</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<style scoped>
.student-paper-view-page {
  background: #f4f5f7;
  min-height: 100vh;
  padding-bottom: 60px;
}

.paper-toolbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background: #ffffff;
  border-bottom: 1px solid #ced4da;
  padding: 12px 0;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.05);
}
.toolbar-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.toolbar-right {
  display: flex;
  gap: 12px;
}
.icon {
  width: 16px;
  height: 16px;
  margin-right: 6px;
}

/* 仿真实卷 A4 排版容器 */
.paper-sheet {
  position: relative;
  max-width: 860px;
  margin: 30px auto;
  background: #ffffff;
  padding: 48px 48px 48px 130px;
  box-sizing: border-box;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  border: 1px solid #ced4da;
  color: #111;
  font-family: '楷体', 'KaiTi', 'SimSun', '宋体', 'Times New Roman', serif;
}

/* 左侧装订线与纵向密封栏 */
.paper-sidebar-left {
  position: absolute;
  left: 20px;
  top: 48px;
  bottom: 48px;
  width: 85px;
  display: flex;
  flex-direction: row;
  justify-content: flex-start;
  align-items: stretch;
  gap: 12px;
  pointer-events: none;
}
.sidebar-student-info {
  writing-mode: vertical-rl;
  -webkit-writing-mode: vertical-rl;
  transform: rotate(180deg);
  font-family: '宋体', 'SimSun', serif;
  font-size: 13px;
  letter-spacing: 2px;
  line-height: 1.6;
  height: 560px;
  margin-top: 80px;
  text-align: left;
}
.sidebar-student-info u {
  text-decoration: underline;
  padding: 0 4px;
  font-weight: bold;
}
.sidebar-seal-line {
  height: 100%;
  min-height: 600px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-left: 1px solid #000;
  border-right: 1px solid #000;
  padding: 0 4px;
}
.seal-line-text {
  writing-mode: vertical-rl;
  -webkit-writing-mode: vertical-rl;
  font-family: '宋体', 'SimSun', serif;
  font-size: 14px;
  letter-spacing: 22px;
  font-weight: 500;
  text-align: center;
}

.top-tag {
  text-align: left;
  font-size: 14px;
  font-family: '楷体', serif;
  margin-bottom: 6px;
}

.school-header {
  font-family: '楷体', 'KaiTi', serif;
  font-size: 21px;
  font-weight: bold;
  text-align: center;
  margin: 0 0 6px 0;
  white-space: nowrap;
}

.exam-title {
  font-family: '楷体', 'KaiTi', serif;
  font-size: 20px;
  font-weight: bold;
  text-align: center;
  margin: 0 0 20px 0;
}

/* 大题得分表 */
.exam-score-table {
  width: 100%;
  border-collapse: collapse;
  text-align: center;
  margin-bottom: 24px;
}
.exam-score-table th,
.exam-score-table td {
  border: 1px solid #000;
  padding: 6px 8px;
  font-size: 14px;
  font-family: '楷体', 'KaiTi', serif;
  height: 24px;
}
.th-title, .td-row-name {
  font-weight: bold;
  width: 14%;
}
.th-total {
  width: 14%;
}
.td-total-full {
  font-weight: bold;
}
.earned-score-cell {
  font-weight: bold;
  color: #1971c2;
}
.td-total-earned {
  font-weight: bold;
  color: #d9480f;
  font-size: 16px;
}

/* 试卷大题 */
.paper-section {
  margin-top: 20px;
}
.section-heading-box {
  margin: 16px 0 10px 0;
  page-break-after: avoid;
}
.mini-score-table {
  display: inline-table;
  vertical-align: middle;
  border-collapse: collapse;
  text-align: center;
  margin-right: 12px;
}
.mini-score-table th,
.mini-score-table td {
  border: 1px solid #000;
  padding: 2px 6px;
  font-size: 13px;
  font-family: '楷体', serif;
  width: 50px;
  height: 20px;
}
.mini-score-cell {
  font-weight: bold;
  color: #1971c2;
}
.section-title-text {
  font-family: '楷体', 'KaiTi', serif;
  font-size: 16px;
  font-weight: bold;
  vertical-align: middle;
}

.paper-q-card {
  margin-bottom: 14px;
  padding-bottom: 10px;
  page-break-inside: avoid;
}
.q-title {
  font-family: '楷体', 'KaiTi', serif;
  font-size: 15px;
  line-height: 1.5;
  margin-bottom: 6px;
}
.q-seq-no {
  font-weight: bold;
}
:deep(.student-choice) {
  font-weight: bold;
  text-decoration: underline;
  padding: 0 4px;
}
.ans-eval {
  font-size: 13px;
  margin-left: 8px;
  white-space: nowrap;
}
.eval-ac { color: #2b8a3e; font-weight: bold; }
.eval-partial { color: #e67700; font-weight: bold; }
.eval-fail { color: #c92a2a; font-weight: bold; }

.q-content {
  font-family: '楷体', serif;
  font-size: 14px;
  line-height: 1.6;
  margin-bottom: 8px;
  white-space: pre-wrap;
  color: #222;
}

.q-options {
  margin: 4px 0 10px 18px;
  font-family: '楷体', 'KaiTi', serif;
  font-size: 14.5px;
}
.q-options.opt-row-4 {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}
.q-options.opt-row-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 20px;
}
.q-options.opt-row-1 {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.option-item {
  line-height: 1.5;
}
.opt-letter {
  font-weight: bold;
  margin-right: 6px;
}

.code-box {
  border: 1px solid #777;
  background: #fbfbfb;
  padding: 8px 12px;
  margin: 8px 0;
}
.code-header {
  font-weight: bold;
  font-size: 13px;
  margin-bottom: 4px;
  color: #333;
}
.code-content {
  margin: 0;
  font-size: 13px;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'Consolas', 'Courier New', monospace;
  color: #111;
}
.std-box {
  border-left: 3px solid #1971c2;
  background: #f8f9fa;
  padding: 6px 12px;
  margin: 8px 0;
  font-size: 13px;
}

.paper-footer-criteria {
  margin-top: 32px;
  page-break-inside: avoid;
}
.footer-criteria-title {
  font-family: '楷体', serif;
  font-size: 15px;
  font-weight: bold;
  margin-bottom: 8px;
}
.criteria-table {
  width: 100%;
  border-collapse: collapse;
}
.criteria-table th,
.criteria-table td {
  border: 1px solid #000;
  padding: 6px 10px;
  font-size: 13px;
  font-family: '楷体', serif;
}
.criteria-table th {
  background: #f4f5f7;
  text-align: center;
}
.total-row {
  font-weight: bold;
}

/* 打印 PDF 专用样式（保证左侧密封线及内容格式完整对齐） */
@media print {
  @page {
    size: A4 portrait;
    margin: 15mm 15mm 15mm 30mm;
  }
  body, html {
    background: #ffffff !important;
  }
  .no-print {
    display: none !important;
  }
  .paper-sheet {
    box-shadow: none !important;
    border: none !important;
    margin: 0 !important;
    padding: 0 !important;
    max-width: 100% !important;
  }
  .paper-sidebar-left {
    position: fixed;
    left: 0;
    top: 50px;
    display: flex;
    gap: 10px;
  }
  .paper-q-card {
    page-break-inside: avoid;
  }
}
</style>
