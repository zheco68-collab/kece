<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { examApi, type Exam, type RankRow } from '@/api/exam';
import { useUserStore } from '@/stores/user';
import { useToast } from '@/composables/useToast';

type ExamDisplayStatus = 'ONGOING' | 'UPCOMING' | 'ENDED';
type StatusTabKey = 'ALL' | ExamDisplayStatus;

const router = useRouter();
const userStore = useUserStore();
const { info } = useToast();

const keyword = ref('');
const statusFilter = ref<StatusTabKey>('ALL');
const page = ref(1);
const size = 10;

const rawList = ref<Exam[]>([]);
const total = ref(0);
const loading = ref(false);
const error = ref('');

// ---- 榜单弹窗状态 ----
const rankingModalVisible = ref(false);
const rankingExam = ref<Exam | null>(null);
const rankingList = ref<RankRow[]>([]);
const rankingLoading = ref(false);
const rankingError = ref('');
const rankingCloseBtn = ref<HTMLButtonElement | null>(null);
let rankingTimer: ReturnType<typeof setInterval> | null = null;

// ---- 未开始场次安排弹窗 ----
const scheduleExam = ref<Exam | null>(null);
const scheduleCloseBtn = ref<HTMLButtonElement | null>(null);

let previousFocus: HTMLElement | null = null;
let keywordTimer: ReturnType<typeof setTimeout> | null = null;

/** 解析时间串为时间戳：缺省无偏移的 ISO 默认视为北京时间 (+08:00)。 */
function parseBeijingTimestamp(iso: string | null): number {
  if (!iso) return 0;
  const trimmed = iso.trim();
  if (!trimmed) return 0;
  const hasZone = /([zZ]|[+-]\d{2}:\d{2})$/.test(trimmed);
  const parsed = Date.parse(hasZone ? trimmed : `${trimmed}+08:00`);
  return Number.isNaN(parsed) ? 0 : parsed;
}

const beijingFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const trimmed = iso.trim();
  const hasZone = /([zZ]|[+-]\d{2}:\d{2})$/.test(trimmed);
  if (!hasZone && /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/.test(trimmed)) {
    return trimmed.slice(0, 16).replace('T', ' ');
  }
  const ts = Date.parse(trimmed);
  if (Number.isNaN(ts)) return trimmed.slice(0, 16).replace('T', ' ');
  return beijingFormatter.format(new Date(ts)).replace(/\//g, '-');
}

/** 结合后端 sessionStatus 与服务器/当前时刻推断场次展示状态。 */
function getRealStatus(exam: Exam): ExamDisplayStatus {
  if (exam.sessionStatus === 'CANCELLED') return 'ENDED';
  const now = Date.now();
  const start = parseBeijingTimestamp(exam.startTime);
  const end = parseBeijingTimestamp(exam.endTime);

  if (end && now >= end) return 'ENDED';
  if (start && now < start) return 'UPCOMING';
  if (exam.sessionStatus === 'ENDED') return 'ENDED';
  return 'ONGOING';
}

const isLiveRanking = computed(
  () => rankingExam.value !== null && getRealStatus(rankingExam.value) === 'ONGOING',
);

const statusCounts = computed(() => {
  const counts = { ALL: rawList.value.length, ONGOING: 0, UPCOMING: 0, ENDED: 0 };
  for (const exam of rawList.value) {
    counts[getRealStatus(exam)] += 1;
  }
  return counts;
});

const statusTabs: Array<{ key: StatusTabKey; label: string }> = [
  { key: 'ALL', label: '全部场次' },
  { key: 'ONGOING', label: '进行中' },
  { key: 'UPCOMING', label: '未开始' },
  { key: 'ENDED', label: '已结束' },
];

const filteredList = computed(() => {
  if (statusFilter.value === 'ALL') return rawList.value;
  return rawList.value.filter((e) => getRealStatus(e) === statusFilter.value);
});

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)));

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const res = await examApi.listExams({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size,
    });
    rawList.value = res.list;
    total.value = res.total;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载考试列表失败';
  } finally {
    loading.value = false;
  }
}

async function fetchRanking(examId: number) {
  rankingLoading.value = true;
  rankingError.value = '';
  try {
    rankingList.value = await examApi.ranking(examId);
  } catch (e) {
    rankingError.value = e instanceof Error ? e.message : '加载榜单失败，请稍后重试';
  } finally {
    rankingLoading.value = false;
  }
}

