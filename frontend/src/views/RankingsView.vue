<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { rankApi, type RankUser } from '@/api/rank'
import Pagination from '@/components/Pagination.vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

// 选中的题型分类
type CategoryKey = 'ALGO' | 'SQL' | 'OBJECTIVE'
const currentCategory = ref<CategoryKey>('ALGO')

const categories = [
  {
    key: 'ALGO' as CategoryKey,
    name: '算法编程',
    subTitle: 'C++ / Java / Python / ArkTS 沙箱评测',
    metricLabel: '通过题数',
    metricUnit: '题',
  },
  {
    key: 'SQL' as CategoryKey,
    name: 'SQL 数据库',
    subTitle: 'MySQL 8.0 临时库对拍校验',
    metricLabel: '通过题数',
    metricUnit: '题',
  },
  {
    key: 'OBJECTIVE' as CategoryKey,
    name: '客观理论',
    subTitle: '单选题 / 多选题 / 判断题',
    metricLabel: '答对题数',
    metricUnit: '题',
  },
]

const currentCategoryMeta = computed(() => {
  return categories.find((c) => c.key === currentCategory.value) || categories[0]
})

// 搜索与分页
const keyword = ref('')
const page = ref(1)
const size = 20

const list = ref<RankUser[]>([])
const total = ref(0)
const myRank = ref<RankUser | null>(null)
const loading = ref(false)
const error = ref('')

// 获取头像首字母占位
function getInitial(name?: string | null): string {
  if (!name) return '?'
  return name.trim().charAt(0).toUpperCase()
}

