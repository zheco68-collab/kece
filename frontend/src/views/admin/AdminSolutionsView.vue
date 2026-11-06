<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/request'
import { solutionApi, type SolutionListItem, type SolutionQuestionType, type SolutionStatus } from '@/api/solution'
import DataTable from '@/components/DataTable.vue'
import Pagination from '@/components/Pagination.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'

const router = useRouter(); const { confirm } = useConfirm(); const toast = useToast()
const keyword = ref(''); const questionId = ref(''); const type = ref<SolutionQuestionType | ''>(''); const status = ref<SolutionStatus | ''>(''); const page = ref(1); const size = 15
const rows = ref<SolutionListItem[]>([]); const total = ref(0); const loading = ref(false); const loadError = ref('')
const columns = [
  { key:'id', label:'ID', width:'70px', align:'center' as const }, { key:'title', label:'题解标题' }, { key:'questionTitle', label:'关联题目' }, { key:'authorName', label:'作者', width:'120px' }, { key:'status', label:'状态', width:'90px', align:'center' as const }, { key:'updatedAt', label:'最后更新', width:'160px', align:'center' as const },
]
function formatDate(value:string) { const date=new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12:false }) }
async function load() { loading.value=true; loadError.value=''; try { const result=await solutionApi.listAdmin({ keyword:keyword.value.trim()||undefined, questionId:questionId.value ? Number(questionId.value) : undefined, questionType:type.value||undefined, status:status.value||undefined, page:page.value, size }); rows.value=result.list; total.value=result.total } catch(value) { loadError.value=value instanceof ApiError ? value.message : '题解列表加载失败' } finally { loading.value=false } }
function reset() { if (page.value === 1) void load(); else page.value=1 }
let timer: ReturnType<typeof setTimeout> | undefined
watch([keyword, questionId], () => { if(timer) clearTimeout(timer); timer=setTimeout(reset, 300) })
watch([type, status], reset); watch(page, () => void load())
async function toggleStatus(row:SolutionListItem) { const hiding=row.status==='PUBLISHED'; const ok=await confirm({ title:hiding?'隐藏题解':'恢复题解', message:hiding?`隐藏「${row.title}」后仅作者和管理员可见。`:`恢复「${row.title}」并重新公开。`, confirmText:hiding?'确认隐藏':'确认恢复' }); if(!ok)return; try { const updated=await solutionApi.updateStatus(row.id,{status:hiding?'HIDDEN':'PUBLISHED',version:row.version}); row.status=updated.status; row.version=updated.version; toast.success(hiding?'题解已隐藏':'题解已恢复') } catch(value) { toast.error(value instanceof ApiError?value.message:'更新状态失败'); void load() } }
async function remove(row:SolutionListItem) { const ok=await confirm({ title:'删除题解', message:`删除「${row.title}」后不可恢复。`, confirmText:'确认删除' }); if(!ok)return; try { await solutionApi.remove(row.id,row.version); toast.success('题解已删除'); void load() } catch(value) { toast.error(value instanceof ApiError?value.message:'删除题解失败') } }
onMounted(() => void load())
</script>

<template>
  <div class="admin-solutions container">
    <header class="page-header"><div><span class="eyebrow">SOLUTION OPERATIONS</span><h1>题解管理</h1><p>直接处理公开题解的可见性与删除，不影响题目本体。</p></div></header>
    <div class="toolbar"><input v-model="keyword" class="input" placeholder="检索题解标题或正文" /><input v-model="questionId" class="input id-input" inputmode="numeric" placeholder="题目 ID" /><select v-model="type" class="input"><option value="">全部题型</option><option value="ALGO">算法编程</option><option value="DB">SQL 数据库</option></select><select v-model="status" class="input"><option value="">全部状态</option><option value="PUBLISHED">公开</option><option value="HIDDEN">已隐藏</option></select></div>
    <p v-if="loadError" class="error-banner">{{ loadError }}</p>
    <DataTable :columns="columns" :rows="rows" :loading="loading" empty-text="暂无符合条件的题解">
      <template #cell-title="{ row }"><button class="title-link" type="button" @click="router.push({ name:'solution-read', params:{ id:row.id } })">{{ row.title }}</button></template>
      <template #cell-questionTitle="{ row }"><span class="question-cell">{{ row.questionType === 'DB' ? 'DB' : 'ALGO' }} #{{ row.questionId }} · {{ row.questionTitle }}</span></template>
      <template #cell-status="{ row }"><span class="status" :class="row.status === 'PUBLISHED' ? 'published' : 'hidden'">{{ row.status === 'PUBLISHED' ? '公开' : '隐藏' }}</span></template>
      <template #cell-updatedAt="{ row }"><span class="time">{{ formatDate(row.updatedAt) }}</span></template>
      <template #actions="{ row }"><div class="actions"><button class="action" type="button" @click="toggleStatus(row)">{{ row.status === 'PUBLISHED' ? '隐藏' : '恢复' }}</button><button class="action danger" type="button" @click="remove(row)">删除</button></div></template>
    </DataTable>
    <Pagination v-model:page="page" :size="size" :total="total" />
  </div>
</template>

<style scoped>
.admin-solutions { padding-top:16px; padding-bottom:48px; } .page-header { margin:16px 0 20px; } .eyebrow { color:var(--ink-tertiary); font:12px/1 ui-monospace, SFMono-Regular, Consolas, monospace; letter-spacing:.06em; } h1 { margin:8px 0; font-size:23px; } .page-header p { margin:0; color:var(--ink-secondary); font-size:14px; } .toolbar { display:grid; grid-template-columns:minmax(220px,1fr) 120px 150px 130px; gap:10px; margin-bottom:16px; } .toolbar .input { height:38px; } .title-link { padding:0; border:0; background:transparent; color:var(--ink); font-weight:700; cursor:pointer; text-align:left; } .title-link:hover { text-decoration:underline; } .question-cell,.time { color:var(--ink-secondary); font-size:13px; } .status { display:inline-flex; padding:3px 8px; border:1px solid; font-size:12px; font-weight:700; } .published { color:#23723c; border-color:#9bd2a9; background:#ebfbee; } .hidden { color:#795f1e; border-color:#e7cc82; background:#fff8db; } .actions { display:flex; gap:6px; } .action { padding:4px 8px; border:1px solid var(--line); background:#fff; color:var(--ink-secondary); cursor:pointer; } .action:hover { border-color:var(--ink); color:var(--ink); } .danger { color:var(--danger); } .error-banner { margin:0 0 16px; padding:10px 12px; color:var(--danger); border:1px solid var(--danger); } @media(max-width:900px) { .toolbar { grid-template-columns:1fr 1fr; } } @media(max-width:560px) { .toolbar { grid-template-columns:1fr; } }
</style>
