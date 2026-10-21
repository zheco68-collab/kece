<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { rankApi, type RankPageData, type RankUser } from '@/api/rank'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

// 选中的题型分类
type CategoryKey = 'ALGO' | 'SQL' | 'OBJECTIVE'
const currentCategory = ref<CategoryKey>('ALGO')

const categories = [
  {
    key: 'ALGO' as CategoryKey,
    name: '算法编程榜',
    subTitle: 'C++ / Java / Python / ArkTS 沙箱',
    metricLabel: '通过题数',
    metricUnit: '题',
    icon: '⚡',
  },
  {
    key: 'SQL' as CategoryKey,
    name: 'SQL 数据库榜',
    subTitle: 'MySQL 8.0 临时独立对拍库',
    metricLabel: '通过 SQL',
    metricUnit: '题',
    icon: '🗄️',
  },
  {
    key: 'OBJECTIVE' as CategoryKey,
    name: '客观题积分榜',
    subTitle: '理论基础 / 单选 / 多选 / 判断',
    metricLabel: '答对题数',
    metricUnit: '题',
    icon: '📝',
  },
]

const currentCategoryMeta = computed(() => {
  return categories.find((c) => c.key === currentCategory.value) || categories[0]
})

// 搜索关键字
const searchKeyword = ref('')
const searchInput = ref('')

// 分页与数据
const page = ref(1)
const size = ref(50)
const loading = ref(false)
const errorMsg = ref('')
const data = ref<RankPageData | null>(null)

// 冠亚季军（前 3 名）
const topThree = computed<RankUser[]>(() => {
  if (!data.value || !data.value.list) return []
  return data.value.list.slice(0, 3)
})

// 表格展示数据（如果前三有专门领奖台，表格从第 4 名开始或者完整展示。全量展示配合高亮更直观便于对齐）
const rankingList = computed<RankUser[]>(() => {
  return data.value?.list || []
})

// 用户主页跳转
function goToProfile(userId: number) {
  router.push(`/profile/${userId}`)
}

// 获取头像首字母作为直角占位
function getInitial(name: string): string {
  if (!name) return '?'
  return name.trim().charAt(0).toUpperCase()
}