// 格式化时间
function formatTime(isoStr: string | null): string {
  if (!isoStr) return '—'
  const d = new Date(isoStr)
  if (Number.isNaN(d.getTime())) return isoStr
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const date = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${date} ${h}:${min}`
}

// 角色标签
function getRoleBadge(roles: string | null) {
  if (!roles) return null
  if (roles.includes('ADMIN')) return { label: '管理员', class: 'role-admin' }
  if (roles.includes('TEACHER')) return { label: '教师', class: 'role-teacher' }
  return null
}

// 加载排行榜数据
async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await rankApi.getRankings({
      type: currentCategory.value,
      page: page.value,
      size,
      keyword: keyword.value.trim() || undefined,
    })
    list.value = res.list || []
    total.value = res.total || 0
    myRank.value = res.myRank || null
  } catch (err: any) {
    error.value = err?.message || '加载排行榜数据失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

// 切换分类
function switchCategory(key: CategoryKey) {
  if (currentCategory.value === key) return
  currentCategory.value = key
  page.value = 1
  keyword.value = ''
  load()
}

// 翻页
function changePage(p: number) {
  page.value = p
  load()
  window.scrollTo({ top: 180, behavior: 'smooth' })
}

// 搜索防抖
let keywordTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (keywordTimer !== null) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    page.value = 1
    load()
  }, 300)
})

onMounted(() => {
  load()
})

onBeforeUnmount(() => {
  if (keywordTimer !== null) clearTimeout(keywordTimer)
})
</script>

<template>
  <div class="rankings container">
    <!-- 顶部标题与个人当前排名概览（遵循 QuestionsView.vue 的 page-head 规范） -->
    <div class="page-head">
      <div class="page-head-left">
        <h1 class="page-title">全域排名</h1>
        <p class="muted head-sub">
          多维实战做题天梯 · 编程算法、SQL 数据库与客观理论题全量实时排盘
        </p>
      </div>

      <!-- 登录用户自身位次卡片 -->
      <div v-if="userStore.isLoggedIn" class="head-rank-card card">
        <div class="my-rank-header">
          <RouterLink :to="`/profile/${userStore.userInfo!.id}`" class="my-avatar-wrap" title="查看我的主页">
            <img
              v-if="userStore.userInfo?.avatarUrl"
              :src="userStore.userInfo.avatarUrl"
              class="my-avatar-img"
              alt="Avatar"
            />
            <div v-else class="my-avatar-placeholder mono">
              {{ getInitial(userStore.userInfo?.username) }}
            </div>
          </RouterLink>
          <div class="my-info-text">
            <div class="my-name-row">
              <RouterLink :to="`/profile/${userStore.userInfo!.id}`" class="my-name">
                {{ userStore.userInfo?.username }}
              </RouterLink>
              <span class="badge badge-self">我的档位</span>
            </div>
            <span class="muted my-category-text mono">{{ currentCategoryMeta.name }}</span>
          </div>
        </div>

        <div class="my-rank-stats">
          <div class="my-stat-item">
            <span class="stat-lbl">天梯名次</span>
            <span class="stat-num mono highlight">
              {{ myRank ? `#${myRank.rank}` : '未上榜' }}
            </span>
          </div>
          <div class="my-stat-item">
            <span class="stat-lbl">{{ currentCategoryMeta.metricLabel }}</span>
            <span class="stat-num mono">{{ myRank?.solvedCount ?? 0 }} 题</span>
          </div>
          <div v-if="currentCategory === 'OBJECTIVE'" class="my-stat-item">
            <span class="stat-lbl">累计得分</span>
            <span class="stat-num mono">{{ myRank?.totalScore ?? 0 }} 分</span>
          </div>
          <div class="my-stat-item">
            <span class="stat-lbl">AC 通过率</span>
            <span class="stat-num mono">
              {{ myRank?.acceptRate != null ? `${myRank.acceptRate}%` : '—' }}
            </span>
          </div>
          <RouterLink :to="`/profile/${userStore.userInfo!.id}`" class="btn btn-ghost btn-sm my-profile-btn">
            我的主页
          </RouterLink>
        </div>
      </div>

      <!-- 未登录访客提示卡片 -->
      <div v-else class="head-guest-card card">
        <span class="muted guest-tip">登录后即可追踪自己在全站天梯中的实时名次与做题战绩</span>
        <RouterLink to="/login" class="btn btn-primary btn-sm">登录查看</RouterLink>
      </div>
    </div>

    <!-- 综合筛选工具栏（与 QuestionsView.vue 的 toolbar card 规范完全对齐） -->
    <div class="toolbar card">
      <div class="toolbar-left">
        <!-- 搜索输入框 -->
        <div class="search-wrap">
          <svg class="search-icon" viewBox="0 0 16 16" fill="currentColor">
            <path
              d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001c.03.04.062.078.098.115l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85a1.007 1.007 0 0 0-.115-.1zM12 6.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z"
            />
          </svg>
          <input
            v-model="keyword"
            class="input search-input"
            type="search"
            placeholder="搜索选手用户名…"
          />
        </div>

        <!-- 分类直角 Tab 组（单色灰度区分） -->
        <div class="category-tabs">
          <button
            v-for="cat in categories"
            :key="cat.key"
            class="tab-btn"
            :class="{ active: currentCategory === cat.key }"
            @click="switchCategory(cat.key)"
          >
            {{ cat.name }}
          </button>
        </div>
      </div>

      <div class="toolbar-right">
        <span class="muted mono list-total-count">
          共 {{ total }} 位选手
        </span>
      </div>
    </div>

    <!-- 列表加载与错误状态 -->
    <p v-if="error" class="submit-error">{{ error }}</p>
    <div v-if="loading" class="loading-box card muted">
      <span class="loading-spin"></span>
      <span>正在加载天梯排名…</span>
    </div>
    <div v-else-if="list.length === 0" class="empty-box card">
      <p class="empty-title">未找到匹配的选手</p>
      <p class="muted empty-desc">尝试调整搜索关键词或前往刷题上榜</p>
    </div>

    <!-- 排行榜数据表格（严格直角规范，与 DataTable / QuestionsView 样式对齐） -->
    <div v-else class="data-table-wrap card">
      <table class="data-table">
        <thead>
          <tr>
            <th class="col-rank">名次</th>
            <th class="col-user">选手</th>
            <th class="col-solved mono">{{ currentCategoryMeta.metricLabel }}</th>
            <th v-if="currentCategory === 'OBJECTIVE'" class="col-score mono">累计总分</th>
            <th class="col-submissions mono">总作答次数</th>
            <th class="col-rate mono">AC 通过率</th>
            <th class="col-time mono">最后活跃时间</th>
            <th class="col-action">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in list"
            :key="row.userId"
            class="rank-row"
            :class="{ 'is-self': row.userId === userStore.userInfo?.id }"
          >
            <!-- 名次徽标（前三名使用单色灰阶徽章，不使用花哨表情） -->
            <td class="col-rank">
              <span v-if="row.rank === 1" class="rank-badge rank-1 mono">#1</span>
              <span v-else-if="row.rank === 2" class="rank-badge rank-2 mono">#2</span>
              <span v-else-if="row.rank === 3" class="rank-badge rank-3 mono">#3</span>
              <span v-else class="rank-num mono">#{{ row.rank }}</span>
            </td>

            <!-- 用户信息（头像、用户名、角色标签、个性签名） -->
            <td class="col-user">
              <div class="user-cell">
                <RouterLink
                  :to="`/profile/${row.userId}`"
                  class="user-avatar-wrap"
                  title="点击查看个人主页"
                >
                  <img
                    v-if="row.avatarUrl"
                    :src="row.avatarUrl"
                    class="avatar-img"
                    :alt="row.username"
                  />
                  <div v-else class="avatar-placeholder mono">
                    {{ getInitial(row.username) }}
                  </div>
                </RouterLink>
                <div class="user-meta">
                  <div class="user-title-row">
                    <RouterLink
                      :to="`/profile/${row.userId}`"
                      class="user-name"
                      title="点击查看个人主页"
                    >
                      {{ row.username }}
                    </RouterLink>
                    <span
                      v-if="getRoleBadge(row.roles)"
                      class="badge"
                      :class="getRoleBadge(row.roles)!.class"
                    >
                      {{ getRoleBadge(row.roles)!.label }}
                    </span>
                    <span v-if="row.userId === userStore.userInfo?.id" class="badge badge-self mono">
                      YOU
                    </span>
                  </div>
                  <div class="user-bio muted" :title="row.bio || ''">
                    {{ row.bio || '无个性签名' }}
                  </div>
                </div>
              </div>
            </td>

            <!-- 通过题数 -->
            <td class="col-solved mono">
              <span class="metric-strong">{{ row.solvedCount }}</span>
              <span class="muted unit">题</span>
            </td>

            <!-- 累计总分 (仅客观题显示) -->
            <td v-if="currentCategory === 'OBJECTIVE'" class="col-score mono">
              <span class="metric-strong">{{ row.totalScore ?? 0 }}</span>
              <span class="muted unit">分</span>
            </td>

            <!-- 总作答次数 -->
            <td class="col-submissions mono">
              <span>{{ row.totalSubmissions }}</span>
              <span class="muted unit">次</span>
            </td>

            <!-- AC 通过率 -->
            <td class="col-rate mono">
              <div class="rate-container">
                <span class="rate-text">{{ row.acceptRate != null ? `${row.acceptRate}%` : '—' }}</span>
                <div class="rate-track">
                  <div class="rate-bar" :style="{ width: `${row.acceptRate || 0}%` }"></div>
                </div>
              </div>
            </td>

            <!-- 最后活跃时间 -->
            <td class="col-time mono muted">
              {{ formatTime(row.lastAcceptedAt) }}
            </td>

            <!-- 操作：进入主页 -->
            <td class="col-action">
              <RouterLink :to="`/profile/${row.userId}`" class="btn btn-ghost btn-sm action-btn">
                查看主页
              </RouterLink>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 标准分页组件（复用 Pagination.vue） -->
    <Pagination
      v-if="total > size"
      :page="page"
      :size="size"
      :total="total"
      @update:page="changePage"
    />
  </div>
</template>

<style scoped>
/* 严格遵循全站直角与灰阶极简原则 */
* {
  border-radius: 0 !important;
}

.rankings {
  padding: 32px 24px 64px;
}

/* 顶部页面头部 */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}