function stopRankingTimer() {
  if (rankingTimer !== null) {
    clearInterval(rankingTimer);
    rankingTimer = null;
  }
}

function openRankingModal(exam: Exam) {
  previousFocus = document.activeElement as HTMLElement | null;
  rankingExam.value = exam;
  rankingList.value = [];
  rankingError.value = '';
  rankingModalVisible.value = true;
  fetchRanking(exam.id);
  stopRankingTimer();
  if (getRealStatus(exam) === 'ONGOING') {
    rankingTimer = setInterval(() => {
      if (rankingExam.value && rankingModalVisible.value && getRealStatus(rankingExam.value) === 'ONGOING') {
        fetchRanking(rankingExam.value.id);
      } else {
        stopRankingTimer();
      }
    }, 10000);
  }
  nextTick(() => {
    rankingCloseBtn.value?.focus();
  });
}

function closeRankingModal() {
  if (!rankingModalVisible.value) return;
  rankingModalVisible.value = false;
  rankingExam.value = null;
  stopRankingTimer();
  const target = previousFocus;
  previousFocus = null;
  nextTick(() => {
    target?.focus();
  });
}

function openScheduleModal(exam: Exam) {
  previousFocus = document.activeElement as HTMLElement | null;
  scheduleExam.value = exam;
  nextTick(() => {
    scheduleCloseBtn.value?.focus();
  });
}

function closeScheduleModal() {
  if (!scheduleExam.value) return;
  scheduleExam.value = null;
  const target = previousFocus;
  previousFocus = null;
  nextTick(() => {
    target?.focus();
  });
}

function handleEnterExam(exam: Exam) {
  if (!userStore.isLoggedIn) {
    info('请先登录再进入考场');
    router.push('/login');
    return;
  }
  router.push(`/exams/${exam.id}`);
}

function handleViewResult(exam: Exam) {
  if (!userStore.isLoggedIn) {
    info('请先登录查看考试结果');
    router.push('/login');
    return;
  }
  router.push(`/exams/${exam.id}/result`);
}

function changePage(p: number) {
  page.value = p;
  load();
}

function onWindowKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return;
  if (rankingModalVisible.value) closeRankingModal();
  else if (scheduleExam.value) closeScheduleModal();
}

watch(keyword, () => {
  if (keywordTimer !== null) clearTimeout(keywordTimer);
  keywordTimer = setTimeout(() => {
    page.value = 1;
    load();
  }, 200);
});

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown);
  load();
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown);
  stopRankingTimer();
  if (keywordTimer !== null) clearTimeout(keywordTimer);
});
</script>

