<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { rankApi, type RankUser } from '@/api/rank'
import Pagination from '@/components/Pagination.vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const categories = [{ key: 'ALGO', name: '算法编程' }, { key: 'SQL', name: 'SQL 数据库' }, { key: 'OBJECTIVE', name: '客观理论' }]
const category = computed(() => categories.some(c => c.key === route.query.type) ? String(route.query.type) : 'ALGO')
const page = computed(() => {
  const n = Number(route.query.page)
  return Number.isSafeInteger(n) && n > 0 && n <= 1000000 ? n : 1
})
const query = computed(() => typeof route.query.keyword === 'string' ? route.query.keyword.trim() : '')
const keyword = ref(query.value)
const objective = computed(() => category.value === 'OBJECTIVE')
const metric = computed(() => objective.value ? '答对题数' : '通过题数')
const size = 20
const rows = ref<RankUser[]>([])
const total = ref(0)
const mine = ref<RankUser | null>(null)
const loading = ref(true)
const error = ref('')
const rules = ref(false)
const brokenAvatars = ref(new Set<number>())
let sequence = 0
let timer: ReturnType<typeof setTimeout> | undefined

function navigate(type = category.value, targetPage = 1) {
  clearTimeout(timer)
  void router.replace({ query: { ...route.query, type, page: String(targetPage), keyword: keyword.value.trim() || undefined } })
}
function search() { navigate() }
watch(keyword, () => {
  clearTimeout(timer)
  if (keyword.value.trim() !== query.value) {
    ++sequence
    loading.value = true
    timer = setTimeout(search, 300)
  } else if (loading.value) void load()
})
async function load() {
  const request = ++sequence
  loading.value = true
  error.value = ''
  try {
    const result = await rankApi.getRankings({ type: category.value, page: page.value, size, keyword: query.value || undefined })
    if (request !== sequence) return
    const last = Math.max(1, Math.ceil(result.total / size))
    if (page.value > last) { navigate(category.value, last); return }
    rows.value = result.list || []
    total.value = result.total
    mine.value = result.myRank
  } catch (e: unknown) {
    if (request === sequence) error.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    if (request === sequence) loading.value = false
  }
}
watch([category, page, query], () => {
  clearTimeout(timer)
  keyword.value = query.value
  void load()
}, { immediate: true })
onBeforeUnmount(() => { ++sequence; clearTimeout(timer) })
function rate(value: number | null) { return value == null ? '—' : value + '%' }
function date(value: string | null) { return value ? value.replace('T', ' ').slice(0, 16) : '尚未通过' }
</script>

