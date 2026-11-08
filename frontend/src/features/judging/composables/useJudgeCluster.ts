import {
  computed,
  inject,
  onBeforeUnmount,
  onMounted,
  provide,
  ref,
  type ComputedRef,
  type InjectionKey,
  type Ref,
} from 'vue'
import { systemApi, type JudgeClusterSummary, type PoolSummary } from '@/api/system'

export interface JudgeClusterState {
  summary: Ref<JudgeClusterSummary | null>
  loading: Ref<boolean>
  initialLoading: Ref<boolean>
  error: Ref<string | null>
  isStale: ComputedRef<boolean>
  isUnavailable: ComputedRef<boolean>
  readyPools: ComputedRef<number>
  totalPools: ComputedRef<number>
  pools: ComputedRef<PoolSummary[]>
  refresh: (manual?: boolean) => Promise<void>
}

export const JUDGE_CLUSTER_KEY: InjectionKey<JudgeClusterState> = Symbol('JUDGE_CLUSTER_KEY')

export function createJudgeClusterState(intervalMs = 30000): JudgeClusterState {
  const summary = ref<JudgeClusterSummary | null>(null)
  const loading = ref(false)
  const initialLoading = ref(true)
  const error = ref<string | null>(null)

  let timer: ReturnType<typeof setInterval> | null = null
  let inFlight = false
  let requestSeq = 0
  let isUnmounted = false

  const readyPools = computed(() => summary.value?.readyPools ?? 0)
  const totalPools = computed(() => summary.value?.totalPools ?? 5)
  const pools = computed(() => summary.value?.pools ?? [])

  const isStale = computed(() => {
    if (!summary.value) return false
    if (summary.value.freshness === 'STALE') return true
    if (error.value !== null) return true
    // 超过 60 秒未获取到新快照则标记为过期
    return Date.now() - summary.value.asOf > 60000
  })

  const isUnavailable = computed(() => {
    return !summary.value && error.value !== null && !initialLoading.value
  })

  const fetchSummary = async (manual = false) => {
    if (inFlight) return
    inFlight = true
    if (manual) loading.value = true

    const currentSeq = ++requestSeq
    try {
      const res = await systemApi.getClusterSummary()
      if (isUnmounted || currentSeq !== requestSeq) return

      summary.value = res
      error.value = null
    } catch (err: unknown) {
      if (isUnmounted || currentSeq !== requestSeq) return
      error.value = err instanceof Error ? err.message : '获取评测集群状态失败'
    } finally {
      inFlight = false
      if (!isUnmounted && currentSeq === requestSeq) {
        initialLoading.value = false
        if (manual) loading.value = false
      }
    }
  }

  const startTimer = () => {
    stopTimer()
    timer = setInterval(() => {
      if (document.visibilityState === 'visible') {
        fetchSummary()
      }
    }, intervalMs)
  }

  const stopTimer = () => {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  const handleVisibilityChange = () => {
    if (document.visibilityState === 'visible') {
      fetchSummary()
      startTimer()
    } else {
      stopTimer()
    }
  }

  onMounted(() => {
    isUnmounted = false
    fetchSummary()
    startTimer()
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onBeforeUnmount(() => {
    isUnmounted = true
    stopTimer()
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  return {
    summary,
    loading,
    initialLoading,
    error,
    isStale,
    isUnavailable,
    readyPools,
    totalPools,
    pools,
    refresh: fetchSummary,
  }
}

export function provideJudgeCluster(intervalMs = 30000): JudgeClusterState {
  const state = createJudgeClusterState(intervalMs)
  provide(JUDGE_CLUSTER_KEY, state)
  return state
}

export function useJudgeCluster(): JudgeClusterState {
  const state = inject(JUDGE_CLUSTER_KEY, null)
  if (!state) {
    // 降级兜底：未在外层 provide 时单例生成独立状态
    return createJudgeClusterState()
  }
  return state
}
