<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  judgersApi,
  type HostGroupView,
  type JudgeNodeView,
  type JudgeNodeDetail,
  type JudgeConnectionView,
} from '@/api/judgers'
import { ApiError } from '@/api/request'
import FormField from '@/components/FormField.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'

const { confirm } = useConfirm()
const toast = useToast()

// ---- 列表状态 ----
const nodes = ref<JudgeNodeView[]>([])
const hostGroups = ref<HostGroupView[]>([])
const connections = ref<JudgeConnectionView[]>([])
const loading = ref(false)
const loadError = ref('')

// ---- 过滤筛选 ----
const filterPool = ref('')
const filterState = ref('')
const filterHealth = ref('')
const search = ref('')
const openActions = ref<string | null>(null)
const lastUpdatedAt = ref<Date | null>(null)
const updateTime = new Intl.DateTimeFormat('zh-CN', { timeStyle: 'medium', hour12: false })

const poolOptions = [
  { value: '', label: '全部专池' },
  { value: 'cpp', label: 'C++' },
  { value: 'java', label: 'Java' },
  { value: 'python', label: 'Python' },
  { value: 'mysql', label: 'MySQL' },
  { value: 'arkts', label: 'ArkTS' },
]

const stateOptions = [
  { value: '', label: '全部配置状态' },
  { value: 'ENABLED', label: '已启用' },
  { value: 'DRAINING', label: '排空中' },
  { value: 'DISABLED', label: '已停用' },
  { value: 'PENDING_VERIFICATION', label: '待验证' },
]

const healthOptions = [
  { value: '', label: '全部健康状态' },
  { value: 'HEALTHY', label: '健康' },
  { value: 'DEGRADED', label: '降级' },
  { value: 'OFFLINE', label: '离线' },
  { value: 'UNVERIFIED', label: '未自检' },
  { value: 'UNHEALTHY', label: '异常' },
]

const hostGroupMap = computed(() => {
  const map = new Map<string, string>()
  for (const h of hostGroups.value) {
    map.set(String(h.id), h.name)
  }
  return map
})

const poolSummary = computed(() => poolOptions.slice(1).map((pool) => {
  const members = nodes.value.filter((node) => node.pool === pool.value)
  return {
    ...pool,
    total: members.length,
    healthy: members.filter((node) => node.state === 'ENABLED' && node.health === 'HEALTHY').length,
  }
}))
const healthyPools = computed(() => poolSummary.value.filter((pool) => pool.healthy > 0).length)
const hasFilters = computed(() => Boolean(search.value || filterPool.value || filterState.value || filterHealth.value))

function labelFor(options: readonly { value: string; label: string }[], value: string) {
  return options.find((item) => item.value === value)?.label ?? value
}

function hostName(id: string) {
  return hostGroupMap.value.get(id) ?? `宿主机 #${id}`
}

function hostNodeCount(host: HostGroupView) {
  return host.nodeCount ?? nodes.value.filter((node) => node.hostGroupId === String(host.id)).length
}

const filteredNodes = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return nodes.value.filter((node) => {
    return (!filterPool.value || node.pool === filterPool.value)
      && (!filterState.value || node.state === filterState.value)
      && (!filterHealth.value || node.health === filterHealth.value)
      && (!keyword || [node.name, hostName(node.hostGroupId), `#${node.nodeId}`, node.nodeId]
        .some((value) => value.toLowerCase().includes(keyword)))
  })
})

function clearFilters() {
  search.value = ''
  filterPool.value = ''
  filterState.value = ''
  filterHealth.value = ''
  openActions.value = null
}

function closeUnfocusedActions(event: FocusEvent) {
  const actions = event.currentTarget as HTMLElement
  if (!actions.contains(event.relatedTarget as Node | null)) openActions.value = null
}

function closeActions(event: KeyboardEvent) {
  openActions.value = null
  const actions = event.currentTarget as HTMLElement
  actions.querySelector('summary')?.focus()
}

async function fetchNodes() {
  const result: JudgeNodeView[] = []
  let afterId: string | undefined
  for (;;) {
    const page = await judgersApi.getNodes({ limit: 100, afterId })
    result.push(...page)
    const next = page.at(-1)?.nodeId
    if (page.length < 100 || !next || next === afterId) return result
    afterId = next
  }
}

async function loadData() {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const [nodeList, hostList] = await Promise.all([
      fetchNodes(),
      judgersApi.getHostGroups(),
    ])
    nodes.value = nodeList
    hostGroups.value = hostList
    lastUpdatedAt.value = new Date()
  } catch (err: unknown) {
    loadError.value = err instanceof Error ? err.message : '加载评测机列表失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadData()
  connectionTimer = setInterval(pollConnection, 2000)
})
onUnmounted(() => {
  if (connectionTimer) clearInterval(connectionTimer)
})

// ---- 格式化辅助 ----
function formatTime(iso: string | null | undefined): string {
  if (!iso) return '--'
  return iso.replace('T', ' ').slice(0, 19)
}

function healthClass(health: string): string {
  switch (health) {
    case 'HEALTHY':
      return 'badge-healthy'
    case 'OFFLINE':
      return 'badge-offline'
    case 'DEGRADED':
    case 'UNHEALTHY':
      return 'badge-unhealthy'
    case 'UNVERIFIED':
    default:
      return 'badge-unverified'
  }
}

// ---- 唯一接入入口：粘贴节点 API 地址 ----
const showConnectModal = ref(false)
const connectUrl = ref('')
const connectKey = ref('')
const connectSubmitting = ref(false)
const connectError = ref('')
const activeConnection = ref<JudgeConnectionView | null>(null)
let connectionTimer: ReturnType<typeof setInterval> | null = null

const pendingStatuses = new Set(['REQUESTED', 'DISCOVERING', 'WAITING_BIND', 'WAITING_HEARTBEAT', 'CHECKING'])

function connectionLabel(status: string): string {
  return ({
    REQUESTED: '等待发现', DISCOVERING: '识别节点中', WAITING_BIND: '等待节点绑定',
    WAITING_HEARTBEAT: '等待健康心跳', CHECKING: '运行环境自检中',
    SUCCEEDED: '已接入', FAILED: '接入失败', CANCELLED: '已取消', EXPIRED: '已过期',
  } as Record<string, string>)[status] ?? status
}