.page-head-left {
  flex: 1;
  min-width: 280px;
}

.page-title {
  margin: 0 0 6px;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--ink);
}

.head-sub {
  margin: 0;
  font-size: 14px;
  color: var(--ink-secondary);
  line-height: 1.5;
}

/* 登录用户位次卡片 */
.head-rank-card {
  padding: 12px 18px;
  border: 1px solid var(--line);
  background: var(--surface);
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}

.my-rank-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.my-avatar-wrap {
  width: 40px;
  height: 40px;
  border: 1px solid var(--line);
  background: #ffffff;
  overflow: hidden;
  display: block;
  flex-shrink: 0;
}

.my-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.my-avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 16px;
  color: var(--ink-secondary);
  background: #f0f0f0;
}

.my-info-text {
  display: flex;
  flex-direction: column;
}

.my-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.my-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.my-name:hover {
  text-decoration: underline;
}

.badge-self {
  font-size: 11px;
  padding: 1px 6px;
  background: var(--button);
  color: #ffffff;
}

.my-category-text {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.my-rank-stats {
  display: flex;
  align-items: center;
  gap: 18px;
  padding-left: 18px;
  border-left: 1px solid var(--line);
}

.my-stat-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.stat-lbl {
  font-size: 11px;
  color: var(--ink-tertiary);
}

.stat-num {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.stat-num.highlight {
  font-weight: 700;
  color: var(--ink);
}

.my-profile-btn {
  margin-left: 4px;
}

/* 访客提示卡片 */
.head-guest-card {
  padding: 14px 18px;
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--surface);
  border: 1px solid var(--line);
}

.guest-tip {
  font-size: 13px;
  color: var(--ink-secondary);
}

/* 综合筛选工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  margin-bottom: 20px;
  border: 1px solid var(--line);
  background: var(--surface);
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  flex: 1;
}

.search-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 220px;
}

.search-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: var(--ink-secondary);
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 34px;
  padding-left: 32px;
  font-size: 13.5px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: var(--ink);
  outline: none;
}

.search-input:focus {
  border-color: var(--ink);
}

.category-tabs {
  display: flex;
  gap: 6px;
}

.tab-btn {
  padding: 5px 14px;
  font-size: 13px;
  font-weight: 500;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.tab-btn:hover {
  color: var(--ink);
  border-color: var(--ink);
}

.tab-btn.active {
  background: var(--ink);
  border-color: var(--ink);
  color: #ffffff;
  font-weight: 600;
}

.list-total-count {
  font-size: 13px;
}

/* 表格容器 */
.data-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  background: var(--surface);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.data-table th,
.data-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.data-table thead th {
  background: var(--bg);
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 600;
}

.rank-row {
  transition: background 0.12s ease;
}

.rank-row:hover td {
  background: var(--bg);
}

.rank-row.is-self td {
  background: #f0fdf4;
}

.rank-row.is-self:hover td {
  background: #e2fbe8;
}

/* 名次列 */
.col-rank {
  width: 70px;
  text-align: center;
}

.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  height: 22px;
  padding: 0 6px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

/* 前三名使用克制的单色灰度强调，不使用花哨表情 */
.rank-1 {
  background: var(--button);
  color: #ffffff;
}

.rank-2 {
  background: #d4d4d4;
  color: var(--ink);
}

.rank-3 {
  background: #e8e8e8;
  color: var(--ink-secondary);
}

.rank-num {
  color: var(--ink-tertiary);
  font-weight: 600;
  font-size: 13px;
}

/* 用户列 */
.col-user {
  min-width: 240px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-avatar-wrap {
  width: 36px;
  height: 36px;
  border: 1px solid var(--line);
  background: #ffffff;
  overflow: hidden;
  display: block;
  flex-shrink: 0;
  transition: border-color 0.15s ease;
}

.user-cell:hover .user-avatar-wrap {
  border-color: var(--ink);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 15px;
  color: var(--ink-secondary);
  background: #f0f0f0;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.user-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.user-name:hover {
  text-decoration: underline;
}

.role-admin {
  background: #fee2e2;
  color: #991b1b;
}

.role-teacher {
  background: #e0e7ff;
  color: #3730a3;
}

.user-bio {
  font-size: 12px;
  color: var(--ink-tertiary);
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 数据指标列 */
.metric-strong {
  font-weight: 700;
  color: var(--ink);
}

.unit {
  font-size: 12px;
  margin-left: 2px;
}

/* 通过率进度条 */
.col-rate {
  min-width: 120px;
}

.rate-container {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.rate-text {
  font-size: 13px;
  font-weight: 600;
}

.rate-track {
  width: 80px;
  height: 4px;
  background: #e2e8f0;
  overflow: hidden;
}

.rate-bar {
  height: 100%;
  background: var(--ink);
}

/* 时间与操作 */
.col-time {
  font-size: 12px;
}

.col-action {
  width: 90px;
  text-align: right;
}

.action-btn {
  font-size: 12px;
  padding: 4px 10px;
}

/* 加载与空态 */
.loading-box,
.empty-box {
  padding: 48px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  background: var(--surface);
  border: 1px solid var(--line);
}

.loading-spin {
  width: 20px;
  height: 20px;
  border: 2px solid var(--line);
  border-top-color: var(--ink);
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.empty-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}

.empty-desc {
  margin: 0;
  font-size: 13px;
}

.submit-error {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 12px;
}

@media (max-width: 860px) {
  .page-head {
    flex-direction: column;
  }
  .head-rank-card {
    width: 100%;
  }
  .my-rank-stats {
    border-left: none;
    padding-left: 0;
    width: 100%;
    justify-content: space-between;
  }
}
</style>