<template>
  <div class="exams container">
    <header class="page-head">
      <div class="head-main">
        <h1 class="page-title">考试大厅</h1>
        <p class="page-sub">
          在线技术机试与随堂考核列表，支持 ACM / OI 赛制。场次时间均以北京时间（UTC+8）为准。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn btn-outline btn-sm" type="button" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新场次' }}
        </button>
      </div>
    </header>

    <div class="toolbar card">
      <div class="status-tabs" role="tablist" aria-label="场次状态筛选">
        <button
          v-for="tab in statusTabs"
          :key="tab.key"
          role="tab"
          type="button"
          class="tab"
          :aria-selected="statusFilter === tab.key"
          :class="{ active: statusFilter === tab.key }"
          @click="statusFilter = tab.key"
        >
          <span>{{ tab.label }}</span>
          <span class="tab-count mono">{{ statusCounts[tab.key] }}</span>
        </button>
      </div>

      <div class="search-wrap">
        <input
          v-model="keyword"
          class="input search"
          type="search"
          aria-label="搜索考试名称或说明"
          placeholder="搜索考试名称或说明…"
        />
      </div>
    </div>

    <p v-if="error" class="submit-error" role="alert">
      {{ error }}
      <button type="button" class="retry-link" @click="load">重试</button>
    </p>

    <div v-if="loading" class="state-card card">
      <div class="loading-spinner"></div>
      <p class="muted">正在加载考试场次…</p>
    </div>

    <div v-else-if="filteredList.length === 0" class="empty-card card">
      <p class="empty-title">暂无匹配的考试场次</p>
      <p class="muted">
        {{
          statusFilter !== 'ALL' || keyword.trim()
            ? '可切换状态标签或清空关键词重新查看。'
            : '当前暂未发布公开考试，请留意教师公告或稍后刷新。'
        }}
      </p>
      <button
        v-if="statusFilter !== 'ALL' || keyword.trim()"
        class="btn btn-outline btn-sm"
        type="button"
        @click="statusFilter = 'ALL'; keyword = ''"
      >
        查看全部场次
      </button>
    </div>

    <div v-else class="exam-list">
      <article
        v-for="exam in filteredList"
        :key="exam.id"
        class="exam-card card"
        :class="`card-${getRealStatus(exam).toLowerCase()}`"
      >
        <div class="exam-main">
          <div class="exam-badges">
            <span
              class="badge status-badge"
              :class="`status-${getRealStatus(exam).toLowerCase()}`"
            >
              <span v-if="getRealStatus(exam) === 'ONGOING'" class="pulse-dot" aria-hidden="true"></span>
              {{
                getRealStatus(exam) === 'ONGOING'
                  ? '进行中'
                  : getRealStatus(exam) === 'UPCOMING'
                    ? '未开始'
                    : '已结束'
              }}
            </span>
            <span class="badge mode-badge" :class="exam.mode === 'ACM' ? 'mode-acm' : 'mode-oi'">
              {{ exam.mode === 'ACM' ? 'ACM 赛制' : 'OI 赛制' }}
            </span>
            <span class="exam-id mono">#{{ exam.id }}</span>
            <span v-if="exam.submitted" class="badge badge-submitted">已交卷</span>
          </div>

          <h2 class="exam-title">{{ exam.title }}</h2>

          <p v-if="exam.description" class="exam-desc">
            {{ exam.description }}
          </p>

          <dl class="exam-meta-row">
            <div class="meta-item">
              <dt>开考时间</dt>
              <dd class="mono">{{ formatTime(exam.startTime) }}</dd>
            </div>
            <div class="meta-item">
              <dt>结束时间</dt>
              <dd class="mono">{{ formatTime(exam.endTime) }}</dd>
            </div>
            <div class="meta-item">
              <dt>考试时长</dt>
              <dd class="mono">{{ exam.durationMinutes }} 分钟</dd>
            </div>
            <div class="meta-item">
              <dt>交卷机制</dt>
              <dd>截止自动收卷</dd>
            </div>
          </dl>
        </div>

        <div class="exam-action">
          <!-- 进行中场次 -->
          <template v-if="getRealStatus(exam) === 'ONGOING'">
            <button
              v-if="!exam.submitted"
              class="btn btn-primary btn-enter accent-primary"
              type="button"
              @click="handleEnterExam(exam)"
            >
              进入考试
            </button>
            <template v-else>
              <button
                class="btn btn-submitted"
                disabled
                type="button"
                title="本场考试已交卷，不可重复作答"
              >
                已交卷
              </button>
              <button
                class="btn btn-primary btn-result accent-primary"
                type="button"
                @click="handleViewResult(exam)"
              >
                查看结果
              </button>
            </template>
            <button
              class="btn btn-outline btn-ranking"
              type="button"
              @click="openRankingModal(exam)"
            >
              实时排名
            </button>
          </template>

          <!-- 未开始场次 -->
          <template v-else-if="getRealStatus(exam) === 'UPCOMING'">
            <button
              class="btn btn-outline btn-schedule"
              type="button"
              @click="openScheduleModal(exam)"
            >
              查看安排
            </button>
            <span class="upcoming-note">等待开考</span>
          </template>

          <!-- 已结束场次：禁止展示“实时排名” -->
          <template v-else>
            <button
              class="btn btn-primary btn-result"
              type="button"
              @click="handleViewResult(exam)"
            >
              查看结果
            </button>
            <button
              class="btn btn-outline btn-ranking"
              type="button"
              @click="openRankingModal(exam)"
            >
              查看榜单
            </button>
          </template>
        </div>
      </article>
    </div>

    <div v-if="total > 0" class="pagination">
      <button class="btn btn-ghost btn-sm" type="button" :disabled="page <= 1" @click="changePage(page - 1)">
        上一页
      </button>
      <span class="page-info">第 {{ page }} / {{ totalPages }} 页</span>
      <button
        class="btn btn-ghost btn-sm"
        type="button"
        :disabled="page >= totalPages"
        @click="changePage(page + 1)"
      >
        下一页
      </button>
    </div>

    <!-- 考试榜单弹窗 -->
    <Teleport to="body">
      <div v-if="rankingModalVisible" class="modal-backdrop" @click.self="closeRankingModal">
        <div
          class="modal-card card"
          role="dialog"
          aria-modal="true"
          aria-labelledby="exam-ranking-title"
        >
          <header class="modal-header">
            <div class="modal-header-info">
              <div class="modal-tag mono">
                #{{ rankingExam?.id }} · {{ isLiveRanking ? '实时考生榜单' : '考试最终榜单' }}
              </div>
              <h2 id="exam-ranking-title" class="modal-title">{{ rankingExam?.title }}</h2>
              <div class="modal-meta">
                <span class="badge mode-badge" :class="rankingExam?.mode === 'ACM' ? 'mode-acm' : 'mode-oi'">
                  {{ rankingExam?.mode === 'ACM' ? 'ACM 赛制（通过数 + 罚时）' : 'OI 赛制（综合总分）' }}
                </span>
                <span v-if="isLiveRanking" class="refresh-tip mono">每 10 秒自动刷新</span>
              </div>
            </div>
            <button
              ref="rankingCloseBtn"
              class="btn-close"
              type="button"
              aria-label="关闭榜单"
              @click="closeRankingModal"
            >
              ✕
            </button>
          </header>

          <div class="modal-body">
            <div v-if="rankingError" class="modal-error">
              <p>{{ rankingError }}</p>
              <button
                class="btn btn-outline btn-sm"
                type="button"
                @click="rankingExam && fetchRanking(rankingExam.id)"
              >
                重试
              </button>
            </div>

            <div v-else-if="rankingLoading && rankingList.length === 0" class="modal-loading">
              <div class="loading-spinner"></div>
              <p class="muted">正在汇总考场榜单…</p>
            </div>

            <div v-else-if="rankingList.length === 0" class="modal-empty muted">
              暂无考生成绩上榜记录
            </div>

            <div v-else class="modal-table-wrap">
              <table class="rank-table">
                <thead>
                  <tr>
                    <th class="th-rank">名次</th>
                    <th>考生账号</th>
                    <th class="th-num">通过题数</th>
                    <th class="th-num">累计罚时</th>
                    <th class="th-num">综合得分</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(r, idx) in rankingList"
                    :key="r.userId"
                    class="rank-row"
                    :class="{
                      'is-me':
                        userStore.userInfo &&
                        (r.userId === userStore.userInfo.id ||
                          r.username === userStore.userInfo.username),
                    }"
                  >
                    <td class="td-rank">
                      <div class="rank-square-badge mono" :class="`rank-${idx + 1}`">
                        {{ idx + 1 }}
                      </div>
                    </td>
                    <td class="td-user">
                      <span class="user-name">{{ r.username }}</span>
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
                    <td class="td-num mono font-bold">
                      <span class="ac-text">{{ r.solved }} 题</span>
                    </td>
                    <td class="td-num mono">{{ r.penaltyMinutes }} 分钟</td>
                    <td class="td-num mono final-score">
                      {{ r.totalScore }} / {{ r.fullScore }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <footer class="modal-footer">
            <div class="footer-left">
              <span class="candidates-count mono">共 {{ rankingList.length }} 名考生</span>
              <button
                class="btn btn-ghost btn-sm btn-refresh"
                type="button"
                :disabled="rankingLoading"
                @click="rankingExam && fetchRanking(rankingExam.id)"
              >
                {{ rankingLoading ? '刷新中…' : '刷新榜单' }}
              </button>
            </div>
            <div class="footer-right">
              <button
                v-if="rankingExam && (rankingExam.submitted || getRealStatus(rankingExam) === 'ENDED')"
                class="btn btn-primary btn-sm"
                type="button"
                @click="handleViewResult(rankingExam); closeRankingModal()"
              >
                查看完整结果
              </button>
              <button class="btn btn-outline btn-sm" type="button" @click="closeRankingModal">
                关闭
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Teleport>

    <!-- 未开始场次安排详情弹窗 -->
    <Teleport to="body">
      <div v-if="scheduleExam" class="modal-backdrop" @click.self="closeScheduleModal">
        <div
          class="modal-card schedule-modal card"
          role="dialog"
          aria-modal="true"
          aria-labelledby="exam-schedule-title"
        >
          <header class="modal-header">
            <div class="modal-header-info">
              <div class="modal-tag mono">#{{ scheduleExam.id }} · 考试安排</div>
              <h2 id="exam-schedule-title" class="modal-title">{{ scheduleExam.title }}</h2>
            </div>
            <button
              ref="scheduleCloseBtn"
              class="btn-close"
              type="button"
              aria-label="关闭考试安排"
              @click="closeScheduleModal"
            >
              ✕
            </button>
          </header>

          <div class="schedule-body">
            <p v-if="scheduleExam.description" class="schedule-desc">{{ scheduleExam.description }}</p>
            <dl class="schedule-grid">
              <div>
                <dt>赛制模式</dt>
                <dd>{{ scheduleExam.mode === 'ACM' ? 'ACM 赛制' : 'OI 赛制' }}</dd>
              </div>
              <div>
                <dt>考试时长</dt>
                <dd class="mono">{{ scheduleExam.durationMinutes }} 分钟</dd>
              </div>
              <div>
                <dt>开考时间</dt>
                <dd class="mono">{{ formatTime(scheduleExam.startTime) }}</dd>
              </div>
              <div>
                <dt>结束时间</dt>
                <dd class="mono">{{ formatTime(scheduleExam.endTime) }}</dd>
              </div>
              <div>
                <dt>实名登记</dt>
                <dd>{{ scheduleExam.requireStudentInfo ? '开考前需填写学号与姓名' : '无需额外登记' }}</dd>
              </div>
              <div>
                <dt>交卷策略</dt>
                <dd>截止自动提交并汇总作答</dd>
              </div>
            </dl>
          </div>

          <footer class="modal-footer">
            <span class="candidates-count">开考后可从此页面点击「进入考试」</span>
            <button class="btn btn-outline btn-sm" type="button" @click="closeScheduleModal">
              知道了
            </button>
          </footer>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.exams {
  padding-top: 32px;
  padding-bottom: 64px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.page-title {
  margin: 0 0 6px;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.4px;
  color: var(--ink);
}

.page-sub {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px 16px;
  padding: 14px 16px;
  margin-bottom: 20px;
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: var(--kece-card-radius, 0px);
}

.status-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: 0;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--ink-secondary);
  border: 1px solid var(--kece-border, var(--line));
  background: #ffffff;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease, background 0.15s ease;
}