// 格式化时间
function formatTime(isoStr: string | null): string {
  if (!isoStr) return '--'
  const d = new Date(isoStr)
  if (Number.isNaN(d.getTime())) return isoStr
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const date = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${date} ${h}:${min}`
}

// 格式化角色标签
function getRoleBadge(roles: string | null) {
  if (!roles) return null
  if (roles.includes('ADMIN')) return { label: '管理员', class: 'role-admin' }
  if (roles.includes('TEACHER')) return { label: '教师', class: 'role-teacher' }
  return null
}

// 切换分类
function switchCategory(key: CategoryKey) {
  if (currentCategory.value === key) return
  currentCategory.value = key
  page.value = 1
  searchKeyword.value = ''
  searchInput.value = ''
  fetchRankings()
}

// 执行搜索
function onSearch() {
  page.value = 1
  searchKeyword.value = searchInput.value.trim()
  fetchRankings()
}

// 重置搜索
function onClearSearch() {
  searchInput.value = ''
  searchKeyword.value = ''
  page.value = 1
  fetchRankings()
}

// 加载榜单数据
async function fetchRankings() {
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await rankApi.getRankings({
      type: currentCategory.value,
      page: page.value,
      size: size.value,
      keyword: searchKeyword.value || undefined,
    })
    data.value = res
  } catch (err: any) {
    errorMsg.value = err?.message || '获取排行榜数据失败，请重试'
  } finally {
    loading.value = false
  }
}

// 翻页
function changePage(newPage: number) {
  if (newPage < 1) return
  page.value = newPage
  fetchRankings()
  window.scrollTo({ top: 320, behavior: 'smooth' })
}

onMounted(() => {
  fetchRankings()
})
</script>

<template>
  <div class="rankings-view">
    <div class="container">
      <!-- 页面头部 -->
      <section class="rank-hero">
        <div class="hero-badge mono">// GLOBAL LEADERBOARDS &amp; MATRIX //</div>
        <div class="hero-header-row">
          <div>
            <h1 class="hero-title">全域天梯排名</h1>
            <p class="hero-desc">
              多维硬核实战评测排盘：算法沙箱、SQL真实数据库对拍与客观理论知识沉淀。点击选手直达专属主页。
            </p>
          </div>
          <div class="hero-meta mono">
            <span class="meta-tag">3 级分类分榜</span>
            <span class="meta-tag">全真实作答加权</span>
            <span class="meta-tag">实时天梯</span>
          </div>
        </div>

        <!-- 题型切换直角 Tab 组 -->
        <div class="category-tabs">
          <button
            v-for="cat in categories"
            :key="cat.key"
            class="tab-btn"
            :class="{ active: currentCategory === cat.key }"
            @click="switchCategory(cat.key)"
          >
            <span class="tab-icon">{{ cat.icon }}</span>
            <span class="tab-text">
              <span class="tab-name">{{ cat.name }}</span>
              <span class="tab-sub mono">{{ cat.subTitle }}</span>
            </span>
          </button>
        </div>
      </section>

      <!-- 当前登录用户专属位次横幅 -->
      <section class="my-rank-strip">
        <div v-if="userStore.isLoggedIn" class="my-rank-content">
          <div class="my-user-info" @click="goToProfile(userStore.userInfo!.id)">
            <div class="my-avatar-box">
              <img
                v-if="userStore.userInfo?.avatarUrl"
                :src="userStore.userInfo.avatarUrl"
                class="avatar-img"
                alt="My Avatar"
              />
              <div v-else class="avatar-placeholder mono">
                {{ getInitial(userStore.userInfo?.username || 'U') }}
              </div>
            </div>
            <div class="my-user-meta">
              <div class="my-user-name">
                {{ userStore.userInfo?.username }}
                <span class="my-badge">当前账号</span>
              </div>
              <div class="my-user-sub mono">
                {{ currentCategoryMeta.name }} · 我的天梯实时档位
              </div>
            </div>
          </div>

          <div class="my-stats-row">
            <template v-if="data?.myRank">
              <div class="stat-cell">
                <span class="stat-label">天梯名次</span>
                <span class="stat-value mono rank-highlight">#{{ data.myRank.rank }}</span>
              </div>
              <div class="stat-cell">
                <span class="stat-label">{{ currentCategoryMeta.metricLabel }}</span>
                <span class="stat-value mono">{{ data.myRank.solvedCount }} {{ currentCategoryMeta.metricUnit }}</span>
              </div>
              <div v-if="currentCategory === 'OBJECTIVE'" class="stat-cell">
                <span class="stat-label">累计总得分</span>
                <span class="stat-value mono score-highlight">{{ data.myRank.totalScore ?? 0 }} pts</span>
              </div>
              <div class="stat-cell">
                <span class="stat-label">总作答/提交</span>
                <span class="stat-value mono">{{ data.myRank.totalSubmissions }} 次</span>
              </div>
              <div class="stat-cell">
                <span class="stat-label">AC 通过率</span>
                <span class="stat-value mono">{{ data.myRank.acceptRate != null ? data.myRank.acceptRate + '%' : '--' }}</span>
              </div>
            </template>
            <div v-else class="unranked-tip">
              <span class="unranked-icon">🎯</span>
              <span>暂未在该榜单上榜，完成 1 次有效答题评测即可荣登天梯！</span>
            </div>
            <RouterLink :to="`/profile/${userStore.userInfo!.id}`" class="my-profile-btn">
              进入我的主页 →
            </RouterLink>
          </div>
        </div>

        <div v-else class="guest-strip">
          <div class="guest-text">
            <span class="guest-icon">🔒</span>
            <span>登录 KECE 账号可实时定位您在算法、SQL 与客观题中的全域天梯位次并参与上榜打擂。</span>
          </div>
          <RouterLink to="/login" class="guest-login-btn">
            立即登录查看排名
          </RouterLink>
        </div>
      </section>

      <!-- 前三名领奖台卡片 (Podium) -->
      <section v-if="!loading && topThree.length > 0 && page === 1 && !searchKeyword" class="podium-section">
        <div class="section-badge mono">// TOP 3 PODIUM //</div>
        <div class="podium-grid">
          <!-- 亚军 (Rank 2) - 居左展示 -->
          <div
            v-if="topThree[1]"
            class="podium-card rank-silver"
            @click="goToProfile(topThree[1].userId)"
          >
            <div class="podium-rank-tag mono">
              <span class="trophy-icon">🥈</span> 02 · 亚军
            </div>
            <div class="podium-avatar-box">
              <img
                v-if="topThree[1].avatarUrl"
                :src="topThree[1].avatarUrl"
                class="avatar-img"
                alt="Silver Avatar"
              />
              <div v-else class="avatar-placeholder mono">
                {{ getInitial(topThree[1].username) }}
              </div>
            </div>
            <div class="podium-name-row">
              <span class="podium-name">{{ topThree[1].username }}</span>
              <span
                v-if="getRoleBadge(topThree[1].roles)"
                :class="['role-badge', getRoleBadge(topThree[1].roles)!.class]"
              >
                {{ getRoleBadge(topThree[1].roles)!.label }}
              </span>
            </div>
            <div class="podium-bio">{{ topThree[1].bio || '这位极客很酷，暂未设置签名' }}</div>
            <div class="podium-metrics mono">
              <div class="metric-item">
                <span class="metric-val">{{ topThree[1].solvedCount }}</span>
                <span class="metric-lbl">{{ currentCategoryMeta.metricLabel }}</span>
              </div>
              <div v-if="currentCategory === 'OBJECTIVE'" class="metric-item">
                <span class="metric-val">{{ topThree[1].totalScore ?? 0 }}</span>
                <span class="metric-lbl">总分</span>
              </div>
              <div class="metric-item">
                <span class="metric-val">{{ topThree[1].acceptRate != null ? topThree[1].acceptRate + '%' : '--' }}</span>
                <span class="metric-lbl">通过率</span>
              </div>
              <div class="metric-item">
                <span class="metric-val">{{ topThree[1].totalSubmissions }}</span>
                <span class="metric-lbl">提交量</span>
              </div>
            </div>
            <div class="podium-hover-hint">查看选手主页 →</div>
          </div>

          <!-- 冠军 (Rank 1) - 居中突出展示 -->
          <div
            v-if="topThree[0]"
            class="podium-card rank-gold"
            @click="goToProfile(topThree[0].userId)"
          >
            <div class="podium-rank-tag mono">
              <span class="trophy-icon">👑</span> 01 · 冠军
            </div>
            <div class="podium-avatar-box large">
              <img
                v-if="topThree[0].avatarUrl"
                :src="topThree[0].avatarUrl"
                class="avatar-img"
                alt="Gold Avatar"
              />
              <div v-else class="avatar-placeholder mono">
                {{ getInitial(topThree[0].username) }}
              </div>
            </div>
            <div class="podium-name-row">
              <span class="podium-name">{{ topThree[0].username }}</span>
              <span
                v-if="getRoleBadge(topThree[0].roles)"
                :class="['role-badge', getRoleBadge(topThree[0].roles)!.class]"
              >
                {{ getRoleBadge(topThree[0].roles)!.label }}
              </span>
            </div>
            <div class="podium-bio">{{ topThree[0].bio || '这位极客很酷，暂未设置签名' }}</div>
            <div class="podium-metrics mono">
              <div class="metric-item highlight">
                <span class="metric-val">{{ topThree[0].solvedCount }}</span>
                <span class="metric-lbl">{{ currentCategoryMeta.metricLabel }}</span>
              </div>
              <div v-if="currentCategory === 'OBJECTIVE'" class="metric-item highlight">
                <span class="metric-val">{{ topThree[0].totalScore ?? 0 }}</span>
                <span class="metric-lbl">总分</span>
              </div>
              <div class="metric-item">
                <span class="metric-val">{{ topThree[0].acceptRate != null ? topThree[0].acceptRate + '%' : '--' }}</span>
                <span class="metric-lbl">通过率</span>
              </div>
              <div class="metric-item">
                <span class="metric-val">{{ topThree[0].totalSubmissions }}</span>
                <span class="metric-lbl">提交量</span>
              </div>
            </div>
            <div class="podium-hover-hint">查看选手主页 →</div>
          </div>

          <!-- 季军 (Rank 3) - 居右展示 -->
          <div
            v-if="topThree[2]"
            class="podium-card rank-bronze"
            @click="goToProfile(topThree[2].userId)"
          >
            <div class="podium-rank-tag mono">
              <span class="trophy-icon">🥉</span> 03 · 季军
            </div>
            <div class="podium-avatar-box">
              <img
                v-if="topThree[2].avatarUrl"
                :src="topThree[2].avatarUrl"
                class="avatar-img"
                alt="Bronze Avatar"
              />
              <div v-else class="avatar-placeholder mono">
                {{ getInitial(topThree[2].username) }}
              </div>
            </div>
            <div class="podium-name-row">
              <span class="podium-name">{{ topThree[2].username }}</span>
              <span
                v-if="getRoleBadge(topThree[2].roles)"
                :class="['role-badge', getRoleBadge(topThree[2].roles)!.class]"
              >
                {{ getRoleBadge(topThree[2].roles)!.label }}
              </span>
            </div>
            <div class="podium-bio">{{ topThree[2].bio || '这位极客很酷，暂未设置签名' }}</div>
            <div class="podium-metrics mono">
              <div class="metric-item">
                <span class="metric-val">{{ topThree[2].solvedCount }}</span>
                <span class="metric-lbl">{{ currentCategoryMeta.metricLabel }}</span>
              </div>
              <div v-if="currentCategory === 'OBJECTIVE'" class="metric-item">
                <span class="metric-val">{{ topThree[2].totalScore ?? 0 }}</span>
                <span class="metric-lbl">总分</span>
              </div>
              <div class="metric-item">
                <span class="metric-val">{{ topThree[2].acceptRate != null ? topThree[2].acceptRate + '%' : '--' }}</span>
                <span class="metric-lbl">通过率</span>
              </div>
              <div class="metric-item">
                <span class="metric-val">{{ topThree[2].totalSubmissions }}</span>
                <span class="metric-lbl">提交量</span>
              </div>
            </div>
            <div class="podium-hover-hint">查看选手主页 →</div>
          </div>
        </div>
      </section>

      <!-- 搜索与表格筛选栏 -->
      <section class="table-control-row">
        <div class="control-left">
          <span class="list-title mono">
            // {{ currentCategoryMeta.name }}
            <span class="list-count" v-if="data">（共收录 {{ data.total }} 位极客选手）</span>
          </span>
        </div>
        <div class="control-right">
          <div class="search-box">
            <span class="search-icon">🔍</span>
            <input
              v-model="searchInput"
              class="search-input"
              type="text"
              placeholder="输入用户名快速检索..."
              @keyup.enter="onSearch"
            />
            <button v-if="searchInput" class="search-clear-btn" @click="onClearSearch">✕</button>
            <button class="search-submit-btn" @click="onSearch">检索</button>
          </div>
        </div>
      </section>

      <!-- 排行榜主数据表格 -->
      <section class="table-section">
        <div v-if="loading" class="loading-box mono">
          <div class="spinner"></div>
          <span>正在计算并同步全域天梯位次...</span>
        </div>

        <div v-else-if="errorMsg" class="error-box">
          <span class="error-icon">⚠️</span>
          <span>{{ errorMsg }}</span>
          <button class="retry-btn" @click="fetchRankings">重试</button>
        </div>

        <div v-else-if="rankingList.length === 0" class="empty-box">
          <div class="empty-icon">📭</div>
          <div class="empty-text">当前榜单暂无匹配的选手记录</div>
          <div class="empty-sub">
            {{ searchKeyword ? '请尝试更换关键词搜索' : '快去作答题目，成为该分类榜单的开辟者！' }}
          </div>
        </div>

        <div v-else class="table-container">
          <table class="rank-table">
            <thead>
              <tr>
                <th class="col-rank mono">名次</th>
                <th class="col-user">选手</th>
                <th class="col-solved mono">{{ currentCategoryMeta.metricLabel }}</th>
                <th v-if="currentCategory === 'OBJECTIVE'" class="col-score mono">累计总分</th>
                <th class="col-submissions mono">总作答次数</th>
                <th class="col-rate mono">AC 通过率</th>
                <th class="col-time mono">最后 AC 时间</th>
                <th class="col-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in rankingList"
                :key="item.userId"
                class="rank-row"
                :class="{
                  'is-top-1': item.rank === 1,
                  'is-top-2': item.rank === 2,
                  'is-top-3': item.rank === 3,
                  'is-self': item.userId === userStore.userInfo?.id,
                }"
              >
                <!-- 名次 -->
                <td class="col-rank mono">
                  <div class="rank-badge-box">
                    <span v-if="item.rank === 1" class="rank-medal gold">01</span>
                    <span v-else-if="item.rank === 2" class="rank-medal silver">02</span>
                    <span v-else-if="item.rank === 3" class="rank-medal bronze">03</span>
                    <span v-else class="rank-num">
                      #{{ String(item.rank).padStart(2, '0') }}
                    </span>
                  </div>
                </td>

                <!-- 用户头像与信息 -->
                <td class="col-user">
                  <div class="user-cell" @click="goToProfile(item.userId)">
                    <div class="table-avatar-box">
                      <img
                        v-if="item.avatarUrl"
                        :src="item.avatarUrl"
                        class="avatar-img"
                        :alt="item.username"
                      />
                      <div v-else class="avatar-placeholder mono">
                        {{ getInitial(item.username) }}
                      </div>
                    </div>
                    <div class="user-text-info">
                      <div class="user-name-line">
                        <span class="user-name">{{ item.username }}</span>
                        <span
                          v-if="getRoleBadge(item.roles)"
                          :class="['role-badge', getRoleBadge(item.roles)!.class]"
                        >
                          {{ getRoleBadge(item.roles)!.label }}
                        </span>
                        <span v-if="item.userId === userStore.userInfo?.id" class="self-tag mono">
                          YOU
                        </span>
                      </div>
                      <div class="user-bio" :title="item.bio || ''">
                        {{ item.bio || '无个性签名' }}
                      </div>
                    </div>
                  </div>
                </td>

                <!-- 通过题目数 -->
                <td class="col-solved mono">
                  <span class="solved-number">{{ item.solvedCount }}</span>
                  <span class="unit-text">{{ currentCategoryMeta.metricUnit }}</span>
                </td>

                <!-- 累计总分 (仅客观题展示) -->
                <td v-if="currentCategory === 'OBJECTIVE'" class="col-score mono">
                  <span class="score-number">{{ item.totalScore ?? 0 }}</span>
                  <span class="unit-text">pts</span>
                </td>

                <!-- 总作答次数 -->
                <td class="col-submissions mono">
                  <span class="sub-number">{{ item.totalSubmissions }}</span>
                  <span class="unit-text">次</span>
                </td>

                <!-- 通过率 -->
                <td class="col-rate mono">
                  <div class="rate-cell">
                    <span class="rate-text">{{ item.acceptRate != null ? item.acceptRate + '%' : '--' }}</span>
                    <div class="rate-track">
                      <div
                        class="rate-fill"
                        :style="{ width: `${item.acceptRate || 0}%` }"
                      ></div>
                    </div>
                  </div>
                </td>

                <!-- 最近活跃时间 -->
                <td class="col-time mono">
                  <span class="time-text">{{ formatTime(item.lastAcceptedAt) }}</span>
                </td>

                <!-- 操作：进入主页 -->
                <td class="col-action">
                  <button
                    class="profile-jump-btn"
                    title="查看该用户主页"
                    @click="goToProfile(item.userId)"
                  >
                    主页 ↗
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页栏 -->
        <div v-if="data && data.total > size" class="pagination-bar mono">
          <button
            class="page-btn"
            :disabled="page <= 1"
            @click="changePage(page - 1)"
          >
            ← 上一页
          </button>
          <span class="page-indicator">
            第 {{ page }} / {{ Math.ceil(data.total / size) }} 页
          </span>
          <button
            class="page-btn"
            :disabled="page * size >= data.total"
            @click="changePage(page + 1)"
          >
            下一页 →
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 严格执行全站直角原则：border-radius 严格为 0 */
* {
  border-radius: 0 !important;
}

.rankings-view {
  min-height: calc(100vh - 120px);
  background: var(--bg);
  padding: 40px 0 80px;
  color: var(--ink);
}

.container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 24px;
}

/* 头部 Hero 区域 */
.rank-hero {
  border: 1px solid var(--line);
  background: var(--surface-white);
  padding: 32px 36px 0;
  margin-bottom: 24px;
}

.hero-badge {
  font-size: 11px;
  letter-spacing: 0.14em;
  color: var(--ink-tertiary);
  margin-bottom: 12px;
}

.hero-header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  margin-bottom: 32px;
}

.hero-title {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 0 0 8px;
  color: var(--ink);
}

.hero-desc {
  font-size: 14px;
  color: var(--ink-secondary);
  max-width: 760px;
  margin: 0;
  line-height: 1.6;
}

.hero-meta {
  display: flex;
  gap: 8px;
}

.meta-tag {
  font-size: 11px;
  padding: 4px 10px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--ink-secondary);
}

/* 题型分类直角 Tab */
.category-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0;
  border-top: 1px solid var(--line);
  margin: 0 -36px;
}

.tab-btn {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 28px;
  background: var(--surface-white);
  border: none;
  border-right: 1px solid var(--line);
  border-bottom: 3px solid transparent;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s ease;
}

.tab-btn:last-child {
  border-right: none;
}

.tab-btn:hover {
  background: var(--surface);
}

.tab-btn.active {
  background: var(--surface-white);
  border-bottom-color: var(--ink);
}

.tab-icon {
  font-size: 24px;
  flex-shrink: 0;
}

.tab-text {
  display: flex;
  flex-direction: column;
}

.tab-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 2px;
}

.tab-btn.active .tab-name {
  color: var(--ink);
}

.tab-sub {
  font-size: 11px;
  color: var(--ink-tertiary);
}

/* 当前用户状态横幅 */
.my-rank-strip {
  border: 1px solid var(--line);
  background: #fafafa;
  margin-bottom: 32px;
  padding: 16px 24px;
}

.my-rank-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 20px;
}

.my-user-info {
  display: flex;
  align-items: center;
  gap: 14px;
  cursor: pointer;
}

.my-avatar-box {
  width: 44px;
  height: 44px;
  border: 1px solid var(--line-strong);
  overflow: hidden;
  background: #ffffff;
  flex-shrink: 0;
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
  font-weight: 800;
  font-size: 18px;
  color: var(--ink-secondary);
  background: #ececec;
}

.my-user-meta {
  display: flex;
  flex-direction: column;
}

.my-user-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
}

.my-badge {
  font-size: 10px;
  padding: 1px 6px;
  background: var(--ink);
  color: #ffffff;
  font-weight: 600;
}

.my-user-sub {
  font-size: 12px;
  color: var(--ink-tertiary);
}

.my-stats-row {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.stat-label {
  font-size: 11px;
  color: var(--ink-tertiary);
  margin-bottom: 2px;
}

.stat-value {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.rank-highlight {
  color: #d97706;
  font-size: 20px;
}

.score-highlight {
  color: #2563eb;
}

.unranked-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-secondary);
  background: var(--surface);
  padding: 8px 14px;
  border: 1px dashed var(--line);
}

.my-profile-btn {
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid var(--line-strong);
  background: var(--ink);
  color: #ffffff;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.my-profile-btn:hover {
  background: #333333;
}

.guest-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  font-size: 13px;
  color: var(--ink-secondary);
}

.guest-text {
  display: flex;
  align-items: center;
  gap: 10px;
}

.guest-login-btn {
  padding: 6px 16px;
  border: 1px solid var(--line-strong);
  background: var(--ink);
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
}

/* 领奖台区域 (Top 3) */
.podium-section {
  margin-bottom: 36px;
}

.section-badge {
  font-size: 11px;
  color: var(--ink-tertiary);
  margin-bottom: 12px;
  letter-spacing: 0.1em;
}

.podium-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.podium-card {
  border: 1px solid var(--line);
  background: var(--surface-white);
  padding: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  cursor: pointer;
  position: relative;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.podium-card:hover {
  border-color: var(--ink);
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.05);
}

/* 冠军卡片特殊样式 */
.podium-card.rank-gold {
  border-top: 4px solid #f59e0b;
  background: linear-gradient(180deg, #fffbeb 0%, #ffffff 40%);
}

.podium-card.rank-silver {
  border-top: 4px solid #94a3b8;
  background: linear-gradient(180deg, #f8fafc 0%, #ffffff 40%);
}

.podium-card.rank-bronze {
  border-top: 4px solid #d97706;
  background: linear-gradient(180deg, #fff7ed 0%, #ffffff 40%);
}

.podium-rank-tag {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px;
  background: rgba(0, 0, 0, 0.04);
  border: 1px solid var(--line);
}

.rank-gold .podium-rank-tag {
  color: #b45309;
  border-color: #fde68a;
  background: #fef3c7;
}

.rank-silver .podium-rank-tag {
  color: #475569;
  border-color: #e2e8f0;
  background: #f1f5f9;
}

.rank-bronze .podium-rank-tag {
  color: #9a3412;
  border-color: #fed7aa;
  background: #ffedd5;
}

.podium-avatar-box {
  width: 68px;
  height: 68px;
  border: 2px solid var(--line-strong);
  overflow: hidden;
  margin-bottom: 14px;
  background: #ffffff;
}

.podium-avatar-box.large {
  width: 80px;
  height: 80px;
  border: 2px solid #b45309;
}

.podium-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.podium-name {
  font-size: 18px;
  font-weight: 800;
  color: var(--ink);
}

.podium-card:hover .podium-name {
  text-decoration: underline;
}

.role-badge {
  font-size: 10px;
  padding: 1px 5px;
  font-weight: 600;
  border: 1px solid transparent;
}

.role-admin {
  background: #fee2e2;
  color: #991b1b;
  border-color: #fca5a5;
}

.role-teacher {
  background: #e0e7ff;
  color: #3730a3;
  border-color: #a5b4fc;
}

.podium-bio {
  font-size: 12px;
  color: var(--ink-secondary);
  line-height: 1.4;
  height: 34px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  margin-bottom: 16px;
}

.podium-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  width: 100%;
  border-top: 1px solid var(--line);
  padding-top: 12px;
  margin-bottom: 12px;
  gap: 8px;
}

.podium-card .podium-metrics:has(.metric-item:nth-child(4)) {
  grid-template-columns: repeat(4, 1fr);
}

.metric-item {
  display: flex;
  flex-direction: column;
}

.metric-val {
  font-size: 15px;
  font-weight: 800;
  color: var(--ink);
}

.metric-item.highlight .metric-val {
  font-size: 18px;
  color: #b45309;
}

.metric-lbl {
  font-size: 10px;
  color: var(--ink-tertiary);
  margin-top: 2px;
}

.podium-hover-hint {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-tertiary);
  margin-top: 4px;
  transition: color 0.15s;
}

.podium-card:hover .podium-hover-hint {
  color: var(--ink);
}

/* 表格筛选栏 */
.table-control-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 16px;
}

.list-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

.list-count {
  font-weight: normal;
  color: var(--ink-tertiary);
}

.search-box {
  display: flex;
  align-items: center;
  border: 1px solid var(--line);
  background: var(--surface-white);
  padding: 0 4px 0 10px;
  height: 36px;
}

.search-icon {
  font-size: 13px;
  margin-right: 6px;
  color: var(--ink-tertiary);
}

.search-input {
  border: none;
  outline: none;
  background: transparent;
  font-size: 13px;
  color: var(--ink);
  width: 200px;
}

.search-clear-btn {
  font-size: 11px;
  color: var(--ink-tertiary);
  padding: 2px 6px;
  margin-right: 4px;
  cursor: pointer;
}

.search-submit-btn {
  padding: 4px 12px;
  background: var(--ink);
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  border: none;
  cursor: pointer;
}

/* 主数据表格 */
.table-section {
  border: 1px solid var(--line);
  background: var(--surface-white);
}

.table-container {
  overflow-x: auto;
}

.rank-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}

.rank-table th {
  background: #f8fafc;
  border-bottom: 1px solid var(--line);
  padding: 14px 18px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary);
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.rank-table td {
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
  font-size: 14px;
  vertical-align: middle;
}

.rank-row {
  transition: background 0.12s ease;
}

.rank-row:hover {
  background: #fbfbfb;
}

.rank-row.is-self {
  background: #f0fdf4;
}

.rank-row.is-self:hover {
  background: #dcfce7;
}

/* 名次列 */
.col-rank {
  width: 80px;
  text-align: center;
}

.rank-badge-box {
  display: flex;
  align-items: center;
  justify-content: center;
}

.rank-medal {
  display: inline-block;
  padding: 2px 8px;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.rank-medal.gold {
  background: #fef3c7;
  color: #b45309;
  border: 1px solid #fde68a;
}

.rank-medal.silver {
  background: #f1f5f9;
  color: #475569;
  border: 1px solid #cbd5e1;
}

.rank-medal.bronze {
  background: #ffedd5;
  color: #c2410c;
  border: 1px solid #fed7aa;
}

.rank-num {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-secondary);
}

/* 用户列 */
.col-user {
  min-width: 240px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.table-avatar-box {
  width: 40px;
  height: 40px;
  border: 1px solid var(--line);
  overflow: hidden;
  background: #ffffff;
  flex-shrink: 0;
  transition: border-color 0.15s;
}

.user-cell:hover .table-avatar-box {
  border-color: var(--ink);
}

.user-text-info {
  display: flex;
  flex-direction: column;
}

.user-name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.user-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

.user-cell:hover .user-name {
  text-decoration: underline;
}

.self-tag {
  font-size: 9px;
  font-weight: 800;
  padding: 1px 4px;
  background: #16a34a;
  color: #ffffff;
}

.user-bio {
  font-size: 11px;
  color: var(--ink-tertiary);
  max-width: 280px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 数据指标列 */
.col-solved,
.col-score,
.col-submissions {
  white-space: nowrap;
}

.solved-number {
  font-size: 16px;
  font-weight: 800;
  color: var(--ink);
}

.score-number {
  font-size: 16px;
  font-weight: 800;
  color: #2563eb;
}

.sub-number {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-secondary);
}

.unit-text {
  font-size: 11px;
  color: var(--ink-tertiary);
  margin-left: 3px;
}

/* 通过率列 */
.col-rate {
  min-width: 130px;
}

.rate-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rate-text {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}

.rate-track {
  width: 90px;
  height: 4px;
  background: #e2e8f0;
  overflow: hidden;
}

.rate-fill {
  height: 100%;
  background: var(--ink);
}

/* 时间与操作 */
.col-time {
  white-space: nowrap;
  font-size: 12px;
  color: var(--ink-tertiary);
}

.col-action {
  width: 90px;
  text-align: right;
  white-space: nowrap;
}

.profile-jump-btn {
  font-size: 12px;
  font-weight: 600;
  padding: 5px 12px;
  border: 1px solid var(--line);
  background: var(--surface-white);
  color: var(--ink);
  cursor: pointer;
  transition: all 0.15s ease;
}

.profile-jump-btn:hover {
  background: var(--ink);
  color: #ffffff;
  border-color: var(--ink);
}

/* 翻页栏 */
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 20px;
  border-top: 1px solid var(--line);
  background: #fafafa;
}

.page-btn {
  padding: 6px 16px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid var(--line);
  background: var(--surface-white);
  color: var(--ink);
  cursor: pointer;
  transition: all 0.15s;
}

.page-btn:hover:not(:disabled) {
  border-color: var(--ink);
  background: var(--surface);
}

.page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.page-indicator {
  font-size: 13px;
  color: var(--ink-secondary);
}

/* 加载、空态与报错 */
.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 80px 24px;
  color: var(--ink-tertiary);
  font-size: 13px;
}

.spinner {
  width: 24px;
  height: 24px;
  border: 2px solid var(--line);
  border-top-color: var(--ink);
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.error-box,
.empty-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px 24px;
  text-align: center;
}

.empty-icon {
  font-size: 40px;
  margin-bottom: 12px;
}

.empty-text {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 6px;
}

.empty-sub {
  font-size: 13px;
  color: var(--ink-tertiary);
}

.retry-btn {
  margin-top: 12px;
  padding: 6px 16px;
  background: var(--ink);
  color: #ffffff;
  font-size: 12px;
  cursor: pointer;
}

/* 响应式调整 */
@media (max-width: 900px) {
  .hero-header-row {
    flex-direction: column;
    align-items: flex-start;
  }
  .category-tabs {
    grid-template-columns: 1fr;
    margin: 0 -24px;
  }
  .tab-btn {
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
  .podium-grid {
    grid-template-columns: 1fr;
  }
  .my-rank-content {
    flex-direction: column;
    align-items: flex-start;
  }
  .my-stats-row {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
