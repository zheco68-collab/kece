<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { examApi, type ExamSubmissionRow, type RankRow } from '@/api/exam';
import { ApiError } from '@/api/request';
import { useUserStore } from '@/stores/user';
import { useToast } from '@/composables/useToast';
import { formatLanguageLabel } from '@/utils/questionMeta';
import { STATUS_ABBR, statusTone } from '@/utils/submission';
import type { SubmissionStatus } from '@/types';
import { copyToClipboard } from '@/utils/clipboard';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const { success, error: toastError } = useToast();

const examId = Number(route.params.id);

const results = ref<ExamSubmissionRow[]>([]);
const ranking = ref<RankRow[]>([]);
const loading = ref(true);
const error = ref('');

const expandedQuestionId = ref<number | null>(null);

const rankRows = computed(() => ranking.value.map((row, index) => ({ ...row, rank: index + 1 })));

const myRankInfo = computed(() => {
  if (!userStore.userInfo) return null;
  return (
    rankRows.value.find(
      (r) => r.userId === userStore.userInfo?.id || r.username === userStore.userInfo?.username,
    ) ?? null
  );
});

const normalizedResults = computed(() => {
  return results.value.map((r) => {
    let score = r.score;
    if (score != null) {
      if (r.status === 'ACCEPTED') {
        score = r.fullScore;
      } else if (score > r.fullScore) {
        score = Math.min(r.fullScore, Math.round((score * r.fullScore) / 100));
      }
    }
    return { ...r, score };
  });
});

const myTotalScore = computed(() => {
  if (myRankInfo.value) {
    if (myFullScore.value > 0 && myRankInfo.value.totalScore > myFullScore.value) {
      return normalizedResults.value.reduce((acc, r) => acc + (r.score ?? 0), 0);
    }
    return myRankInfo.value.totalScore;
  }
  return normalizedResults.value.reduce((acc, r) => acc + (r.score ?? 0), 0);
});

const myFullScore = computed(() => {
  return normalizedResults.value.reduce((acc, r) => acc + r.fullScore, 0);
});

const mySolvedCount = computed(() => {
  if (myRankInfo.value) return myRankInfo.value.solved;
  return normalizedResults.value.filter((r) => r.status === 'ACCEPTED').length;
});

const typeLabels: Record<string, string> = {
  ALGO: '编程算法',
  DB: '数据库 SQL',
  OBJECTIVE: '客观题',
};

function getStatusAbbr(status: string | null | undefined): string {
  if (!status) return '待评';
  if (status === 'UNANSWERED') return '未答';
  return STATUS_ABBR[status as SubmissionStatus] ?? status;
}

function getStatusClass(status: string | null | undefined): string {
  if (!status) return 'pending';
  if (status === 'UNANSWERED') return 'unanswered';
  return statusTone(status as SubmissionStatus);
}

function toggleExpand(id: number) {
  expandedQuestionId.value = expandedQuestionId.value === id ? null : id;
}

async function copyAnswer(text: string) {
  const ok = await copyToClipboard(text);
  if (ok) {
    success('作答内容已复制到剪贴板');
  } else {
    toastError('复制失败，请手动选取');
  }
}

