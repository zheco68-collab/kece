<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/request'
import { solutionApi, type SolutionDetail } from '@/api/solution'
import SolutionArticle from '@/components/solutions/SolutionArticle.vue'
import { useUserStore } from '@/stores/user'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'

const route = useRoute(); const router = useRouter(); const userStore = useUserStore(); const { confirm } = useConfirm(); const toast = useToast()
const solution = ref<SolutionDetail | null>(null); const loading = ref(true); const error = ref('')
const isOwner = computed(() => solution.value?.authorId === userStore.userInfo?.id)
const questionPath = computed(() => solution.value?.questionType === 'DB' ? `/database/${solution.value.questionId}` : `/questions/${solution.value?.questionId}`)
function date(value: string) { const parsed = new Date(value); return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false }) }
function goBack() { if (solution.value?.questionPublic) router.push(questionPath.value); else router.back() }
async function load() { loading.value=true; error.value=''; try { solution.value=await solutionApi.detail(Number(route.params.id)) } catch (value) { error.value=value instanceof ApiError ? value.message : '题解加载失败' } finally { loading.value=false } }
async function remove() { if (!solution.value) return; const ok=await confirm({ title:'删除题解', message:`确定删除「${solution.value.title}」吗？删除后不可恢复。`, confirmText:'确认删除' }); if (!ok) return; try { await solutionApi.remove(solution.value.id, solution.value.version); toast.success('题解已删除'); router.push(questionPath.value) } catch(value) { toast.error(value instanceof ApiError ? value.message : '删除题解失败') } }
onMounted(() => void load())
</script>

<template>
  <main class="solution-read container">
    <section v-if="loading" class="state card">正在载入题解…</section>
    <section v-else-if="error" class="state card"><p>{{ error }}</p><button class="btn btn-outline" @click="router.back()">返回</button></section>
    <template v-else-if="solution">
      <header class="solution-header">
        <div class="header-toolbar"><button class="back-button" type="button" @click="goBack">← {{ solution.questionPublic ? '返回原题' : '返回上一页' }}</button><div class="head-actions"><button v-if="solution.questionPublic" class="btn btn-outline" @click="router.push(questionPath)">查看原题</button><button v-if="isOwner" class="btn btn-outline" @click="router.push({ name:'solution-edit', params:{ id:solution.id } })">编辑</button><button v-if="isOwner" class="btn btn-danger" @click="remove">删除</button></div></div>
        <div class="header-content"><span class="eyebrow">SOLUTION / {{ solution.questionType }}</span><h1>{{ solution.title }}</h1><div class="solution-meta"><span>题目 #{{ solution.questionId }}</span><span>作者 {{ solution.authorName }}</span><span>更新于 {{ date(solution.updatedAt) }}</span></div></div>
      </header>
      <p v-if="solution.status === 'HIDDEN'" class="hidden-note">该题解已被管理员隐藏，仅作者和管理员可见。{{ solution.lastManagementReason ? ` 原因：${solution.lastManagementReason}` : '' }}</p>
      <section class="article-shell card"><SolutionArticle :content="solution.contentMd" /></section>
    </template>
  </main>
</template>

<style scoped>
.solution-read { max-width:1220px; padding-top:28px; padding-bottom:76px; } .solution-header { border:1px solid var(--line); background:var(--surface); } .header-toolbar { display:flex; align-items:center; justify-content:space-between; gap:16px; min-height:64px; padding:0 20px; border-bottom:1px solid var(--line); background:var(--bg); } .back-button, .head-actions .btn { min-height:34px; border:1px solid #121212; border-radius:0; box-shadow:none; font-size:13px; font-weight:700; } .back-button { display:inline-flex; align-items:center; padding:0 12px; background:#171717; color:#fff; cursor:pointer; } .back-button:hover { background:#fff; color:#171717; } .head-actions { display:flex; gap:8px; flex-wrap:wrap; } .head-actions .btn { padding:0 12px; background:#171717; color:#fff; } .head-actions .btn:hover { background:#fff; color:#171717; } .head-actions .btn-danger { background:#9f1d1d; border-color:#9f1d1d; } .head-actions .btn-danger:hover { color:#9f1d1d; background:#fff; } .header-content { padding:24px 28px 26px; } .eyebrow { color:var(--ink-tertiary); font:12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; letter-spacing:.06em; } h1 { margin:10px 0 18px; font-size:32px; line-height:1.25; } .solution-meta { display:flex; gap:0; flex-wrap:wrap; color:var(--ink-secondary); font-size:13px; } .solution-meta span { padding:0 12px; border-left:1px solid var(--line); } .solution-meta span:first-child { padding-left:0; border-left:0; } .article-shell { margin-top:20px; padding:30px 36px 48px; } .state { padding:48px; text-align:center; color:var(--ink-secondary); } .hidden-note { margin:20px 0 0; padding:12px 16px; border:1px solid #e3b341; background:#fff8db; color:#715400; } @media(max-width:640px) { .solution-read { padding-top:20px; } .header-toolbar { align-items:flex-start; flex-direction:column; padding:14px 16px; } .header-content { padding:20px 16px; } .solution-meta span { margin:3px 0; padding:0 8px; } h1 { font-size:26px; } .article-shell { padding:20px; } }
</style>