<template>
  <div class="rankings">
    <header class="heading">
      <div><h1>排名</h1><p>按题型查看成绩，比较选手表现。</p></div>
      <button :aria-expanded="rules" aria-controls="rank-rules" @click="rules = !rules">排名规则</button>
    </header>
    <section v-if="rules" id="rank-rules" class="rules" aria-label="排名规则">
      <h2>成绩如何排序</h2>
      <p v-if="objective">依次比较：答对题数多、每题最高分合计高、正确提交次数多、总作答次数少、最近正确提交时间早，最后按用户 ID 排序。</p>
      <p v-else>依次比较：通过题数多、通过提交次数多、总提交次数少、最近通过时间早，最后按用户 ID 排序。</p>
      <p>题数按不同题目去重；提交通过率为通过提交次数除以总提交次数。客观题累计得分为每题最高分之和。搜索保留全站名次。</p>
      <p>榜单可能有约 2–3 分钟延迟，个人成绩与列表可能暂时不同。「最近通过」指最近一条通过提交的创建时间。</p>
    </section>
    <nav class="categories" aria-label="排名题型">
      <button v-for="cat in categories" :key="cat.key" :aria-pressed="category === cat.key" :class="{ active: category === cat.key }" @click="navigate(cat.key)">{{ cat.name }}</button>
    </nav>
    <section class="summary" aria-label="我的成绩">
      <template v-if="user.isLoggedIn">
        <strong>我的成绩 · {{ user.userInfo?.username }}</strong>
        <span v-if="loading">正在加载…</span>
        <span v-else-if="error">成绩暂时不可用</span>
        <template v-else-if="mine">
          <span>全站名次 <b>#{{ mine.rank }}</b></span>
          <span>{{ metric }} <b>{{ mine.solvedCount }}</b></span>
          <span v-if="objective">累计得分 <b>{{ mine.totalScore ?? 0 }}</b></span>
          <span>提交通过率 <b>{{ rate(mine.acceptRate) }}</b></span>
        </template>
        <span v-else>本题型暂无提交记录</span>
        <RouterLink v-if="user.userInfo" :to="'/profile/' + user.userInfo.id">我的主页 ↗</RouterLink>
      </template>
      <template v-else><span>登录后查看自己的排名与成绩</span><RouterLink to="/login">登录查看 ↗</RouterLink></template>
    </section>
    <div class="toolbar">
      <form class="search" @submit.prevent="search">
        <input v-model="keyword" type="search" aria-label="搜索用户名" placeholder="搜索用户名" />
        <button type="submit">搜索</button>
      </form>
      <span role="status">{{ loading ? '正在查询…' : error ? '暂不可用' : (query ? '匹配 ' : '共 ') + total + ' 位选手' }}</span>
    </div>
    <div v-if="loading" class="state" aria-busy="true"><div v-for="n in 6" :key="n" class="skeleton"></div><span class="loading-label">正在加载排名…</span></div>
    <div v-else-if="error" class="state"><h2>暂时无法加载排名</h2><p role="alert">{{ error }}</p><button @click="load">重试</button></div>
    <div v-else-if="!rows.length" class="state"><h2>{{ query ? '没有找到匹配的选手' : '本题型暂无提交记录' }}</h2><button v-if="query" @click="keyword = ''; search()">清空搜索</button><RouterLink v-else to="/questions">前往练习 ↗</RouterLink></div>
    <template v-else>
      <table>
        <caption class="sr-only">{{ categories.find(c => c.key === category)?.name }}排行榜</caption>
        <thead><tr><th>名次</th><th>选手</th><th class="number">{{ metric }}</th><th v-if="objective" class="number">累计得分</th><th class="number">提交次数</th><th class="number">提交通过率</th><th class="number">最近通过</th></tr></thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="row.userId" :class="{ self: row.userId === user.userInfo?.id }" :style="{ '--drop-delay': Math.min(index, 9) * 45 + 'ms' }">
            <td class="rank" :class="{ leading: row.rank <= 3 }">#{{ row.rank }}</td>
            <td class="person"><RouterLink :to="'/profile/' + row.userId" :title="row.username">
              <img v-if="row.avatarUrl && !brokenAvatars.has(row.userId)" :src="row.avatarUrl" alt="" @error="brokenAvatars.add(row.userId)" />
              <span v-else class="avatar" aria-hidden="true">{{ row.username.slice(0, 1).toUpperCase() }}</span>
              <span class="username">{{ row.username }}</span>
              <small v-if="row.userId === user.userInfo?.id">我</small>
            </RouterLink></td>
            <td class="number score" :data-label="metric">{{ row.solvedCount }}</td>
            <td v-if="objective" class="number" data-label="累计得分">{{ row.totalScore ?? 0 }}</td>
            <td class="number" data-label="提交次数">{{ row.totalSubmissions }}</td>
            <td class="number" data-label="提交通过率">{{ rate(row.acceptRate) }}</td>
            <td class="number time" data-label="最近通过"><time :title="date(row.lastAcceptedAt)" tabindex="0">{{ date(row.lastAcceptedAt) }}</time></td>
          </tr>
        </tbody>
      </table>
      <Pagination v-if="total > size" :page="page" :size="size" :total="total" :disabled="loading" @update:page="navigate(category, $event)" />
      <footer v-else><span>已显示全部 {{ total }} 位选手</span><span>第 1 / 1 页</span></footer>
    </template>
  </div>
</template>