onMounted(async () => {
  try {
    const [r, rk] = await Promise.all([examApi.results(examId), examApi.ranking(examId)]);
    results.value = r;
    ranking.value = rk;
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载考试成绩失败';
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="exam-result-page container">
    <!-- 状态反馈 -->
    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在结算考卷成绩并汇总排行榜…</p>
    </div>

    <div v-else-if="error" class="state-card card">
      <div class="error-title">成绩结算失败</div>
      <p class="error-msg">{{ error }}</p>
      <button type="button" class="btn btn-primary" @click="router.push('/exams')">
        返回考试大厅
      </button>
    </div>

    <template v-else>
      <!-- 顶部信息头 -->
      <header class="result-header card">
        <div class="header-top">
          <button type="button" class="btn-back" @click="router.push('/exams')">
            <svg
              class="back-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            <span>返回考试大厅</span>
          </button>
          <span class="report-badge">考试归档战报</span>
        </div>

        <div class="header-main">
          <h1 class="result-title">
            <span class="exam-tag mono tabular-nums">#{{ examId }}</span>
            <span class="title-text">考试成绩与结算战报</span>
          </h1>
          <p class="result-subtitle">作答记录已永久锁定存盘 · 自动判题与加权折算已完成</p>
        </div>
      </header>

      <!-- -------------------------------------------------------------
           用户的核心标志性设计：4 大指标直角大方块矩阵 (Hero Stat Bento)
           ------------------------------------------------------------- -->
      <section class="stat-squares-grid">
        <!-- 方块 1: 综合总分 -->
        <div class="stat-square card">
          <div class="stat-square-head">综合总得分</div>
          <div class="stat-square-body">
            <span class="stat-num score-num mono tabular-nums">{{ myTotalScore }}</span>
            <span class="stat-unit mono tabular-nums">/ {{ myFullScore }}</span>
          </div>
          <div class="stat-square-foot">
            <span class="foot-tag tabular-nums">
              得分率 {{ myFullScore > 0 ? Math.round((myTotalScore / myFullScore) * 100) : 0 }}%
            </span>
          </div>
        </div>

        <!-- 方块 2: 全场名次 -->
        <div class="stat-square card">
          <div class="stat-square-head">全场最终排名</div>
          <div class="stat-square-body">
            <span class="stat-num rank-num mono tabular-nums">
              {{ myRankInfo ? `#${myRankInfo.rank}` : '-' }}
            </span>
          </div>
          <div class="stat-square-foot">
            <span class="foot-tag tabular-nums"> 全场共 {{ rankRows.length }} 名考生完赛 </span>
          </div>
        </div>

        <!-- 方块 3: 通过试题 -->
        <div class="stat-square card">
          <div class="stat-square-head">通过题目数 (AC)</div>
          <div class="stat-square-body">
            <span class="stat-num ac-num mono tabular-nums">{{ mySolvedCount }}</span>
            <span class="stat-unit mono tabular-nums">/ {{ normalizedResults.length }}</span>
          </div>
          <div class="stat-square-foot">
            <span class="foot-tag tabular-nums">
              完成率
              {{
                normalizedResults.length > 0
                  ? Math.round((mySolvedCount / normalizedResults.length) * 100)
                  : 0
              }}%
            </span>
          </div>
        </div>

        <!-- 方块 4: 总罚时/状态 -->
        <div class="stat-square card">
          <div class="stat-square-head">ACM 累计罚时</div>
          <div class="stat-square-body">
            <span class="stat-num penalty-num mono tabular-nums">
              {{ myRankInfo?.penaltyMinutes != null ? myRankInfo.penaltyMinutes : '-' }}
            </span>
            <span class="stat-unit">分钟</span>
          </div>
          <div class="stat-square-foot">
            <span class="foot-tag">含错误提交罚时累计</span>
          </div>
        </div>
      </section>

      <!-- 试题得分明细矩阵卡片流 -->
      <section class="breakdown-section card">
        <div class="section-head">
          <div class="head-left">
            <h2 class="section-title">逐题得分明细与作答回溯</h2>
            <span class="section-desc tabular-nums"
              >共 {{ normalizedResults.length }} 题 · 点击卡片可展开查看你在考场中的原始作答</span
            >
          </div>
        </div>

        <div class="breakdown-list">
          <article
            v-for="(row, idx) in normalizedResults"
            :key="row.examQuestionId"
            class="question-result-card card"
            :class="{ open: expandedQuestionId === row.examQuestionId }"
          >
            <button
              type="button"
              class="q-card-main"
              :aria-expanded="expandedQuestionId === row.examQuestionId"
              :aria-controls="`exam-answer-drawer-${row.examQuestionId}`"
              @click="toggleExpand(row.examQuestionId)"
            >
              <!-- ---------------------------------------------------------
                   核心标志性直角方块：64×64px 试题评测状态方块 (严格保留设计感)
                   --------------------------------------------------------- -->
              <div class="q-status-square" :class="getStatusClass(row.status)">
                <span class="sq-abbr mono">
                  {{ getStatusAbbr(row.status) }}
                </span>
                <span class="sq-score mono tabular-nums">
                  {{ row.score != null ? `+${row.score}` : '-' }}
                </span>
              </div>

              <div class="q-card-info">
                <div class="q-card-title-row">
                  <span class="q-seq mono tabular-nums"
                    >#{{ idx + 1 < 10 ? `0${idx + 1}` : idx + 1 }}</span
                  >
                  <span class="q-title">{{ row.title }}</span>
                  <span class="badge" :class="`type-${row.questionType.toLowerCase()}`">
                    {{ typeLabels[row.questionType] ?? row.questionType }}
                  </span>
                </div>

                <div class="q-card-meta-row">
                  <span class="meta-score mono tabular-nums">
                    本题得分: <strong>{{ row.score ?? '-' }}</strong> / {{ row.fullScore }} 分
                  </span>
                  <span v-if="row.language" class="meta-lang mono">
                    语言: {{ formatLanguageLabel(row.language) }}
                  </span>
                </div>
              </div>

              <div class="q-card-toggle">
                <span class="toggle-text">{{
                  expandedQuestionId === row.examQuestionId ? '收起作答' : '展开作答'
                }}</span>
                <svg
                  class="toggle-arrow"
                  :class="{ rotate: expandedQuestionId === row.examQuestionId }"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  aria-hidden="true"
                >
                  <polyline points="6 9 12 15 18 9" />
                </svg>
              </div>
            </button>

            <!-- 展开的作答查看区 -->
            <div
              v-if="expandedQuestionId === row.examQuestionId"
              :id="`exam-answer-drawer-${row.examQuestionId}`"
              class="q-card-drawer"
            >
              <div class="drawer-head">
                <span class="drawer-title">考生提交内容快照</span>
                <button type="button" class="btn-copy" @click.stop="copyAnswer(row.answer)">
                  复制内容
                </button>
              </div>
              <pre class="drawer-code mono">{{ row.answer || '（未作答或内容为空）' }}</pre>
            </div>
          </article>
        </div>
      </section>

      <!-- 全场天梯排行榜 -->
      <section class="ranking-section card">
        <div class="section-head">
          <div class="head-left">
            <h2 class="section-title">全场实时天梯排行榜</h2>
            <span class="section-desc tabular-nums"
              >基于 ACM/OI 竞赛标准规则实时计算 · 共 {{ rankRows.length }} 名考生</span
            >
          </div>
        </div>

        <div class="table-container">
          <table class="rank-table">
            <thead>
              <tr>
                <th class="th-rank">名次</th>
                <th>考生账号</th>
                <th class="th-num">通过试题 (AC)</th>
                <th class="th-num">累计罚时</th>
                <th class="th-num">综合得分</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="r in rankRows"
                :key="r.userId"
                class="rank-row"
                :class="{
                  'is-me':
                    userStore.userInfo &&
                    (r.userId === userStore.userInfo.id ||
                      r.username === userStore.userInfo.username),
                  'top-1': r.rank === 1,
                  'top-2': r.rank === 2,
                  'top-3': r.rank === 3,
                }"
              >
                <td class="td-rank">
                  <!-- 冠亚季军硬朗直角方块勋章 -->
                  <div class="rank-square-badge mono tabular-nums" :class="`rank-${r.rank}`">
                    {{ r.rank }}
                  </div>
                </td>
                <td class="td-user">
                  <span class="username-text">{{ r.username }}</span>
                  <span
                    v-if="
                      userStore.userInfo &&
                      (r.userId === userStore.userInfo.id ||
                        r.username === userStore.userInfo.username)
                    "
                    class="me-tag"
                  >
                    (我)
                  </span>
                </td>
                <td class="td-num mono tabular-nums font-bold">
                  <span class="ac-text">{{ r.solved }} 题</span>
                </td>
                <td class="td-num mono tabular-nums">{{ r.penaltyMinutes }} 分钟</td>
                <td class="td-num mono tabular-nums final-score">
                  {{ r.totalScore }} / {{ r.fullScore }}
                </td>
              </tr>
              <tr v-if="rankRows.length === 0">
                <td colspan="5" class="empty-cell muted">暂无全场排行榜数据</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 底部操作导航条：统一全部为高反差黑底直角按钮 -->
      <footer class="result-footer-bar card">
        <div class="footer-left">
          <button type="button" class="btn btn-primary" @click="router.push('/exams')">
            返回考试大厅
          </button>
          <button type="button" class="btn btn-primary" @click="router.push('/submissions')">
            查看个人所有提交记录
          </button>
        </div>
        <div class="footer-right">
          <button type="button" class="btn btn-primary" @click="router.push('/questions')">
            前往题库练习 →
          </button>
        </div>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.exam-result-page {
  padding-top: 32px;
  padding-bottom: 64px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 状态加载 */
.state-card {
  padding: 64px 32px;
  text-align: center;
  border-radius: 0;
}
.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--line);
  border-top-color: var(--ink);
  animation: spin 0.8s linear infinite;
  margin: 0 auto 16px;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.error-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--kece-danger);
  margin-bottom: 8px;
}
.error-msg {
  color: var(--ink-secondary);
  margin-bottom: 24px;
}

