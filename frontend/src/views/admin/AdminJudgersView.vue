<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  judgersApi,
  type HostGroupView,
  type JudgeNodeView,
  type JudgeNodeDetail,
  type EnrollmentResult,
} from '@/api/judgers'
import { ApiError } from '@/api/request'
import DataTable from '@/components/DataTable.vue'
import FormField from '@/components/FormField.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'

const { confirm } = useConfirm()
const toast = useToast()

// ---- 列表状态 ----
const nodes = ref<JudgeNodeView[]>([])
const hostGroups = ref<HostGroupView[]>([])
const loading = ref(false)
const loadError = ref('')

// ---- 过滤筛选 ----
const filterPool = ref('')
const filterState = ref('')
const filterHealth = ref('')

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
  { value: 'ENABLED', label: '已启用 (ENABLED)' },
  { value: 'DRAINING', label: '排空中 (DRAINING)' },
  { value: 'DISABLED', label: '已停用 (DISABLED)' },
  { value: 'PENDING_VERIFICATION', label: '待验证 (PENDING)' },
]

const healthOptions = [
  { value: '', label: '全部健康状态' },
  { value: 'HEALTHY', label: '正常 (HEALTHY)' },
  { value: 'DEGRADED', label: '降级 (DEGRADED)' },
  { value: 'OFFLINE', label: '离线 (OFFLINE)' },
  { value: 'UNVERIFIED', label: '未自检 (UNVERIFIED)' },
  { value: 'UNHEALTHY', label: '异常 (UNHEALTHY)' },
]

const columns = [
  { key: 'nodeId', label: 'ID', width: '70px' },
  { key: 'name', label: '节点名称', width: '150px' },
  { key: 'pool', label: '专池', width: '90px' },
  { key: 'hostGroupId', label: '宿主机', width: '110px' },
  { key: 'state', label: '配置状态', width: '100px' },
  { key: 'health', label: '健康', width: '90px' },
  { key: 'slots', label: '槽位占用', width: '100px' },
  { key: 'runtime', label: '运行时版本' },
  { key: 'heartbeatAt', label: '最近心跳', width: '160px' },
]

const hostGroupMap = computed(() => {
  const map = new Map<string, string>()
  for (const h of hostGroups.value) {
    map.set(String(h.id), h.name)
  }
  return map
})

const filteredNodes = computed(() => {
  return nodes.value.filter((n) => {
    if (filterPool.value && n.pool.toLowerCase() !== filterPool.value.toLowerCase()) return false
    if (filterState.value && n.state !== filterState.value) return false
    if (filterHealth.value && n.health !== filterHealth.value) return false
    return true
  })
})

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const [nodeList, hostList] = await Promise.all([
      judgersApi.getNodes({ limit: 100 }),
      judgersApi.getHostGroups(),
    ])
    nodes.value = nodeList
    hostGroups.value = hostList
  } catch (err: unknown) {
    loadError.value = err instanceof Error ? err.message : '加载评测机列表失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadData()
})

// ---- 格式化辅助 ----
function formatTime(iso: string | null | undefined): string {
  if (!iso) return '--'
  return iso.replace('T', ' ').slice(0, 19)
}

function poolClass(pool: string): string {
  switch (pool.toLowerCase()) {
    case 'cpp':
      return 'pool-badge-cpp'
    case 'java':
      return 'pool-badge-java'
    case 'python':
      return 'pool-badge-python'
    case 'mysql':
      return 'pool-badge-mysql'
    case 'arkts':
      return 'pool-badge-arkts'
    default:
      return 'pool-badge-default'
  }
}