<style scoped>
tbody tr { animation: rank-drop 520ms cubic-bezier(.22,1.15,.36,1) both; animation-delay: var(--drop-delay, 0ms); }
@keyframes rank-drop { from { opacity: 0; transform: translateY(-24px); } 75% { opacity: 1; transform: translateY(2px); } to { opacity: 1; transform: translateY(0); } }
@media (prefers-reduced-motion: reduce) { tbody tr { animation: none; } }
.rankings{max-width:1360px;margin:auto;padding:36px 32px 64px;font-size:16px;container-type:inline-size}
.heading{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:28px}
h1{font-size:32px;line-height:1.25;margin:0 0 8px}h2{font-size:20px}p{color:var(--ink-secondary);margin:0;line-height:1.7}
button,input{font:inherit;border:1px solid var(--line);border-radius:0;background:white;color:var(--ink);min-height:44px;padding:8px 16px}button{cursor:pointer}button:hover{background:var(--surface)}a{color:inherit}
button:focus-visible,a:focus-visible,input:focus-visible,time:focus-visible{outline:2px solid var(--ink);outline-offset:3px}
.categories{display:flex;gap:28px;border-bottom:1px solid var(--line);margin-bottom:24px}.categories button{border:0;border-bottom:3px solid transparent;padding:12px 8px;background:none}.categories .active{border-bottom-color:var(--ink);font-weight:700}
.summary{display:flex;align-items:center;flex-wrap:wrap;gap:16px 24px;background:var(--surface);border-left:4px solid var(--ink);padding:20px 24px;min-height:76px;margin-bottom:24px}
.summary span{color:var(--ink-secondary);font-size:14px}.summary b{font-size:20px;color:var(--ink);margin-left:8px;font-variant-numeric:tabular-nums}.summary a{margin-left:auto;white-space:nowrap}
.toolbar{display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:24px}.toolbar>span{font-size:14px;color:var(--ink-secondary)}.search{display:flex;max-width:440px;width:100%}.search input{min-width:0;flex:1;border-right:0}
table{width:100%;border-collapse:collapse;table-layout:fixed}th{font-size:14px;font-weight:600;background:var(--surface);height:44px}th,td{text-align:left;padding:12px;border-bottom:1px solid var(--line)}td{height:64px}th:first-child{width:76px}th:nth-child(2){width:27%}.number{text-align:right;font-variant-numeric:tabular-nums}.rank{font-size:20px}.leading,.score{font-weight:700}.score{font-size:20px}.person a{display:flex;align-items:center;gap:12px;text-decoration:none;min-width:0}.person a:hover .username{text-decoration:underline}.username{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.person img,.avatar{width:36px;height:36px;flex-shrink:0;object-fit:cover}.avatar{display:grid;place-items:center;background:#222;color:white}.person small{background:#dcebdd;color:#265c31;padding:2px 5px;flex-shrink:0}.time{font-size:13px;color:var(--ink-secondary);overflow-wrap:anywhere}tbody tr:hover{background:var(--surface)}.self{background:#f0f7f1}.self td:first-child{box-shadow:inset 3px 0 #38824c}footer{display:flex;justify-content:space-between;margin-top:24px;font-size:14px}.rules{padding:24px;background:var(--surface);margin-bottom:24px}.rules h2{margin-top:0}.rules p+p{margin-top:12px}.state{min-height:380px;padding:24px 0;position:relative}.state p{margin-bottom:20px}.skeleton{height:48px;background:var(--surface);margin-bottom:12px}.loading-label{position:absolute;top:32px;left:20px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}
@container (max-width:900px){table,tbody{display:block}thead{display:none}tr{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));border-bottom:1px solid var(--line);padding:16px 12px;gap:12px}td{display:block;height:auto;border:0;padding:0}.rank{grid-column:1;font-size:18px}.person{grid-column:2 / 4;grid-row:1}.number{text-align:left;font-size:16px}.number:before{content:attr(data-label);display:block;font-size:12px;font-weight:400;color:var(--ink-secondary);margin-bottom:4px}.time{grid-column:1 / -1;font-size:13px}.self td:first-child{box-shadow:none}.self{border-left:3px solid #38824c}.summary{gap:16px}.summary strong{width:100%}}
@media(max-width:600px){.rankings{padding:24px 16px 40px}.heading{gap:12px}h1{font-size:28px}.heading p{font-size:14px}.heading button{white-space:nowrap;padding:8px 12px}.categories{gap:8px;justify-content:space-between}.categories button{font-size:14px}.summary{padding:16px}.summary a{margin-left:0}.toolbar{flex-wrap:wrap;gap:12px}}
</style>