/* 顶部信息头 */
.result-header {
  padding: 24px 28px;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
}
.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.btn-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--kece-border);
  background: var(--kece-surface-muted);
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border-radius: 0;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}
.btn-back:hover {
  background: var(--surface-white);
  color: var(--ink);
  border-color: var(--ink);
}
.back-icon {
  width: 14px;
  height: 14px;
}
.report-badge {
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  background: var(--line-strong);
  color: var(--surface-white);
  padding: 2px 8px;
}
.header-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.result-title {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
  display: flex;
  align-items: baseline;
  gap: 12px;
  color: var(--ink);
}
.exam-tag {
  font-size: 18px;
  color: var(--ink-tertiary);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.result-subtitle {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 14px;
}

/* -------------------------------------------------------------
   用户的核心标志性设计：4 大指标直角大方块矩阵 (Hero Stat Bento)
   ------------------------------------------------------------- */
.stat-squares-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.stat-square {
  padding: 20px 24px;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
}
.stat-square-head {
  font-size: 12px;
  color: var(--ink-tertiary);
  text-transform: uppercase;
  font-weight: 600;
  letter-spacing: 0.5px;
}
.stat-square-body {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.stat-num {
  font-size: 32px;
  font-weight: 800;
  color: var(--ink);
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.score-num {
  color: var(--ink);
}
.rank-num {
  color: var(--kece-accent-ink);
}
.ac-num {
  color: var(--kece-success);
}
.penalty-num {
  color: var(--kece-warning);
}
.stat-unit {
  font-size: 14px;
  color: var(--ink-tertiary);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.stat-square-foot {
  font-size: 12px;
  color: var(--ink-secondary);
  font-variant-numeric: tabular-nums;
}

/* 试题明细区域 */
.breakdown-section {
  border-radius: 0;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  overflow: hidden;
}
.section-head {
  padding: 18px 24px;
  background: var(--kece-surface-muted);
  border-bottom: 1px solid var(--kece-border);
}
.section-title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 800;
  color: var(--ink);
}
.section-desc {
  font-size: 12px;
  color: var(--ink-tertiary);
  font-variant-numeric: tabular-nums;
}

.breakdown-list {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.question-result-card {
  border-radius: 0;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  transition: border-color 0.15s ease;
}
.question-result-card:hover {
  border-color: var(--line-strong);
}
.q-card-main {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  cursor: pointer;
  text-align: left;
  background: transparent;
  border: none;
  font: inherit;
  color: inherit;
}

/* 核心直角方块：64×64px 评测状态方块 */
.q-status-square {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 0;
  color: var(--surface-white);
  border: 1px solid transparent;
}
.q-status-square.ac {
  background: var(--kece-success);
}
.q-status-square.wa {
  background: var(--kece-danger);
}
.q-status-square.tle {
  background: var(--line-strong);
}
.q-status-square.re {
  background: var(--kece-danger);
}
.q-status-square.pending {
  background: #475569;
}
.q-status-square.unanswered {
  background: #475569;
}
.sq-abbr {
  font-size: 17px;
  font-weight: 800;
  line-height: 1;
}
.sq-score {
  font-size: 11px;
  font-weight: 600;
  color: var(--surface-white);
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.q-card-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.q-card-title-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.q-seq {
  font-size: 13px;
  color: var(--ink-tertiary);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.q-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}
.q-card-meta-row {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 13px;
  color: var(--ink-secondary);
}
.meta-score {
  font-variant-numeric: tabular-nums;
}
.meta-score strong {
  color: var(--ink);
  font-weight: 700;
}

.q-card-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-tertiary);
  flex-shrink: 0;
}
.toggle-arrow {
  width: 14px;
  height: 14px;
  transition: transform 0.2s ease;
}
.toggle-arrow.rotate {
  transform: rotate(180deg);
}

.q-card-drawer {
  border-top: 1px solid var(--kece-border);
  background: var(--kece-surface-muted);
  padding: 16px 20px;
}
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.drawer-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
}
.btn-copy {
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  color: var(--ink);
  padding: 2px 8px;
  font-size: 11px;
  cursor: pointer;
  border-radius: 0;
}
.drawer-code {
  margin: 0;
  padding: 12px;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--ink);
}

/* 天梯排行榜 */
.ranking-section {
  border-radius: 0;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  overflow: hidden;
}
.table-container {
  overflow-x: auto;
}
.rank-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.rank-table th {
  text-align: left;
  padding: 12px 18px;
  color: var(--ink-tertiary);
  font-weight: 600;
  background: var(--kece-surface-muted);
  border-bottom: 1px solid var(--kece-border);
}
.rank-table td {
  padding: 14px 18px;
  border-bottom: 1px solid var(--kece-border);
  font-variant-numeric: tabular-nums;
}
.th-rank,
.td-rank {
  width: 70px;
}
.th-num,
.td-num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.rank-row.is-me {
  background: var(--kece-accent-soft);
}
.rank-row.is-me td {
  border-top: 1px solid rgba(19, 121, 105, 0.35);
  border-bottom: 1px solid rgba(19, 121, 105, 0.35);
}
.rank-square-badge {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 800;
  border-radius: 0;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  font-variant-numeric: tabular-nums;
}
.rank-square-badge.rank-1 {
  background: var(--line-strong);
  color: var(--surface-white);
  border-color: var(--line-strong);
}
.rank-square-badge.rank-2 {
  background: var(--ink-secondary);
  color: var(--surface-white);
  border-color: var(--ink-secondary);
}
.rank-square-badge.rank-3 {
  background: var(--ink-tertiary);
  color: var(--surface-white);
  border-color: var(--ink-tertiary);
}

.username-text {
  font-weight: 600;
  color: var(--ink);
}
.me-tag {
  font-size: 11px;
  color: var(--kece-accent-ink);
  font-weight: 700;
  margin-left: 6px;
}
.ac-text {
  color: var(--kece-success);
  font-weight: 700;
}
.final-score {
  font-weight: 700;
  color: var(--ink);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.empty-cell {
  text-align: center;
  padding: 32px;
}

/* 底部操作条 */
.result-footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-radius: 0;
  background: var(--surface-white);
  border: 1px solid var(--kece-border);
  gap: 16px;
  flex-wrap: wrap;
}
.footer-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

@media (max-width: 900px) {
  .stat-squares-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 600px) {
  .stat-squares-grid {
    grid-template-columns: 1fr;
  }
  .result-footer-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .footer-left,
  .footer-right {
    width: 100%;
  }
  .btn-primary {
    width: 100%;
    justify-content: center;
  }
}

@media (prefers-reduced-motion: reduce) {
  .loading-spinner {
    animation: none;
  }
  .btn-back,
  .question-result-card,
  .toggle-arrow {
    transition: none;
  }
}
</style>