async function loadConnections() {
  try {
    connections.value = await judgersApi.getConnections()
  } catch {
    // 接入记录加载失败不影响新节点接入。
  }
}

function openConnectModal(connection?: JudgeConnectionView) {
  connectUrl.value = connection?.apiUrl ?? ''
  activeConnection.value = connection ?? null
  connectKey.value = ''
  connectError.value = ''
  showConnectModal.value = true
  void loadConnections()
}

async function submitConnect() {
  const apiUrl = connectUrl.value.trim()
  if (!apiUrl) {
    connectError.value = '请粘贴评测机 API 地址'
    return
  }
  connectSubmitting.value = true
  connectError.value = ''
  if (activeConnection.value && !pendingStatuses.has(activeConnection.value.status)) connectKey.value = ''
  if (!connectKey.value) connectKey.value = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}${Math.random().toString(36).slice(2)}`
  try {
    activeConnection.value = await judgersApi.connectNode(apiUrl, connectKey.value)
    await Promise.all([loadData(), loadConnections()])
  } catch (err: unknown) {
    connectError.value = err instanceof ApiError ? err.message : '接入请求失败，请重试'
  } finally {
    connectSubmitting.value = false
  }
}

async function pollConnection() {
  const current = activeConnection.value
  if (!showConnectModal.value || !current || !pendingStatuses.has(current.status)) return
  try {
    const updated = await judgersApi.getConnection(current.connectionId)
    if (activeConnection.value?.connectionId !== updated.connectionId) return
    activeConnection.value = updated
    const position = connections.value.findIndex((item) => item.connectionId === updated.connectionId)
    if (position >= 0) connections.value[position] = updated
    if (updated.status === 'SUCCEEDED') {
      toast.success('评测机已通过自检并接入评测池')
      await loadData()
    }
  } catch {
    // 暂时断线时保留当前阶段，下一轮继续查询。
  }
}

async function cancelConnection() {
  if (!activeConnection.value || !pendingStatuses.has(activeConnection.value.status)) return
  try {
    activeConnection.value = await judgersApi.cancelConnection(activeConnection.value.connectionId)
    await Promise.all([loadData(), loadConnections()])
  } catch (err: unknown) {
    connectError.value = err instanceof ApiError ? err.message : '取消接入失败'
  }
}

// ---- 编辑节点弹窗 ----
const showEditModal = ref(false)
const editTarget = ref<JudgeNodeView | null>(null)
const editState = ref('')
const editMaxSlots = ref(1)
const editSubmitting = ref(false)
const editError = ref('')

function openEditModal(node: JudgeNodeView) {
  editTarget.value = node
  editState.value = node.state
  editMaxSlots.value = node.maxSlots
  editError.value = ''
  showEditModal.value = true
}

async function submitEdit() {
  if (!editTarget.value) return
  if (editMaxSlots.value < 1 || editMaxSlots.value > 64) {
    editError.value = '槽位数必须在 1 到 64 之间'
    return
  }

  editSubmitting.value = true
  editError.value = ''
  try {
    await judgersApi.updateNode(editTarget.value.nodeId, {
      revision: editTarget.value.revision,
      state: editState.value !== editTarget.value.state ? editState.value : undefined,
      maxSlots: editMaxSlots.value,
    })
    toast.success('节点配置已更新')
    showEditModal.value = false
    loadData()
  } catch (err: unknown) {
    editError.value = err instanceof ApiError ? err.message : '更新配置失败'
  } finally {
    editSubmitting.value = false
  }
}

// ---- 发起自检 ----
async function handleCheck(node: JudgeNodeView) {
  const ok = await confirm({
    title: '发起沙箱自检',
    message: `确定向节点 ${node.name} (${node.pool}) 下发沙箱与运行时自检指令？`,
  })
  if (!ok) return

  try {
    await judgersApi.requestCheck(node.nodeId)
    toast.success('已下发自检指令，请稍后刷新查看诊断结论')
    loadData()
  } catch (err: unknown) {
    toast.error(err instanceof ApiError ? err.message : '下发自检指令失败')
  }
}

// ---- 注销停用 ----
async function handleRevoke(node: JudgeNodeView) {
  const ok = await confirm({
    title: '注销节点凭据',
    message: `确定注销节点 ${node.name}？该节点将被置为 DISABLED 并清除所有凭据。`,
    danger: true,
  })
  if (!ok) return

  try {
    await judgersApi.revokeNode(node.nodeId, node.revision)
    toast.success('节点已注销停用')
    loadData()
  } catch (err: unknown) {
    toast.error(err instanceof ApiError ? err.message : '注销节点失败')
  }
}

// ---- 删除节点 ----
async function handleDeleteNode(node: JudgeNodeView) {
  openActions.value = null
  const ok = await confirm({
    title: '删除评测节点',
    message: `确定删除评测节点「${node.name}」吗？节点需要已停用，且不能有在途任务或自检。删除后不会再出现在节点列表中。`,
    confirmText: '删除节点',
    danger: true,
  })
  if (!ok) return

  try {
    await judgersApi.deleteNode(node.nodeId)
    toast.success('评测节点已成功删除')
    await loadData()
  } catch (err: unknown) {
    toast.error(err instanceof ApiError ? err.message : '删除评测节点失败')
  }
}

// ---- 详情抽屉/弹窗 ----
const showDetailModal = ref(false)
const detailLoading = ref(false)
const nodeDetail = ref<JudgeNodeDetail | null>(null)
const detailError = ref('')

async function openDetailModal(node: JudgeNodeView) {
  showDetailModal.value = true
  detailLoading.value = true
  detailError.value = ''
  nodeDetail.value = null
  try {
    nodeDetail.value = await judgersApi.getNodeDetail(node.nodeId)
  } catch (err: unknown) {
    detailError.value = err instanceof Error ? err.message : '获取详情失败'
  } finally {
    detailLoading.value = false
  }
}

// ---- 宿主机分组管理弹窗 ----
const showHostModal = ref(false)
const hostSlotsDraft = ref<Record<string, number>>({})
const hostSubmitting = ref(false)
const hostError = ref('')

function openHostModal() {
  hostSlotsDraft.value = Object.fromEntries(hostGroups.value.map((host) => [host.id, host.maxSlots]))
  hostError.value = ''
  showHostModal.value = true
}

async function saveHostBudget(host: HostGroupView) {
  const slots = hostSlotsDraft.value[host.id]
  if (!Number.isInteger(slots) || slots < 1 || slots > 256) {
    hostError.value = '槽位数必须在 1 到 256 之间'
    return
  }
  hostSubmitting.value = true
  hostError.value = ''
  try {
    await judgersApi.updateHostGroup(host.id, { revision: host.revision, maxSlots: slots })
    toast.success('宿主机共享额度已更新')
    hostGroups.value = await judgersApi.getHostGroups()
    hostSlotsDraft.value = Object.fromEntries(hostGroups.value.map((item) => [item.id, item.maxSlots]))
  } catch (err: unknown) {
    hostError.value = err instanceof ApiError ? err.message : '更新宿主机额度失败'
  } finally {
    hostSubmitting.value = false
  }
}

async function handleDeleteHost(host: HostGroupView) {
  const count = hostNodeCount(host)
  if (count > 0) {
    toast.error(`该分组下仍有 ${count} 个评测节点，请先删除节点后再删除分组`)
    return
  }
  const ok = await confirm({
    title: '删除宿主机分组',
    message: `确定要删除宿主机分组「${host.name}」吗？`,
    confirmText: '删除分组',
    danger: true,
  })
  if (!ok) return

  hostSubmitting.value = true
  hostError.value = ''
  try {
    await judgersApi.deleteHostGroup(host.id)
    toast.success('宿主机分组已删除')
    hostGroups.value = await judgersApi.getHostGroups()
    hostSlotsDraft.value = Object.fromEntries(hostGroups.value.map((item) => [item.id, item.maxSlots]))
    await loadData()
  } catch (err: unknown) {
    hostError.value = err instanceof ApiError ? err.message : '删除宿主机分组失败'
  } finally {
    hostSubmitting.value = false
  }
}
</script>

<template>
  <div class="admin-judgers-view">
    <header class="page-head">
      <div>
        <h1 class="page-title">评测机管理</h1>
        <p class="page-desc">粘贴节点 API 地址，自动识别专池、绑定身份并完成运行环境自检。</p>
      </div>
      <div class="head-actions">
        <button class="btn btn-outline" @click="openHostModal">
          <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
            <path d="M3 4h18v6H3zM3 14h18v6H3zM6 7h2m-2 10h2" />
          </svg>
          宿主机额度 <span v-if="lastUpdatedAt" class="action-count">{{ hostGroups.length }}</span>
        </button>
        <button class="btn btn-primary" @click="openConnectModal()">
          <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          接入评测机
        </button>
      </div>
    </header>

    <section class="pool-overview" aria-labelledby="judge-pools-title" :aria-busy="loading">
      <div class="pool-heading"><h2 id="judge-pools-title">专池</h2><span v-if="lastUpdatedAt" class="pool-total">{{ healthyPools }} / {{ poolSummary.length }} 正常</span></div>
      <div class="pool-grid" role="group" aria-label="选择评测专池">
        <button
          v-for="pool in poolSummary" :key="pool.value" type="button" class="pool-cell"
          :aria-pressed="filterPool === pool.value" :aria-label="`筛选 ${pool.label} 节点`"
          :aria-describedby="`judge-pool-${pool.value}`"
          @click="filterPool = filterPool === pool.value ? '' : pool.value; openActions = null"
        >
          <span v-if="lastUpdatedAt" class="pool-dot" :class="{ 'is-healthy': pool.healthy > 0 }" aria-hidden="true"></span>
          <strong class="pool-cell-name">{{ pool.label }}</strong>
          <span :id="`judge-pool-${pool.value}`" class="pool-cell-count">
            <template v-if="lastUpdatedAt"><strong>{{ pool.healthy }}</strong>/{{ pool.total }}</template>
            <template v-else>{{ loading ? '加载中' : '暂无数据' }}</template>
          </span>
        </button>
      </div>
    </section>

    <section class="node-panel" aria-labelledby="judge-nodes-title" :aria-busy="loading">
      <div class="node-panel-head">
        <div class="node-panel-title">
          <h2 id="judge-nodes-title">节点列表</h2>
          <span v-if="lastUpdatedAt" class="count-tag" role="status">
            {{ hasFilters ? `${filteredNodes.length} / ${nodes.length}` : nodes.length }} 台
          </span>
        </div>
        <div class="refresh-group">
          <time v-if="lastUpdatedAt" :datetime="lastUpdatedAt.toISOString()">{{ loadError ? '上次成功更新' : '更新于' }} {{ updateTime.format(lastUpdatedAt) }}</time>
          <button class="btn btn-outline btn-refresh" :disabled="loading" @click="loadData">
            <svg class="btn-icon" :class="{ 'is-spinning': loading }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
              <path d="M20 4v5h-5m5 5a8 8 0 1 1-1.5-7L20 9" />
            </svg>
            {{ loading ? '刷新中' : '刷新' }}
          </button>
        </div>
      </div>
      <div class="filter-bar">
        <label class="node-search">
          <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
            <circle cx="10.5" cy="10.5" r="5.5" /><path d="m15 15 5 5" />
          </svg>
          <input v-model="search" type="search" aria-label="搜索节点或宿主机" placeholder="搜索节点、编号或宿主机" />
        </label>
        <select v-model="filterPool" class="filter-select" aria-label="按专池筛选">
          <option v-for="opt in poolOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <select v-model="filterState" class="filter-select" aria-label="按配置状态筛选">
          <option v-for="opt in stateOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <select v-model="filterHealth" class="filter-select" aria-label="按健康状态筛选">
          <option v-for="opt in healthOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <button v-if="hasFilters" class="filter-clear" @click="clearFilters">清除筛选</button>
      </div>

      <div v-if="loadError" class="error-banner" role="alert">
        <span>{{ loadError }}</span><button class="btn btn-outline" @click="loadData">重试</button>
      </div>
      <div v-if="loading && !lastUpdatedAt" class="table-message" role="status">正在读取节点状态…</div>
      <div v-else-if="lastUpdatedAt && !filteredNodes.length" class="table-message">
        <strong>{{ hasFilters ? '没有符合条件的节点' : '还没有接入评测节点' }}</strong>
        <button v-if="hasFilters" class="btn btn-outline" @click="clearFilters">清除筛选</button>
        <button v-else class="btn btn-outline" @click="openConnectModal()">接入第一个节点</button>
      </div>
      <table v-else-if="filteredNodes.length" class="node-table" role="table" aria-label="评测节点">
        <colgroup><col class="col-node" /><col class="col-status" /><col class="col-slots" /><col class="col-time" /><col class="col-actions" /></colgroup>
        <thead role="rowgroup">
          <tr role="row"><th scope="col">节点</th><th scope="col">运行状态</th><th scope="col">槽位占用</th><th scope="col">最近心跳</th><th scope="col" class="row-operations">操作</th></tr>
        </thead>
        <tbody role="rowgroup">
          <tr v-for="row in filteredNodes" :key="row.nodeId" role="row">
            <th scope="row" class="node-identity">
              <button class="node-name" :title="row.name" @click="openDetailModal(row)">{{ row.name }}</button>
              <span class="node-meta"><span class="pool-badge">{{ labelFor(poolOptions, row.pool) }}</span><span class="host-name" :title="hostName(row.hostGroupId)">{{ hostName(row.hostGroupId) }}</span></span>
            </th>
            <td role="cell" class="node-status">
              <span class="status-badge" :class="healthClass(row.health)">{{ labelFor(healthOptions, row.health) }}</span>
              <span class="state-label">{{ labelFor(stateOptions, row.state) }}</span>
            </td>
            <td role="cell" class="node-slots" data-label="槽位占用">
              <span class="slot-number"><strong>{{ row.running }}</strong> / {{ row.maxSlots }}</span>
              <progress v-if="row.maxSlots > 0" :value="row.running" :max="row.maxSlots" :aria-label="`${row.name} 槽位占用`"></progress>
            </td>
            <td role="cell" class="node-heartbeat" data-label="最近心跳">
              <time v-if="row.heartbeatAt" :datetime="row.heartbeatAt">{{ formatTime(row.heartbeatAt) }}</time>
              <span v-else>尚未收到</span>
              <span class="runtime-version" :title="row.runtimeVersion || row.runtimeSpec">{{ row.runtimeVersion || row.runtimeSpec }}</span>
            </td>
            <td role="cell" class="row-operations">
              <div class="actions-group">
                <button class="action-btn action-detail" @click="openDetailModal(row)">详情</button>
                <button v-if="row.state === 'DISABLED' && row.running === 0" class="action-btn action-remove" @click="handleDeleteNode(row)">删除</button>
                <details :open="openActions === row.nodeId" class="actions-disclosure" @focusout="closeUnfocusedActions" @keydown.esc.stop="closeActions">
                  <summary :aria-label="`${row.name} 的更多操作`" @click.prevent="openActions = openActions === row.nodeId ? null : row.nodeId">
                    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.7"/><circle cx="12" cy="12" r="1.7"/><circle cx="19" cy="12" r="1.7"/></svg>
                  </summary>
                  <div class="actions-popover" @click="openActions = null">
                    <button class="action-btn" @click="handleCheck(row)">发起自检</button>
                    <button class="action-btn" @click="openEditModal(row)">调整配置</button>
                    <button v-if="row.state !== 'DISABLED'" class="action-btn btn-danger" @click="handleRevoke(row)">注销节点凭据</button>
                    <button v-if="row.state === 'DISABLED' && row.running === 0" class="action-btn btn-danger" @click="handleDeleteNode(row)">删除节点</button>
                    <span v-else class="action-hint">停用并排空后可删除</span>
                  </div>
                </details>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 唯一接入入口：粘贴评测机 API 地址 -->
    <div v-if="showConnectModal" class="modal-backdrop" @click.self="showConnectModal = false">
      <div class="modal-box connect-modal" role="dialog" aria-modal="true" aria-label="接入评测机">
        <div class="modal-head">
          <h2 class="modal-title">接入评测机</h2>
          <button class="modal-close" aria-label="关闭弹窗" @click="showConnectModal = false">&times;</button>
        </div>
        <div class="modal-body">
          <p class="connect-intro">粘贴评测机提供的 API 地址。系统会识别所属专池、绑定节点并执行运行环境自检。</p>
          <FormField label="评测机 API 地址" required hint="支持 HTTP、自签名 HTTPS，以及公网或私网地址">
            <input v-model.trim="connectUrl" class="form-input mono" type="url" autocomplete="url" spellcheck="false" placeholder="http://10.20.0.15:19091" :disabled="connectSubmitting || Boolean(activeConnection && pendingStatuses.has(activeConnection.status))" @keydown.enter="submitConnect" />
          </FormField>
          <div v-if="activeConnection" class="connect-progress" role="status" aria-live="polite">
            <span class="connect-progress-label">接入进度 · #{{ activeConnection.connectionId }}</span>
            <strong :class="{ 'is-error': ['FAILED', 'EXPIRED'].includes(activeConnection.status) }">{{ connectionLabel(activeConnection.status) }}</strong>
            <p v-if="activeConnection.errorDetail">{{ activeConnection.errorDetail }}</p>
            <p v-else-if="activeConnection.status === 'SUCCEEDED'">节点 #{{ activeConnection.nodeId }} 已通过自检，可以在节点列表中查看。</p>
            <p v-else-if="pendingStatuses.has(activeConnection.status)">接入正在后台进行。关闭弹窗后仍可在此处的接入记录中查看进度。</p>
          </div>
          <details v-if="connections.length" class="connection-history">
            <summary>接入记录 <span>{{ connections.length }}</span></summary>
            <div class="connection-list">
              <button v-for="item in connections.slice(0, 10)" :key="item.connectionId" class="connection-row" @click="openConnectModal(item)">
                <span class="connection-address" :title="item.apiUrl">{{ item.apiUrl }}</span>
                <span class="connection-state" :class="{ 'is-done': item.status === 'SUCCEEDED', 'is-error': ['FAILED', 'EXPIRED'].includes(item.status) }">{{ connectionLabel(item.status) }}</span>
                <span class="connection-open">查看 →</span>
              </button>
            </div>
          </details>
          <div v-if="connectError" class="form-error-msg" role="alert">{{ connectError }}</div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-secondary" @click="showConnectModal = false">关闭</button>
          <button v-if="activeConnection && pendingStatuses.has(activeConnection.status)" class="btn btn-outline" @click="cancelConnection">取消接入</button>
          <button v-if="!activeConnection || ['FAILED', 'EXPIRED', 'CANCELLED'].includes(activeConnection.status)" class="btn btn-primary" :disabled="connectSubmitting" @click="submitConnect">
            {{ connectSubmitting ? '提交中…' : activeConnection ? '重新接入' : '开始接入' }}
          </button>
        </div>
      </div>
    </div>
    <!-- 弹窗 3：调配状态与槽位 -->
    <div v-if="showEditModal" class="modal-backdrop" @click.self="showEditModal = false">
      <div class="modal-box" role="dialog" aria-modal="true" aria-label="评测节点操作">
        <div class="modal-head">
          <h2 class="modal-title">调配节点状态与槽位</h2>
          <button class="modal-close" aria-label="关闭弹窗" @click="showEditModal = false">&times;</button>
        </div>

        <div class="modal-body">
          <div class="node-target-info mono">
            目标节点: {{ editTarget?.name }} (ID: {{ editTarget?.nodeId }})
          </div>

          <FormField label="期望配置状态 (State)" required hint="ENABLED=接受新任务, DRAINING=排空存量不再接单, DISABLED=停用">
            <select v-model="editState" class="form-input mono">
              <option value="ENABLED">ENABLED - 启用接单 (需通过自检)</option>
              <option value="DRAINING">DRAINING - 优雅排空 (完成存量后停机)</option>
              <option value="DISABLED">DISABLED - 停用下线</option>
            </select>
          </FormField>

          <FormField label="最大槽位数 (Max Slots)" required hint="不可超过所属宿主机的总槽位上限">
            <input v-model.number="editMaxSlots" type="number" min="1" max="64" class="form-input mono" />
          </FormField>

          <div v-if="editError" class="form-error-msg mono">{{ editError }}</div>
        </div>

        <div class="modal-foot">
          <button class="btn btn-secondary mono" @click="showEditModal = false">取消</button>
          <button class="btn btn-primary mono" :disabled="editSubmitting" @click="submitEdit">
            {{ editSubmitting ? '保存中...' : '保存更改' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 弹窗 4：节点详情 (自检、任务、审计) -->
    <div v-if="showDetailModal" class="modal-backdrop" @click.self="showDetailModal = false">
      <div class="modal-box modal-lg" role="dialog" aria-modal="true" aria-label="评测节点管理">
        <div class="modal-head">
          <h2 class="modal-title">
            节点详情 · {{ nodeDetail?.node?.name || '加载中...' }}
          </h2>
          <button class="modal-close" aria-label="关闭弹窗" @click="showDetailModal = false">&times;</button>
        </div>

        <div v-if="detailLoading" class="modal-body mono text-center py-6">
          正在加载节点详情...
        </div>

        <div v-else-if="detailError" class="modal-body mono error-text py-6">
          {{ detailError }}
        </div>

        <div v-else-if="nodeDetail" class="modal-body">
          <!-- 节点元数据网格 -->
          <div class="meta-grid mono">
            <div class="meta-item">
              <span class="meta-k">节点编号</span>
              <span class="meta-v">{{ nodeDetail.node.nodeId }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">评测专池</span>
              <span class="meta-v">{{ labelFor(poolOptions, nodeDetail.node.pool) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">配置状态</span>
              <span class="meta-v">{{ labelFor(stateOptions, nodeDetail.node.state) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">健康状态</span>
              <span class="meta-v">{{ labelFor(healthOptions, nodeDetail.node.health) }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">槽位占用</span>
              <span class="meta-v">{{ nodeDetail.node.running }} / {{ nodeDetail.node.maxSlots }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">配置版本</span>
              <span class="meta-v">{{ nodeDetail.node.revision }}</span>
            </div>
          </div>

          <dl class="detail-endpoint">
            <div><dt>宿主机</dt><dd>{{ hostName(nodeDetail.node.hostGroupId) }}</dd></div>
            <div><dt>运行时</dt><dd>{{ nodeDetail.node.runtimeVersion || nodeDetail.node.runtimeSpec }}</dd></div>
            <div><dt>探测地址</dt><dd>{{ nodeDetail.node.baseUrl || '未配置' }}</dd></div>
          </dl>

          <!-- 自检记录 -->
          <div class="detail-section">
            <h3 class="section-title mono">沙箱自检</h3>
            <div v-if="!nodeDetail.checks || nodeDetail.checks.length === 0" class="empty-tip mono">
              暂无自检记录
            </div>
            <div v-else class="record-table-wrap">
              <table class="record-table mono">
                <thead>
                  <tr>
                    <th>CHECK ID</th>
                    <th>STATE</th>
                    <th>VERSION</th>
                    <th>DIAGNOSTIC DETAIL</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="c in nodeDetail.checks" :key="c.checkId">
                    <td>{{ c.checkId }}</td>
                    <td>
                      <span class="status-badge" :class="c.state === 'PASSED' ? 'badge-healthy' : 'badge-unhealthy'">
                        {{ c.state }}
                      </span>
                    </td>
                    <td>{{ c.runtimeVersion || '--' }}</td>
                    <td class="text-detail">{{ c.detail || '--' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 任务尝试记录 -->
          <div class="detail-section">
            <h3 class="section-title mono">近期评测任务</h3>
            <div v-if="!nodeDetail.attempts || nodeDetail.attempts.length === 0" class="empty-tip mono">
              暂无评测执行记录
            </div>
            <div v-else class="record-table-wrap">
              <table class="record-table mono">
                <thead>
                  <tr>
                    <th>JOB ID</th>
                    <th>LEASE EPOCH</th>
                    <th>OUTCOME</th>
                    <th>STARTED AT</th>
                    <th>ENDED AT</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="a in nodeDetail.attempts" :key="`${a.jobId}-${a.leaseEpoch}`">
                    <td>{{ a.jobId }}</td>
                    <td>{{ a.leaseEpoch }}</td>
                    <td>
                      <span class="status-badge" :class="a.outcome === 'ACCEPTED' ? 'badge-healthy' : 'badge-pending'">
                        {{ a.outcome || 'IN_PROGRESS' }}
                      </span>
                    </td>
                    <td>{{ formatTime(a.startedAt) }}</td>
                    <td>{{ formatTime(a.endedAt) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 审计日志 -->
          <div class="detail-section">
            <h3 class="section-title mono">操作记录</h3>
            <div v-if="!nodeDetail.audit || nodeDetail.audit.length === 0" class="empty-tip mono">
              暂无审计记录
            </div>
            <div v-else class="record-table-wrap">
              <table class="record-table mono">
                <thead>
                  <tr>
                    <th>ACTION</th>
                    <th>OPERATOR</th>
                    <th>REV</th>
                    <th>TIME</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(au, idx) in nodeDetail.audit" :key="idx">
                    <td>{{ au.action }}</td>
                    <td>{{ au.actorId }}</td>
                    <td>{{ au.revision }}</td>
                    <td>{{ formatTime(au.at) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div class="modal-foot">
          <button class="btn btn-secondary mono" @click="showDetailModal = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- 宿主机共享额度管理 -->
    <div v-if="showHostModal" class="modal-backdrop" @click.self="showHostModal = false">
      <div class="modal-box modal-lg" role="dialog" aria-modal="true" aria-label="评测节点管理">
        <div class="modal-head">
          <h2 class="modal-title">宿主机共享额度</h2>
          <button class="modal-close" aria-label="关闭弹窗" @click="showHostModal = false">&times;</button>
        </div>

        <div class="modal-body">
          <p class="host-budget-note">同一宿主机上的各语言评测进程共享总槽位。新节点会自动关联宿主机，首次额度为 2。</p>
          <div v-if="hostError" class="form-error-msg mono" role="alert">{{ hostError }}</div>

          <div class="record-table-wrap mt-4">
            <table class="record-table mono">
              <thead>
                <tr>
                  <th>GROUP ID</th>
                  <th>分组名称</th>
                  <th>共享槽位</th>
                  <th>关联节点</th>
                  <th>版本号</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="h in hostGroups" :key="h.id">
                  <td>{{ h.id }}</td>
                  <td class="font-bold">{{ h.name }}</td>
                  <td><input v-model.number="hostSlotsDraft[h.id]" type="number" min="1" max="256" class="form-input mono slots-input" :aria-label="`${h.name} 共享槽位`" /></td>
                  <td><span class="count-tag" :class="{ 'count-empty': !hostNodeCount(h) }">{{ hostNodeCount(h) }} 台</span></td>
                  <td>REV: {{ h.revision }}</td>
                  <td>
                    <div class="host-actions">
                      <button class="btn btn-outline" :disabled="hostSubmitting || hostSlotsDraft[h.id] === h.maxSlots" @click="saveHostBudget(h)">保存</button>
                      <button
                        class="btn btn-outline btn-delete"
                        :disabled="hostSubmitting || hostNodeCount(h) > 0"
                        :title="hostNodeCount(h) > 0 ? '分组下存在关联节点，请先删除节点' : '删除宿主机分组'"
                        @click="handleDeleteHost(h)"
                      >
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="modal-foot">
          <button class="btn btn-secondary mono" @click="showHostModal = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-judgers-view {
  --judge-panel: #fcfcfd;
  --judge-rule: #dde1e6;
  --judge-hover: #f0f2f4;
  width: 100%;
  max-width: 1440px;
  margin-inline: auto;
  container: judges / inline-size;
}

.page-head, .pool-heading, .node-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
}

.page-head { margin: 4px 0 22px; }
.page-title { margin: 0; font-size: clamp(26px, 3cqw, 34px); line-height: 1.18; letter-spacing: -.035em; }
.page-desc { margin: 6px 0 0; font-size: 14px; color: var(--ink-secondary); }
.head-actions, .refresh-group, .node-panel-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 38px;
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  border: 1px solid transparent;
  border-radius: 0;
}
.btn:disabled { cursor: wait; opacity: .65; }
.btn-primary { background: var(--button); color: #f7f8fa; border-color: var(--button); }
.btn-primary:hover:not(:disabled) { background: #37393d; }
.btn-outline { background: var(--judge-panel); border-color: var(--judge-rule); }
.btn-outline:hover:not(:disabled), .btn-secondary:hover { background: var(--judge-hover); }
.btn-secondary { background: var(--surface); color: var(--ink); }
.btn-icon { width: 16px; height: 16px; flex: 0 0 16px; }
.action-count { padding-inline: 5px; background: var(--surface-hover); font-variant-numeric: tabular-nums; }

.pool-overview, .node-panel { border: 1px solid var(--judge-rule); background: var(--judge-panel); }
.pool-overview { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: stretch; margin-bottom: 14px; }
.connection-history { margin-top: 16px; border: 1px solid var(--judge-rule); background: var(--judge-panel); }
.connection-history summary { display: flex; align-items: center; justify-content: space-between; padding: 9px 12px; font-size: 12px; font-weight: 700; cursor: pointer; }
.connection-history summary span { color: var(--ink-secondary); font-variant-numeric: tabular-nums; }
.connection-list { max-height: 220px; overflow-y: auto; border-top: 1px solid var(--judge-rule); }
.connection-row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 12px; width: 100%; min-height: 38px; padding: 6px 12px; border-top: 1px solid var(--judge-rule); text-align: left; font-size: 12px; }
.connection-row:first-child { border-top: 0; }
.connection-row:hover, .connection-row:focus-visible { background: var(--judge-hover); }
.connection-address { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--font-mono, monospace); }
.connection-state { color: var(--ink-secondary); white-space: nowrap; }
.connection-state.is-done { color: #17613a; }
.connection-state.is-error { color: #9f3037; }
.connection-open { font-weight: 700; white-space: nowrap; }
@container judges (max-width: 560px) { .connection-row { grid-template-columns: minmax(0, 1fr) auto; } .connection-open { display: none; } }
.pool-heading { display: flex; align-items: baseline; justify-content: flex-start; flex-wrap: nowrap; gap: 8px; padding: 10px 14px; border-right: 1px solid var(--judge-rule); white-space: nowrap; }
.node-panel-head { padding: 14px 18px; }
.pool-heading h2, .node-panel-title h2 { margin: 0; font-size: 17px; line-height: 1.4; }
.pool-heading h2 { font-size: 14px; }
.pool-total { color: var(--ink-secondary); font-size: 11px; font-variant-numeric: tabular-nums; }
.pool-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); }
.pool-cell {
  position: relative;
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 10px 12px;
  border-right: 1px solid var(--judge-rule);
  text-align: left;
  font-size: 12px;
}
.pool-cell:last-child { border-right: none; }
.pool-cell:hover, .pool-cell[aria-pressed='true'] { background: var(--judge-hover); }
.pool-cell[aria-pressed='true'] { box-shadow: inset 0 -2px var(--ink); }
.pool-cell:focus-visible { outline-offset: -3px; }
.pool-cell-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pool-dot { width: 6px; height: 6px; background: #9299a1; flex: 0 0 6px; }
.pool-dot.is-healthy { background: #187349; }
.pool-cell-count { margin-left: auto; color: var(--ink-secondary); font-size: 11px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.pool-cell-count strong { color: var(--ink); font-size: 12px; }

.node-panel-head { min-height: 64px; }
.count-tag { background: var(--surface-hover); color: var(--ink-secondary); padding: 2px 7px; font-size: 12px; font-variant-numeric: tabular-nums; }
.refresh-group { font-size: 12px; color: var(--ink-secondary); font-variant-numeric: tabular-nums; }
.btn-refresh { min-height: 34px; color: var(--ink); }
.is-spinning { animation: judge-refresh .75s linear infinite; }
@keyframes judge-refresh { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .is-spinning { animation: none; } }

.filter-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 0 18px 14px; }
.node-search { display: flex; align-items: center; gap: 8px; flex: 1 1 205px; max-width: 340px; min-width: 0; padding: 0 9px; border: 1px solid var(--judge-rule); background: var(--judge-panel); }
.node-search input { width: 100%; min-width: 0; border: 0; background: transparent; color: var(--ink); min-height: 36px; font: inherit; font-size: 13px; outline: none; }
.node-search:focus-within { outline: 2px solid var(--ink); outline-offset: -2px; }
.filter-select { max-width: 100%; min-height: 38px; padding: 4px 8px; border: 1px solid var(--judge-rule); border-radius: 0; background: var(--judge-panel); color: var(--ink); font: inherit; font-size: 13px; }
.filter-clear { min-height: 38px; padding-inline: 8px; font-size: 13px; text-decoration: underline; text-underline-offset: 3px; }
.error-banner, .table-message { border-top: 1px solid var(--judge-rule); padding: 16px 18px; }
.error-banner { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; background: #fdf0ef; color: #8b242b; }
.table-message { display: grid; justify-items: center; gap: 12px; padding: 32px 16px; color: var(--ink-secondary); font-size: 14px; }

.node-table { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: 13px; }
.col-node { width: 33%; }
.col-status { width: 19%; }
.col-slots { width: 14%; }
.col-time { width: 20%; }
.col-actions { width: 14%; }
.node-table :is(th, td) { padding: 10px 14px; text-align: left; border-top: 1px solid var(--judge-rule); vertical-align: middle; }
.node-table thead th { background: var(--surface); color: var(--ink-secondary); font-size: 12px; font-weight: 600; }
.node-table tbody tr:focus-within, .node-table tbody tr:hover { background: #f6f7f9; }
.node-name { display: block; max-width: 100%; padding: 0; text-align: left; font-size: 14px; font-weight: 700; overflow-wrap: anywhere; }
.node-name:hover { text-decoration: underline; text-underline-offset: 3px; }
.node-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; margin-top: 4px; font-size: 12px; font-weight: 400; }
.host-name, .runtime-version { color: var(--ink-secondary); overflow-wrap: anywhere; }
.pool-badge { padding: 0 5px; background: var(--surface-hover); color: var(--ink); font-size: 11px; font-weight: 650; }
.status-badge { display: inline-flex; align-items: center; gap: 5px; padding: 1px 6px; background: #edf0f2; color: #3f474f; font-size: 12px; font-weight: 650; }
.status-badge::before { content: ''; width: 5px; height: 5px; background: currentColor; }
.badge-healthy { background: #e6f3eb; color: #145b38; }
.badge-unhealthy { background: #f8e9df; color: #813807; }
.badge-offline { background: #f8e9ea; color: #8c3035; }
.state-label, .runtime-version { display: block; margin-top: 4px; font-size: 12px; color: var(--ink-secondary); }
.node-heartbeat, .slot-number { font-variant-numeric: tabular-nums; }
.node-heartbeat time { overflow-wrap: anywhere; }
.node-slots progress { display: block; appearance: none; width: min(100%, 76px); height: 4px; margin-top: 5px; border: 0; border-radius: 0; background: #e4e7eb; color: #34383e; }
.node-slots progress::-webkit-progress-bar { background: #e4e7eb; }
.node-slots progress::-webkit-progress-value { background: #34383e; }
.node-slots progress::-moz-progress-bar { background: #34383e; }

.node-table .row-operations { text-align: right; }
.actions-group { display: flex; align-items: center; justify-content: flex-end; gap: 6px; }
.action-btn, .actions-disclosure summary { display: inline-grid; place-items: center; min-height: 34px; padding: 3px 7px; border: 1px solid transparent; font-size: 13px; white-space: nowrap; cursor: pointer; }
.action-btn:hover, .actions-disclosure summary:hover, .actions-disclosure[open] summary { background: var(--judge-hover); border-color: var(--judge-rule); }
.actions-disclosure { position: relative; }
.actions-disclosure summary { width: 34px; padding: 0; list-style: none; }
.actions-disclosure summary::-webkit-details-marker { display: none; }
.actions-disclosure summary svg { width: 19px; height: 19px; }
.actions-popover { position: absolute; top: calc(100% + 4px); right: 0; z-index: 5; display: grid; min-width: 138px; padding: 4px; border: 1px solid var(--judge-rule); background: var(--judge-panel); box-shadow: var(--shadow-md); }
.actions-popover .action-btn { justify-items: start; }
.action-remove { color: #912d34; }
.action-remove:hover { color: #791e26; background: #f8e9ea; }
.action-hint { padding: 7px; color: var(--ink-secondary); font-size: 11px; white-space: nowrap; }
.actions-popover .btn-danger { color: #972b34; }
.actions-popover .btn-danger:hover { background: #f8e9ea; }

@container judges (max-width: 780px) {
  .node-table, .node-table tbody { display: block; }
  .node-table colgroup { display: none; }
  .node-table thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
  .node-table tbody tr { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px 16px; padding: 14px; border-top: 1px solid var(--judge-rule); }
  .node-table tbody :is(th, td) { min-width: 0; padding: 0; border: 0; }
  .node-identity { grid-column: 1; }
  .node-table .node-status { grid-column: 2; text-align: right; }
  .node-slots, .node-heartbeat { grid-row: 2; }
  .node-table [data-label]::before { content: attr(data-label); display: block; color: var(--ink-secondary); font-size: 11px; }
  .node-table .node-heartbeat { text-align: right; }
  .node-table .row-operations { grid-column: 1 / -1; }
  .actions-group { justify-content: flex-end; }
}
@container judges (max-width: 470px) {
  .pool-overview { display: block; }
  .pool-heading { padding: 9px 12px; border-right: 0; border-bottom: 1px solid var(--judge-rule); }
  .pool-grid { overflow-x: auto; grid-template-columns: repeat(5, minmax(105px, 1fr)); }
  .pool-cell { padding: 9px 12px; }
  .node-panel-head { padding: 12px; }
  .filter-bar { padding-inline: 12px; }
  .node-search { flex-basis: 100%; max-width: none; }
  .filter-select { flex: 1; min-width: 0; }
  .node-table tbody tr { grid-template-columns: minmax(0, 1fr) minmax(0, 1.65fr) auto; gap: 9px 10px; }
  .node-identity { grid-column: 1 / 3; }
  .node-table .node-status { grid-column: 3; }
  .node-table .row-operations { grid-area: 2 / 3; align-self: end; }
  .action-detail { display: none; }
  .action-btn, .actions-disclosure summary { min-height: 44px; }
  .actions-disclosure summary { width: 44px; }
}

.detail-endpoint { display: grid; gap: 6px; margin: 12px 0 0; font-size: 13px; }
.detail-endpoint div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 8px; }
.detail-endpoint dt { color: var(--ink-secondary); }
.detail-endpoint dd { margin: 0; overflow-wrap: anywhere; }

/* 节点操作弹窗 */
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 16px;
}

.modal-box {
  background: var(--judge-panel);
  border: 1px solid var(--line);
  border-radius: 0;
  width: 100%;
  max-width: 520px;
  max-height: min(90dvh, 900px);
  display: flex;
  flex-direction: column;
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.16);
}

.modal-box.modal-lg {
  max-width: 820px;
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line, #e2e8f0);
  background: #fafafa;
}

.modal-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink, #0f172a);
}

.modal-close {
  background: transparent;
  border: none;
  font-size: 20px;
  cursor: pointer;
  color: var(--ink-tertiary, #94a3b8);
  line-height: 1;
}

.modal-close:hover {
  color: var(--ink, #0f172a);
}

.modal-body {
  padding: 20px;
  overflow-y: auto;
}

.modal-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--line, #e2e8f0);
  background: #fafafa;
}

.form-input {
  width: 100%;
  padding: 8px 10px;
  font-size: 13px;
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 0;
  color: var(--ink, #0f172a);
}

.form-input:focus {
  border-color: #0f172a;
  outline: none;
}

.form-error-msg {
  color: #b91c1c;
  font-size: 12px;
  margin-top: 8px;
}

.connect-modal { max-width: 600px; }
.connect-intro { margin: 0 0 18px; color: var(--ink-secondary); font-size: 14px; line-height: 1.6; }
.connect-progress { display: grid; gap: 8px; margin-top: 20px; padding: 16px; border: 1px solid var(--judge-rule); background: var(--surface); }
.connect-progress-label { color: var(--ink-secondary); font-size: 11px; font-family: var(--font-mono, monospace); letter-spacing: .04em; }
.connect-progress strong { font-size: 18px; }
.connect-progress strong.is-error { color: #9f3037; }
.connect-progress p { margin: 0; color: var(--ink-secondary); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.host-budget-note { margin: 0 0 14px; font-size: 13px; color: var(--ink-secondary); line-height: 1.5; }

.node-target-info {
  font-size: 12px;
  color: var(--ink-secondary, #64748b);
  margin-bottom: 14px;
  padding: 6px 10px;
  background: #f8fafc;
  border-left: 3px solid #0f172a;
}

/* 详情抽屉细节 */
.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid var(--line, #e2e8f0);
  margin-bottom: 20px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.meta-k {
  font-size: 10px;
  color: var(--ink-tertiary, #94a3b8);
}

.meta-v {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink, #0f172a);
}

.detail-section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-secondary, #64748b);
  margin-bottom: 8px;
  letter-spacing: 0.04em;
}

.empty-tip {
  font-size: 12px;
  color: var(--ink-tertiary, #94a3b8);
  padding: 12px 0;
}

.record-table-wrap {
  border: 1px solid var(--line, #e2e8f0);
  overflow-x: auto;
}

.record-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
}

.record-table th,
.record-table td {
  padding: 8px 10px;
  text-align: left;
  border-bottom: 1px solid var(--line, #f1f5f9);
}

.record-table th {
  background: #f8fafc;
  color: var(--ink-secondary, #64748b);
  font-weight: 600;
}

.record-table tbody tr:last-child td {
  border-bottom: none;
}

.text-detail {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 宿主机管理 */
.slots-input {
  max-width: 100px;
}

.mt-4 {
  margin-top: 16px;
}

.py-6 {
  padding-top: 24px;
  padding-bottom: 24px;
}

.text-center {
  text-align: center;
}

.font-bold {
  font-weight: 700;
}

.error-text {
  color: #b91c1c;
}

.host-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.btn-delete {
  color: #c53030 !important;
  border-color: #feb2b2 !important;
}

.btn-delete:hover:not(:disabled) {
  background: #fff5f5 !important;
  border-color: #fc8181 !important;
}

.btn-delete:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.count-empty {
  color: var(--ink-secondary, #94a3b8) !important;
}
</style>