function stateClass(state: string): string {
  switch (state) {
    case 'ENABLED':
      return 'badge-enabled'
    case 'DRAINING':
      return 'badge-draining'
    case 'DISABLED':
      return 'badge-disabled'
    case 'PENDING_VERIFICATION':
    default:
      return 'badge-pending'
  }
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

// ---- 新建节点弹窗 ----
const showRegisterModal = ref(false)
const regName = ref('')
const regPool = ref('python')
const regHostGroupId = ref('')
const regMaxSlots = ref(4)
const regBaseUrl = ref('')
const regSubmitting = ref(false)
const regError = ref('')

// 凭据结果弹窗
const showSecretModal = ref(false)
const secretResult = ref<EnrollmentResult | null>(null)
const copied = ref(false)

function openRegisterModal() {
  regName.value = ''
  regPool.value = 'python'
  regHostGroupId.value = hostGroups.value[0]?.id ? String(hostGroups.value[0].id) : ''
  regMaxSlots.value = 4
  regBaseUrl.value = ''
  regError.value = ''
  showRegisterModal.value = true
}

async function submitRegister() {
  if (!regName.value.trim()) {
    regError.value = '请输入节点名称'
    return
  }
  if (!regHostGroupId.value) {
    regError.value = '请选择所属宿主机分组'
    return
  }
  if (regMaxSlots.value < 1 || regMaxSlots.value > 64) {
    regError.value = '槽位数必须在 1 到 64 之间'
    return
  }

  regSubmitting.value = true
  regError.value = ''
  try {
    const res = await judgersApi.registerNode({
      name: regName.value.trim(),
      pool: regPool.value,
      hostGroupId: regHostGroupId.value,
      maxSlots: regMaxSlots.value,
      baseUrl: regBaseUrl.value.trim() || undefined,
    })
    showRegisterModal.value = false
    secretResult.value = res
    showSecretModal.value = true
    loadData()
  } catch (err: unknown) {
    regError.value = err instanceof ApiError ? err.message : '注册节点失败'
  } finally {
    regSubmitting.value = false
  }
}

function copyEnrollmentCode() {
  if (!secretResult.value?.enrollmentCode) return
  navigator.clipboard.writeText(secretResult.value.enrollmentCode)
  copied.value = true
  toast.success('接入码已复制到剪贴板')
  setTimeout(() => {
    copied.value = false
  }, 2000)
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

// ---- 重置接入码 ----
async function handleResetEnrollment(node: JudgeNodeView) {
  const ok = await confirm({
    title: '重新生成接入码',
    message: `重新生成接入码将重置节点 ${node.name} 的会话代次并断开当前连接，是否继续？`,
    danger: true,
  })
  if (!ok) return

  try {
    const res = await judgersApi.resetEnrollment(node.nodeId, node.revision)
    secretResult.value = res
    showSecretModal.value = true
    loadData()
  } catch (err: unknown) {
    toast.error(err instanceof ApiError ? err.message : '重置接入码失败')
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
const newHostName = ref('')
const newHostSlots = ref(16)
const hostSubmitting = ref(false)
const hostError = ref('')

function openHostModal() {
  newHostName.value = ''
  newHostSlots.value = 16
  hostError.value = ''
  showHostModal.value = true
}

async function submitCreateHost() {
  if (!newHostName.value.trim()) {
    hostError.value = '请输入宿主机分组名称'
    return
  }
  if (newHostSlots.value < 1 || newHostSlots.value > 256) {
    hostError.value = '槽位数必须在 1 到 256 之间'
    return
  }

  hostSubmitting.value = true
  hostError.value = ''
  try {
    await judgersApi.createHostGroup({
      name: newHostName.value.trim(),
      maxSlots: newHostSlots.value,
    })
    toast.success('已创建宿主机分组')
    newHostName.value = ''
    newHostSlots.value = 16
    const list = await judgersApi.getHostGroups()
    hostGroups.value = list
  } catch (err: unknown) {
    hostError.value = err instanceof ApiError ? err.message : '创建宿主机分组失败'
  } finally {
    hostSubmitting.value = false
  }
}
</script>

<template>
  <div class="admin-judgers-view container">
    <!-- 页头与操作栏 -->
    <header class="page-head">
      <div class="head-info">
        <h1 class="page-title">评测机管理</h1>
        <p class="page-desc">
          管理 C++、Java、Python、MySQL、ArkTS 五大独立评测专池，控制宿主机行锁配额与节点入网生命周期。
        </p>
      </div>

      <div class="head-actions">
        <button class="btn btn-outline mono" @click="openHostModal">
          <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="2" y="2" width="20" height="8" rx="0" ry="0" />
            <rect x="2" y="14" width="20" height="8" rx="0" ry="0" />
            <line x1="6" y1="6" x2="6.01" y2="6" />
            <line x1="6" y1="18" x2="6.01" y2="18" />
          </svg>
          宿主机分组 ({{ hostGroups.length }})
        </button>

        <button class="btn btn-primary mono" @click="openRegisterModal">
          <svg class="btn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          登记评测节点
        </button>
      </div>
    </header>

    <!-- 筛选工具条 -->
    <div class="filter-bar">
      <div class="filter-left">
        <select v-model="filterPool" class="filter-select mono" aria-label="按专池筛选">
          <option v-for="opt in poolOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>

        <select v-model="filterState" class="filter-select mono" aria-label="按配置状态筛选">
          <option v-for="opt in stateOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>

        <select v-model="filterHealth" class="filter-select mono" aria-label="按健康状态筛选">
          <option v-for="opt in healthOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </div>

      <div class="filter-right">
        <span class="count-tag mono">TOTAL: {{ filteredNodes.length }} / {{ nodes.length }}</span>
        <button class="btn-refresh mono" :disabled="loading" title="刷新列表" @click="loadData">
          <svg class="refresh-icon" :class="{ 'is-spinning': loading }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
          </svg>
        </button>
      </div>
    </div>

    <!-- 错误横条 -->
    <div v-if="loadError" class="error-banner mono">
      <span>{{ loadError }}</span>
      <button class="btn-retry" @click="loadData">重试</button>
    </div>

    <!-- 节点数据表格 -->
    <div class="table-container">
      <DataTable
        :columns="columns"
        :rows="filteredNodes"
        row-key="nodeId"
        :loading="loading"
        empty-text="暂无匹配的评测节点"
      >
        <!-- ID 列 -->
        <template #cell-nodeId="{ row }">
          <span class="mono node-id">{{ row.nodeId }}</span>
        </template>

        <!-- 节点名称 -->
        <template #cell-name="{ row }">
          <div class="node-name-cell">
            <span class="node-name">{{ row.name }}</span>
            <span v-if="row.baseUrl" class="node-url mono" :title="row.baseUrl">
              {{ row.baseUrl }}
            </span>
          </div>
        </template>

        <!-- 专池 -->
        <template #cell-pool="{ row }">
          <span class="pool-badge mono" :class="poolClass(row.pool)">
            {{ row.pool.toUpperCase() }}
          </span>
        </template>

        <!-- 宿主机 -->
        <template #cell-hostGroupId="{ row }">
          <span class="host-group-tag mono">
            {{ hostGroupMap.get(String(row.hostGroupId)) || `HOST-${row.hostGroupId}` }}
          </span>
        </template>

        <!-- 配置状态 -->
        <template #cell-state="{ row }">
          <span class="status-badge mono" :class="stateClass(row.state)">
            {{ row.state }}
          </span>
        </template>

        <!-- 健康 -->
        <template #cell-health="{ row }">
          <span class="status-badge mono" :class="healthClass(row.health)">
            {{ row.health }}
          </span>
        </template>

        <!-- 槽位 -->
        <template #cell-slots="{ row }">
          <span class="mono slots-indicator" :class="{ 'has-load': row.running > 0 }">
            {{ row.running }} / {{ row.maxSlots }}
          </span>
        </template>

        <!-- 运行时 -->
        <template #cell-runtime="{ row }">
          <span class="mono runtime-info" :title="row.runtimeVersion || row.runtimeSpec">
            {{ row.runtimeVersion || row.runtimeSpec || '--' }}
          </span>
        </template>

        <!-- 最近心跳 -->
        <template #cell-heartbeatAt="{ row }">
          <span class="mono time-cell">{{ formatTime(row.heartbeatAt) }}</span>
        </template>

        <!-- 操作区 -->
        <template #actions="{ row }">
          <div class="actions-group mono">
            <button class="action-btn" title="查看自检与执行历史" @click="openDetailModal(row)">
              详情
            </button>
            <button class="action-btn" title="下发沙箱自检" @click="handleCheck(row)">
              自检
            </button>
            <button class="action-btn" title="修改配置与槽位" @click="openEditModal(row)">
              调配
            </button>
            <button class="action-btn" title="重新生成接入码" @click="handleResetEnrollment(row)">
              重配
            </button>
            <button
              v-if="row.state !== 'DISABLED'"
              class="action-btn btn-danger"
              title="注销凭据并停用"
              @click="handleRevoke(row)"
            >
              注销
            </button>
          </div>
        </template>
      </DataTable>
    </div>

    <!-- 弹窗 1：登记节点 -->
    <div v-if="showRegisterModal" class="modal-backdrop" @click.self="showRegisterModal = false">
      <div class="modal-box">
        <div class="modal-head">
          <h2 class="modal-title">登记评测机节点</h2>
          <button class="modal-close" @click="showRegisterModal = false">&times;</button>
        </div>

        <div class="modal-body">
          <FormField label="节点名称" required hint="在集群中唯一定位此节点，建议体现机型或机架号">
            <input v-model="regName" class="form-input" placeholder="例如: py-worker-01" />
          </FormField>

          <FormField label="专评池 (Pool)" required hint="节点专职处理该语言/环境的提交，不可混跑">
            <select v-model="regPool" class="form-input mono">
              <option value="python">python - Python 独立专池</option>
              <option value="cpp">cpp - C++ 独立专池</option>
              <option value="java">java - Java 独立专池</option>
              <option value="mysql">mysql - MySQL 独立专池</option>
              <option value="arkts">arkts - ArkTS 独立专池</option>
            </select>
          </FormField>

          <FormField label="所属宿主机分组" required hint="行锁控制宿主机总并发配额，杜绝超卖">
            <select v-model="regHostGroupId" class="form-input mono">
              <option v-for="h in hostGroups" :key="h.id" :value="String(h.id)">
                {{ h.name }} (最大配额: {{ h.maxSlots }} 槽)
              </option>
            </select>
          </FormField>

          <FormField label="本地槽位上限 (Max Slots)" required hint="该进程并发评测容器上限 (1 ~ 64)">
            <input v-model.number="regMaxSlots" type="number" min="1" max="64" class="form-input mono" />
          </FormField>

          <FormField label="节点 API 基址 (可选)" hint="用于控制面主动探测健康，如: https://192.168.1.10:9099">
            <input v-model="regBaseUrl" class="form-input mono" placeholder="https://..." />
          </FormField>

          <div v-if="regError" class="form-error-msg mono">{{ regError }}</div>
        </div>

        <div class="modal-foot">
          <button class="btn btn-secondary mono" @click="showRegisterModal = false">取消</button>
          <button class="btn btn-primary mono" :disabled="regSubmitting" @click="submitRegister">
            {{ regSubmitting ? '正在登记...' : '确认登记并生成接入码' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 弹窗 2：凭据展示与接入码 -->
    <div v-if="showSecretModal" class="modal-backdrop" @click.self="showSecretModal = false">
      <div class="modal-box">
        <div class="modal-head">
          <h2 class="modal-title">评测机入网凭据</h2>
          <button class="modal-close" @click="showSecretModal = false">&times;</button>
        </div>

        <div class="modal-body">
          <div class="secret-alert">
            <svg class="alert-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>
            <div>
              <p class="alert-text">接入码具有时效性且仅展示一次，请妥善保管并填入节点启动配置。</p>
            </div>
          </div>

          <div class="secret-field">
            <label class="secret-label mono">NODE ID:</label>
            <div class="secret-val mono">{{ secretResult?.nodeId }}</div>
          </div>

          <div class="secret-field">
            <label class="secret-label mono">ENROLLMENT CODE (接入码):</label>
            <div class="code-box mono">
              <code>{{ secretResult?.enrollmentCode }}</code>
              <button class="btn-copy mono" @click="copyEnrollmentCode">
                {{ copied ? '已复制' : '复制' }}
              </button>
            </div>
          </div>

          <div class="secret-tip mono">
            有效时间: {{ secretResult?.expiresInSeconds }} 秒 · 过期未激活需在后台重置
          </div>
        </div>

        <div class="modal-foot">
          <button class="btn btn-primary mono" @click="showSecretModal = false">完成并关闭</button>
        </div>
      </div>
    </div>

    <!-- 弹窗 3：调配状态与槽位 -->
    <div v-if="showEditModal" class="modal-backdrop" @click.self="showEditModal = false">
      <div class="modal-box">
        <div class="modal-head">
          <h2 class="modal-title">调配节点状态与槽位</h2>
          <button class="modal-close" @click="showEditModal = false">&times;</button>
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
      <div class="modal-box modal-lg">
        <div class="modal-head">
          <h2 class="modal-title">
            节点详情 · {{ nodeDetail?.node?.name || '加载中...' }}
          </h2>
          <button class="modal-close" @click="showDetailModal = false">&times;</button>
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
              <span class="meta-k">NODE ID:</span>
              <span class="meta-v">{{ nodeDetail.node.nodeId }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">POOL:</span>
              <span class="meta-v">{{ nodeDetail.node.pool }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">STATE:</span>
              <span class="meta-v">{{ nodeDetail.node.state }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">HEALTH:</span>
              <span class="meta-v">{{ nodeDetail.node.health }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">SLOTS:</span>
              <span class="meta-v">{{ nodeDetail.node.running }} / {{ nodeDetail.node.maxSlots }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-k">REVISION:</span>
              <span class="meta-v">{{ nodeDetail.node.revision }}</span>
            </div>
          </div>

          <!-- 自检记录 -->
          <div class="detail-section">
            <h3 class="section-title mono">// 沙箱自检记录 (RECENT CHECKS)</h3>
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
            <h3 class="section-title mono">// 近期执行任务 (RECENT ATTEMPTS)</h3>
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
            <h3 class="section-title mono">// 审计追踪 (AUDIT TRAIL)</h3>
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

    <!-- 弹窗 5：宿主机分组管理 -->
    <div v-if="showHostModal" class="modal-backdrop" @click.self="showHostModal = false">
      <div class="modal-box modal-lg">
        <div class="modal-head">
          <h2 class="modal-title">宿主机分组与槽位配额</h2>
          <button class="modal-close" @click="showHostModal = false">&times;</button>
        </div>

        <div class="modal-body">
          <div class="host-create-bar">
            <div class="host-input-group">
              <input v-model="newHostName" class="form-input" placeholder="分组名称 (如: baremetal-srv-01)" />
              <input v-model.number="newHostSlots" type="number" min="1" max="256" class="form-input mono slots-input" placeholder="总槽位" />
              <button class="btn btn-primary mono" :disabled="hostSubmitting" @click="submitCreateHost">
                {{ hostSubmitting ? '添加中...' : '新建宿主机分组' }}
              </button>
            </div>
            <div v-if="hostError" class="form-error-msg mono">{{ hostError }}</div>
          </div>

          <div class="record-table-wrap mt-4">
            <table class="record-table mono">
              <thead>
                <tr>
                  <th>GROUP ID</th>
                  <th>分组名称</th>
                  <th>最大并发槽位配额</th>
                  <th>版本号</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="h in hostGroups" :key="h.id">
                  <td>{{ h.id }}</td>
                  <td class="font-bold">{{ h.name }}</td>
                  <td>{{ h.maxSlots }} SLOTS</td>
                  <td>REV: {{ h.revision }}</td>
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
  padding-top: 24px;
  padding-bottom: 48px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 24px;
  gap: 16px;
  flex-wrap: wrap;
}

.page-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink, #0f172a);
  margin-bottom: 6px;
}

.page-desc {
  font-size: 13px;
  color: var(--ink-secondary, #64748b);
  max-width: 680px;
  line-height: 1.5;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  border-radius: 0;
  border: 1px solid transparent;
  transition: all 0.15s ease;
}

.btn-primary {
  background: #0f172a;
  color: #ffffff;
  border-color: #0f172a;
}

.btn-primary:hover:not(:disabled) {
  background: #1e293b;
}

.btn-outline {
  background: #ffffff;
  color: var(--ink, #0f172a);
  border-color: var(--line, #e2e8f0);
}

.btn-outline:hover {
  background: #f8fafc;
}

.btn-secondary {
  background: #f1f5f9;
  color: var(--ink-secondary, #64748b);
}

.btn-secondary:hover {
  background: #e2e8f0;
}

.btn-icon {
  width: 14px;
  height: 14px;
}

/* 筛选条 */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 12px;
  flex-wrap: wrap;
  background: #ffffff;
  border: 1px solid var(--line, #e2e8f0);
  padding: 10px 14px;
}

.filter-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-select {
  padding: 6px 10px;
  font-size: 12px;
  border: 1px solid var(--line, #e2e8f0);
  background: #ffffff;
  border-radius: 0;
  color: var(--ink, #0f172a);
}

.filter-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.count-tag {
  font-size: 11px;
  color: var(--ink-tertiary, #94a3b8);
  font-weight: 600;
}

.btn-refresh {
  background: transparent;
  border: 1px solid var(--line, #e2e8f0);
  padding: 5px;
  cursor: pointer;
  color: var(--ink-secondary, #64748b);
  display: flex;
  align-items: center;
  justify-content: center;
}

.refresh-icon {
  width: 14px;
  height: 14px;
}

.refresh-icon.is-spinning {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.error-banner {
  padding: 10px 14px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.btn-retry {
  background: #b91c1c;
  color: #ffffff;
  border: none;
  padding: 4px 10px;
  font-size: 11px;
  cursor: pointer;
}

/* 表格容器 */
.table-container {
  background: #ffffff;
  border: 1px solid var(--line, #e2e8f0);
  overflow-x: auto;
}

.node-id {
  font-weight: 700;
  color: var(--ink-secondary, #64748b);
}

.node-name-cell {
  display: flex;
  flex-direction: column;
}

.node-name {
  font-weight: 600;
  color: var(--ink, #0f172a);
}

.node-url {
  font-size: 10px;
  color: var(--ink-tertiary, #94a3b8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
}

.pool-badge {
  display: inline-block;
  padding: 2px 6px;
  font-size: 10px;
  font-weight: 700;
  border-radius: 0;
  text-align: center;
}

.pool-badge-python {
  background: #3776ab;
  color: #ffffff;
}

.pool-badge-cpp {
  background: #00599c;
  color: #ffffff;
}

.pool-badge-java {
  background: #b07219;
  color: #ffffff;
}

.pool-badge-mysql {
  background: #00618a;
  color: #ffffff;
}

.pool-badge-arkts {
  background: #c7254e;
  color: #ffffff;
}

.pool-badge-default {
  background: #334155;
  color: #ffffff;
}

.host-group-tag {
  font-size: 11px;
  color: var(--ink-secondary, #64748b);
}

.status-badge {
  display: inline-block;
  padding: 2px 6px;
  font-size: 10px;
  font-weight: 700;
  border: 1px solid transparent;
}

.badge-enabled {
  background: #ecfdf5;
  color: #047857;
  border-color: #a7f3d0;
}

.badge-draining {
  background: #fffbeb;
  color: #b45309;
  border-color: #fde68a;
}

.badge-disabled {
  background: #f1f5f9;
  color: #64748b;
  border-color: #cbd5e1;
}

.badge-pending {
  background: #f8fafc;
  color: #475569;
  border-color: #cbd5e1;
}

.badge-healthy {
  background: #ecfdf5;
  color: #047857;
  border-color: #a7f3d0;
}

.badge-offline {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}

.badge-unhealthy {
  background: #fff7ed;
  color: #c2410c;
  border-color: #fed7aa;
}

.badge-unverified {
  background: #f1f5f9;
  color: #64748b;
  border-color: #cbd5e1;
}

.slots-indicator {
  font-size: 12px;
  color: var(--ink-tertiary, #94a3b8);
}

.slots-indicator.has-load {
  color: #0f172a;
  font-weight: 700;
}

.runtime-info {
  font-size: 11px;
  color: var(--ink-secondary, #64748b);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

.time-cell {
  font-size: 11px;
  color: var(--ink-tertiary, #94a3b8);
}

.actions-group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.action-btn {
  background: transparent;
  border: 1px solid var(--line, #e2e8f0);
  padding: 3px 6px;
  font-size: 11px;
  color: var(--ink, #0f172a);
  cursor: pointer;
  border-radius: 0;
  transition: all 0.1s ease;
}

.action-btn:hover {
  background: #f1f5f9;
}

.action-btn.btn-danger {
  color: #b91c1c;
  border-color: #fecaca;
}

.action-btn.btn-danger:hover {
  background: #fef2f2;
}

/* 弹窗通用工业风设计 */
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 16px;
}

.modal-box {
  background: #ffffff;
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 0;
  width: 100%;
  max-width: 520px;
  max-height: 90vh;
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

/* 凭据弹窗细节 */
.secret-alert {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  padding: 10px 14px;
  margin-bottom: 16px;
}

.alert-icon {
  width: 18px;
  height: 18px;
  color: #b45309;
  flex-shrink: 0;
}

.alert-text {
  font-size: 12px;
  color: #92400e;
  line-height: 1.4;
}

.secret-field {
  margin-bottom: 12px;
}

.secret-label {
  font-size: 11px;
  color: var(--ink-tertiary, #94a3b8);
  margin-bottom: 4px;
  display: block;
}

.secret-val {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink, #0f172a);
}

.code-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8fafc;
  border: 1px dashed var(--line, #cbd5e1);
  padding: 8px 12px;
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.btn-copy {
  background: #0f172a;
  color: #ffffff;
  border: none;
  font-size: 11px;
  padding: 4px 8px;
  cursor: pointer;
}

.btn-copy:hover {
  background: #1e293b;
}

.secret-tip {
  font-size: 11px;
  color: var(--ink-tertiary, #94a3b8);
  margin-top: 10px;
}

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
.host-create-bar {
  background: #f8fafc;
  border: 1px solid var(--line, #e2e8f0);
  padding: 14px;
}

.host-input-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

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
</style>
