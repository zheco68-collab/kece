<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { examApi, type Exam, type RankRow } from '@/api/exam'
import { useUserStore } from '@/stores/user'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const userStore = useUserStore()
const { info } = useToast()

const keyword = ref('')
const statusFilter = ref<'ALL' | 'ONGOING' | 'UPCOMING' | 'ENDED'>('ALL')
const page = ref(1)
const size = 10

const rawList = ref<Exam[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

const rankingModalVisible = ref(false)
const rankingExam = ref<Exam | null>(null)
const rankingList = ref<RankRow[]>([])
const rankingLoading = ref(false)
let rankingTimer: ReturnType<typeof setInterval> | null = null

async function fetchRanking(examId: number) {
  rankingLoading.value = true
  try {
    rankingList.value = await examApi.ranking(examId)
  } catch {
    /* 优雅降级 */
  } finally {
    rankingLoading.value = false
  }
}

function openRankingModal(exam: Exam) {
  rankingExam.value = exam
  rankingModalVisible.value = true
  fetchRanking(exam.id)
  if (rankingTimer) clearInterval(rankingTimer)
  rankingTimer = setInterval(() => {
    if (rankingExam.value && rankingModalVisible.value) {
      fetchRanking(rankingExam.value.id)
    }
  }, 10000)
}

function closeRankingModal() {
  rankingModalVisible.value = false
  if (rankingTimer) {
    clearInterval(rankingTimer)
    rankingTimer = null
  }
}

onBeforeUnmount(() => {
  if (rankingTimer) {
    clearInterval(rankingTimer)
    rankingTimer = null
  }
})

const statusTabs = [
  { key: 'ALL', label: '全部考试' },
  { key: 'ONGOING', label: '进行中' },
  { key: 'UPCOMING', label: '未开始' },
  { key: 'ENDED', label: '已结束' },
] as const

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await examApi.listExams({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size,
    })
    rawList.value = res.list
    total.value = res.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载考试列表失败'
  } finally {
    loading.value = false
  }
}

/** 客户端辅助状态推断（结合服务器返回的 sessionStatus 与本地时间） */
function getRealStatus(exam: Exam): 'ONGOING' | 'UPCOMING' | 'ENDED' {
  if (exam.sessionStatus === 'CANCELLED') return 'ENDED'
  const now = Date.now()
  const start = exam.startTime ? new Date(exam.startTime).getTime() : 0
  const end = exam.endTime ? new Date(exam.endTime).getTime() : 0

  if (end && now >= end) {
    return 'ENDED'
  }
  if (start && now < start) {
    return 'UPCOMING'
  }
  if (exam.sessionStatus === 'ENDED') {
    return 'ENDED'
  }
  return 'ONGOING'
}