.tab:hover {
  color: var(--ink);
  border-color: #9ca3af;
}

.tab.active {
  background: var(--kece-accent-soft, #eef6f4);
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
  font-weight: 600;
}

.tab-count {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.tab.active .tab-count {
  color: var(--kece-accent-ink, #0f5f53);
}

.search-wrap {
  flex: 1 1 240px;
  max-width: 340px;
}

.search {
  width: 100%;
}

.submit-error {
  margin: 0 0 16px;
  padding: 10px 14px;
  border: 1px solid var(--kece-danger, #c4314b);
  background: var(--kece-danger-soft, #fdf2f4);
  color: var(--kece-danger, #c4314b);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.retry-link {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.state-card,
.empty-card {
  padding: 40px 24px;
  text-align: center;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: var(--kece-card-radius, 0px);
  background: #ffffff;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.empty-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}

.exam-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.exam-card {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 22px;
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: var(--kece-card-radius, 0px);
}

.exam-card.card-ongoing {
  border-left: 3px solid var(--kece-accent, #137969);
}

.exam-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.exam-badges {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 0;
  border: 1px solid var(--kece-border, var(--line));
}

.status-ongoing {
  background: var(--kece-accent-soft, #eef6f4);
  border-color: var(--kece-accent, #137969);
  color: var(--kece-accent-ink, #0f5f53);
}

.status-upcoming {
  background: var(--kece-warning-soft, #fff7ed);
  border-color: #fed7aa;
  color: var(--kece-warning, #b25c16);
}

.status-ended {
  background: var(--kece-surface-muted, #f8fafc);
  border-color: var(--kece-border, var(--line));
  color: var(--ink-secondary);
}

.pulse-dot {
  width: 6px;
  height: 6px;
  background: var(--kece-accent, #137969);
}

.mode-acm {
  background: #ffffff;
  color: var(--ink);
  border-color: #cbd5e1;
}

.mode-oi {
  background: #ffffff;
  color: var(--ink-secondary);
}

.badge-submitted {
  background: #f1f5f9;
  color: #334155;
  border-color: #cbd5e1;
}

.exam-id {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.exam-title {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  color: var(--ink);
  line-height: 1.35;
}

.exam-desc {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-secondary);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.exam-meta-row {
  margin: 4px 0 0;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 24px;
  padding-top: 10px;
  border-top: 1px solid #f1f5f9;
}

.meta-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
}

.meta-item dt {
  color: var(--ink-tertiary);
}

.meta-item dd {
  margin: 0;
  color: var(--ink);
  font-weight: 500;
}

.exam-action {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-end;
  gap: 8px;
  min-width: 150px;
  flex-shrink: 0;
}

.exam-action .btn {
  width: 140px;
  justify-content: center;
}

.accent-primary {
  background: var(--kece-accent, #137969);
  border-color: var(--kece-accent, #137969);
  color: #ffffff;
}

.accent-primary:hover:not(:disabled) {
  background: var(--kece-accent-hover, #0e6053);
  border-color: var(--kece-accent-hover, #0e6053);
}

.btn-submitted {
  background: #f1f5f9;
  color: var(--ink-tertiary);
  border: 1px solid var(--kece-border, var(--line));
  cursor: not-allowed;
}

.upcoming-note {
  font-size: 12px;
  color: var(--ink-tertiary);
}

/* 模态框样式 */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-card {
  width: 100%;
  max-width: 780px;
  background: #ffffff;
  border: 1px solid var(--kece-border, var(--line));
  border-radius: 0;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.2);
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  overflow: hidden;
}

.schedule-modal {
  max-width: 560px;
}

.modal-header {
  padding: 18px 22px;
  border-bottom: 1px solid var(--kece-border, var(--line));
  background: var(--kece-surface-muted, #f8fafc);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.modal-tag {
  font-size: 12px;
  color: var(--ink-secondary);
  margin-bottom: 4px;
}

.modal-title {
  margin: 0 0 8px;
  font-size: 19px;
  font-weight: 700;
  color: var(--ink);
}

.modal-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.refresh-tip {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.btn-close {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--kece-border, var(--line));
  background: #ffffff;
  color: var(--ink-secondary);
  cursor: pointer;
  flex-shrink: 0;
}

.btn-close:hover {
  border-color: var(--ink);
  color: var(--ink);
}

.modal-body {
  padding: 0;
  overflow-y: auto;
  max-height: calc(85vh - 150px);
}

.modal-loading,
.modal-empty,
.modal-error {
  padding: 48px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.modal-table-wrap {
  width: 100%;
  overflow-x: auto;
}

.rank-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
}

.rank-table th {
  text-align: left;
  padding: 12px 16px;
  color: var(--ink-secondary);
  font-weight: 600;
  background: #ffffff;
  border-bottom: 1px solid var(--kece-border, var(--line));
}

.rank-table td {
  padding: 12px 16px;
  border-bottom: 1px solid #f1f5f9;
}

.th-rank,
.td-rank {
  width: 68px;
  text-align: center;
}

.th-num,
.td-num {
  text-align: right;
}

.rank-row.is-me {
  background: var(--kece-accent-soft, #eef6f4);
}

.rank-square-badge {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid var(--kece-border, var(--line));
  background: #ffffff;
}

.rank-square-badge.rank-1 {
  background: #111827;
  color: #ffffff;
  border-color: #111827;
}

.rank-square-badge.rank-2 {
  background: #4b5563;
  color: #ffffff;
  border-color: #4b5563;
}

.rank-square-badge.rank-3 {
  background: #6b7280;
  color: #ffffff;
  border-color: #6b7280;
}

.user-name {
  font-weight: 600;
  color: var(--ink);
}

.me-tag {
  margin-left: 6px;
  font-size: 11px;
  color: var(--kece-accent-ink, #0f5f53);
  font-weight: 700;
}

.ac-text {
  color: var(--kece-success, #16794f);
}

.final-score {
  font-weight: 700;
  color: var(--ink);
}

.schedule-body {
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.schedule-desc {
  margin: 0;
  font-size: 14px;
  color: var(--ink-secondary);
  line-height: 1.6;
}

.schedule-grid {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
  padding: 14px;
  background: var(--kece-surface-muted, #f8fafc);
  border: 1px solid var(--kece-border, var(--line));
}

.schedule-grid dt {
  font-size: 12px;
  color: var(--ink-tertiary);
  margin-bottom: 2px;
}

.schedule-grid dd {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink);
  font-weight: 600;
}

.modal-footer {
  padding: 14px 22px;
  background: var(--kece-surface-muted, #f8fafc);
  border-top: 1px solid var(--kece-border, var(--line));
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.footer-left,
.footer-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.candidates-count {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 28px 0 12px;
}

.page-info {
  color: var(--ink-secondary);
  font-size: 14px;
}

@media (max-width: 768px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }
  .exam-card {
    flex-direction: column;
    gap: 16px;
  }
  .exam-action {
    align-items: stretch;
    min-width: 0;
  }
  .exam-action .btn {
    width: 100%;
  }
  .schedule-grid {
    grid-template-columns: 1fr;
  }
}
</style>