const filteredList = computed(() => {
  if (statusFilter.value === 'ALL') return rawList.value
  return rawList.value.filter((e) => getRealStatus(e) === statusFilter.value)
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return iso.slice(0, 16).replace('T', ' ')
}

function handleAction(exam: Exam) {
  const status = getRealStatus(exam)
  if (status === 'ONGOING') {
    if (!userStore.isLoggedIn) {
      info('请先登录再进入考场')
      router.push('/login')
      return
    }
    router.push(`/exams/${exam.id}`)
  } else if (status === 'ENDED') {
    router.push(`/exams/${exam.id}/result`)
  }
}

function changePage(p: number) {
  page.value = p
  load()
}

watch(keyword, () => {
  page.value = 1
  load()
})

onMounted(() => {
  load()
})
</script>

<template>
  <div class="exams container">
    <!-- 头部说明卡片 -->
    <header class="page-head">
      <div class="head-main">
        <h1 class="page-title">考试大厅</h1>
        <p class="muted">全真技术工程机试 · 倒计时自动结算 · 支持 ACM 与 OI 赛制</p>
      </div>
    </header>

    <!-- 检索与状态筛选工具条 -->
    <div class="toolbar card">
      <div class="search-wrap">
        <input
          v-model="keyword"
          class="input search"
          type="search"
          placeholder="搜索考试名称或说明…"
        />
      </div>

      <div class="status-tabs">
        <button
          v-for="tab in statusTabs"
          :key="tab.key"
          class="tab"
          :class="{ active: statusFilter === tab.key }"
          @click="statusFilter = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>
    </div>

    <!-- 状态反馈 -->
    <p v-if="error" class="submit-error">{{ error }}</p>
    <div v-if="loading" class="state-card card"><div class="loading-spinner"></div><p class="muted">正在加载考试场次…</p></div>
    <p v-else-if="filteredList.length === 0" class="empty">当前暂无可参与的考试场次</p>

    <!-- 考试场次 Bento 直角卡片流 -->
    <div v-else class="exam-list">
      <article
        v-for="exam in filteredList"
        :key="exam.id"
        class="exam-card card"
      >
        <div class="exam-main">
          <div class="exam-title-row">
            <h2 class="exam-title">
              <span class="exam-id">#{{ exam.id }}</span>
              <span class="exam-name">{{ exam.title }}</span>
            </h2>
            <div class="exam-badges">
              <span class="badge mode-badge" :class="exam.mode === 'ACM' ? 'mode-acm' : 'mode-oi'">
                {{ exam.mode === 'ACM' ? 'ACM 赛制' : 'OI 赛制' }}
              </span>
              <span
                class="badge status-badge"
                :class="`status-${getRealStatus(exam).toLowerCase()}`"
              >
                <span v-if="getRealStatus(exam) === 'ONGOING'" class="pulse-dot"></span>
                {{
                  getRealStatus(exam) === 'ONGOING'
                    ? '进行中'
                    : getRealStatus(exam) === 'UPCOMING'
                      ? '未开始'
                      : '已结束'
                }}
              </span>
              <span v-if="exam.submitted" class="badge badge-submitted">
                已交卷
              </span>
            </div>
          </div>

          <p v-if="exam.description" class="exam-desc">
            {{ exam.description }}
          </p>

          <!-- 考试规格与参数排盘 -->
          <div class="exam-meta-row">
            <div class="meta-item">
              <span class="meta-k">考试时长</span>
              <span class="meta-v mono">{{ exam.durationMinutes }} 分钟</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">开考时间</span>
              <span class="meta-v mono">{{ formatTime(exam.startTime) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">结束时间</span>
              <span class="meta-v mono">{{ formatTime(exam.endTime) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">自动交卷</span>
              <span class="meta-v">时间截止强制归档</span>
            </div>
          </div>
        </div>

        <div class="exam-action">
          <!-- 实时查看考生排名按钮（在考试外面直接查看） -->
          <button
            class="btn btn-secondary btn-ranking"
            type="button"
            @click="openRankingModal(exam)"
          >
            <svg class="rank-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="20" x2="18" y2="10" />
              <line x1="12" y1="20" x2="12" y2="4" />
              <line x1="6" y1="20" x2="6" y2="14" />
            </svg>
            <span>实时排名</span>
          </button>

          <!-- 本场考试已提交：进入考场按钮变灰，提供归档战报按钮 -->
          <template v-if="exam.submitted">
            <button
              class="btn btn-submitted"
              disabled
              type="button"
              title="本场考试已交卷，不可重复进入考场"
            >
              已交卷
            </button>
            <button
              class="btn btn-primary btn-report"
              type="button"
              @click="router.push(`/exams/${exam.id}/result`)"
            >
              <span>查看战报</span>
              <svg class="cta-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </button>
          </template>

          <!-- 未提交情况 -->
          <template v-else>
            <button
              v-if="getRealStatus(exam) === 'ONGOING'"
              class="btn btn-primary btn-enter"
              type="button"
              @click="handleAction(exam)"
            >
              <span>进入考场</span>
              <svg class="cta-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </button>

            <button
              v-else-if="getRealStatus(exam) === 'ENDED'"
              class="btn btn-primary btn-result"
              type="button"
              @click="handleAction(exam)"
            >
              <span>查看战报与榜单</span>
              <svg class="cta-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </button>

            <button
              v-else
              class="btn btn-outline btn-upcoming"
              disabled
              type="button"
            >
              等待开考
            </button>
          </template>
        </div>
      </article>
    </div>

    <!-- 分页导航 -->
    <div v-if="total > 0" class="pagination">
      <button class="btn btn-ghost btn-sm" :disabled="page <= 1" @click="changePage(page - 1)">
        上一页
      </button>
      <span class="page-info">第 {{ page }} / {{ totalPages }} 页</span>
      <button
        class="btn btn-ghost btn-sm"
        :disabled="page >= totalPages"
        @click="changePage(page + 1)"
      >
        下一页
      </button>
    </div>

    <!-- 实时考生排名直角弹窗 (Bento 风格模态框) -->
    <Teleport to="body">
      <div v-if="rankingModalVisible" class="modal-backdrop" @click.self="closeRankingModal">
        <div class="modal-card card">
          <header class="modal-header">
            <div class="modal-header-info">
              <div class="modal-tag mono">#{{ rankingExam?.id }} · 实时考生天梯榜</div>
              <h2 class="modal-title">{{ rankingExam?.title }}</h2>
              <div class="modal-meta">
                <span class="badge mode-badge" :class="rankingExam?.mode === 'ACM' ? 'mode-acm' : 'mode-oi'">
                  {{ rankingExam?.mode === 'ACM' ? 'ACM 赛制 (AC 题数 + 罚时)' : 'OI 赛制 (综合总分)' }}
                </span>
                <span class="refresh-tip mono">自动每 10 秒刷新</span>
              </div>
            </div>
            <button class="btn-close" type="button" @click="closeRankingModal">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </header>

          <div class="modal-body">
            <div v-if="rankingLoading && rankingList.length === 0" class="modal-loading">
              <div class="loading-spinner"></div>
              <p class="muted">正在计算并汇总考场实时成绩…</p>
            </div>

            <div v-else-if="rankingList.length === 0" class="modal-empty muted">
              当前场次暂无完赛考生提交记录
            </div>

            <div v-else class="modal-table-wrap">
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
                    v-for="(r, idx) in rankingList"
                    :key="r.userId"
                    class="rank-row"
                    :class="{
                      'is-me': userStore.userInfo && (r.userId === userStore.userInfo.id || r.username === userStore.userInfo.username),
                      'top-1': idx === 0,
                      'top-2': idx === 1,
                      'top-3': idx === 2,
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
                        v-if="userStore.userInfo && (r.userId === userStore.userInfo.id || r.username === userStore.userInfo.username)"
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
              <span class="candidates-count mono">共 {{ rankingList.length }} 名考生在榜</span>
              <button
                class="btn btn-ghost btn-sm btn-refresh"
                type="button"
                :disabled="rankingLoading"
                @click="rankingExam && fetchRanking(rankingExam.id)"
              >
                {{ rankingLoading ? '刷新中…' : '立即刷新 ↻' }}
              </button>
            </div>
            <div class="footer-right">
              <button
                v-if="rankingExam && (rankingExam.submitted || getRealStatus(rankingExam) === 'ENDED')"
                class="btn btn-primary btn-sm"
                type="button"
                @click="router.push(`/exams/${rankingExam.id}/result`); closeRankingModal();"
              >
                查看完整战报 →
              </button>
              <button class="btn btn-outline btn-sm" type="button" @click="closeRankingModal">
                关闭
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.exams {
  padding-top: 40px;
  padding-bottom: 64px;
}

/* 头部 */
.page-head {
  margin-bottom: 24px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.5px;
}

/* 搜索与工具条 */
.toolbar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  margin-bottom: 24px;
  border-radius: 0;
}
.search {
  max-width: 360px;
}
.status-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.tab {
  padding: 7px 16px;
  border-radius: 0;
  font-size: 14px;
  color: var(--ink-secondary);
  border: 1px solid var(--line);
  background: var(--surface);
  cursor: pointer;
  transition: all 0.15s ease;
}
.tab.active {
  background: var(--button);
  border-color: var(--button);
  color: #fff;
}

.loading {
  padding: 32px 0;
  text-align: center;
}
.empty {
  padding: 48px 0;
  text-align: center;
  color: var(--ink-tertiary);
  font-size: 14px;
}

/* 考试卡片列表 */
.exam-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.exam-card {
  padding: 24px 28px;
  border-radius: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.exam-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--ink);
}

.exam-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.exam-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.exam-title {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  color: var(--ink);
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.exam-id {
  font-size: 14px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  color: var(--ink-tertiary);
  font-weight: 600;
}
.exam-name {
  letter-spacing: -0.2px;
}

.exam-badges {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mode-badge {
  border-radius: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
}
.mode-acm {
  background: var(--ink);
  color: #fff;
}
.mode-oi {
  background: #2b2b2b;
  color: #fff;
}

.status-badge {
  border-radius: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.status-ongoing {
  background: #e8f5e9;
  color: #2e7d32;
  border: 1px solid rgba(46, 125, 50, 0.3);
}
.status-upcoming {
  background: #f5f5f5;
  color: var(--ink-secondary);
  border: 1px solid var(--line);
}
.status-ended {
  background: #f0f0f0;
  color: var(--ink-tertiary);
  border: 1px solid var(--line);
}

.pulse-dot {
  width: 6px;
  height: 6px;
  background: #2e7d32;
  border-radius: 0;
  animation: pulse 1s infinite alternate;
}
@keyframes pulse {
  from { opacity: 0.3; transform: scale(0.8); }
  to { opacity: 1; transform: scale(1.2); }
}

.exam-desc {
  margin: 0;
  font-size: 14px;
  color: var(--ink-secondary);
  line-height: 1.5;
  max-width: 800px;
}

.exam-meta-row {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
  padding-top: 4px;
}
.meta-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.meta-k {
  color: var(--ink-tertiary);
  font-size: 12px;
}
.meta-v {
  color: var(--ink-secondary);
  font-weight: 500;
}

/* 操作区 */
.exam-action {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.badge-submitted {
  border-radius: 0;
  font-size: 12px;
  font-weight: 700;
  padding: 2px 10px;
  background: #374151;
  color: #fff;
}
.btn-enter,
.btn-result,
.btn-report {
  border-radius: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  font-weight: 600;
  font-size: 14px;
}
.btn-ranking {
  border-radius: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  font-weight: 600;
  font-size: 13px;
  background: var(--surface);
  color: var(--ink);
  border: 1px solid var(--line);
  transition: all 0.15s ease;
}
.btn-ranking:hover {
  background: var(--bg);
  border-color: var(--ink);
}
.rank-icon {
  width: 14px;
  height: 14px;
}
.btn-submitted {
  border-radius: 0;
  padding: 10px 18px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-tertiary);
  background: var(--bg-tertiary, #e4e4e7);
  border: 1px solid var(--line);
  cursor: not-allowed;
}
.cta-arrow {
  width: 16px;
  height: 16px;
}
.btn-upcoming {
  border-radius: 0;
  padding: 10px 18px;
  font-size: 13px;
  color: var(--ink-tertiary);
  background: var(--bg);
  cursor: not-allowed;
}

/* 模态弹窗 */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}
.modal-card {
  width: 100%;
  max-width: 760px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--ink);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.25);
  border-radius: 0;
  overflow: hidden;
}
.modal-header {
  padding: 20px 24px;
  background: var(--bg);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.modal-header-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.modal-tag {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-tertiary);
}
.modal-title {
  margin: 0;
  font-size: 20px;
  font-weight: 800;
  color: var(--ink);
}
.modal-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}
.refresh-tip {
  font-size: 11px;
  color: var(--ink-tertiary);
}
.btn-close {
  background: none;
  border: 1px solid var(--line);
  color: var(--ink-secondary);
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-radius: 0;
  transition: all 0.15s ease;
}
.btn-close:hover {
  background: var(--ink);
  color: #fff;
  border-color: var(--ink);
}
.btn-close svg {
  width: 16px;
  height: 16px;
}

.modal-body {
  padding: 0;
  overflow-y: auto;
  max-height: calc(85vh - 160px);
}
.modal-loading {
  padding: 60px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.modal-empty {
  padding: 60px 24px;
  text-align: center;
  font-size: 14px;
}
.loading-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid var(--line);
  border-top-color: var(--ink);
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.modal-table-wrap {
  width: 100%;
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
  background: var(--bg);
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: 1;
}
.rank-table td {
  padding: 12px 18px;
  border-bottom: 1px solid var(--line);
}
.th-rank, .td-rank { width: 64px; text-align: center; }
.th-num, .td-num { text-align: right; }

.rank-row.is-me {
  background: #f8faff;
  box-shadow: inset 3px 0 0 #0288d1;
}
.rank-square-badge {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 800;
  border-radius: 0;
  background: var(--bg);
  border: 1px solid var(--line);
}
.rank-square-badge.rank-1 { background: #000; color: #fff; border-color: #000; }
.rank-square-badge.rank-2 { background: #333; color: #fff; border-color: #333; }
.rank-square-badge.rank-3 { background: #666; color: #fff; border-color: #666; }

.user-name {
  font-weight: 600;
  color: var(--ink);
}
.me-tag {
  margin-left: 6px;
  font-size: 11px;
  color: #0288d1;
  font-weight: 700;
}
.ac-text {
  color: var(--success);
}
.final-score {
  font-weight: 700;
  color: var(--ink);
}

.modal-footer {
  padding: 16px 24px;
  background: var(--bg);
  border-top: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.footer-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.candidates-count {
  font-size: 12px;
  color: var(--ink-tertiary);
}
.btn-refresh {
  border-radius: 0;
  font-size: 12px;
  padding: 4px 10px;
}
.footer-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 分页 */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 32px 0 24px;
}
.page-info {
  color: var(--ink-secondary);
  font-size: 14px;
}

@media (max-width: 768px) {
  .exam-card {
    flex-direction: column;
    align-items: stretch;
  }
  .exam-action {
    margin-top: 12px;
  }
  .btn-enter,
  .btn-result,
  .btn-report,
  .btn-ranking,
  .btn-submitted,
  .btn-upcoming {
    width: 100%;
    justify-content: center;
  }
}
</style>
