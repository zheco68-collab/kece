import { ref, watch, onBeforeUnmount, getCurrentInstance, type Ref } from 'vue'

export interface SubmissionMonitorOptions<T> {
  submissionId: Ref<number | null | undefined>
  fetcher: (id: number) => Promise<T>
  isTerminal: (result: T) => boolean
  onSuccess?: (result: T) => void
  intervalMs?: number // 默认 1500ms
  maxTimeoutMs?: number // 默认 120000ms (2分钟防挂死超时)
}

export function useSubmissionMonitor<T>(options: SubmissionMonitorOptions<T>) {
  const result = ref<T | null>(null) as Ref<T | null>
  const polling = ref(false)
  const error = ref<string | null>(null)

  let timer: ReturnType<typeof setTimeout> | null = null
  let activeId: number | null = null
  let startTime = 0
  let generation = 0

  const stop = () => {
    generation++
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    polling.value = false
  }

  const scheduleNext = () => {
    if (!polling.value) return

    const interval = options.intervalMs ?? 1500
    const maxTimeout = options.maxTimeoutMs ?? 120000

    timer = setTimeout(async () => {
      const currentTargetId = options.submissionId.value
      if (!currentTargetId || currentTargetId !== activeId || !polling.value) {
        stop()
        return
      }

      if (Date.now() - startTime > maxTimeout) {
        error.value = '评测耗时较长，请稍后在提交记录中查看结果'
        stop()
        return
      }

      const reqGen = generation
      const reqId = activeId

      try {
        const res = await options.fetcher(reqId)
        // 关键竞态防御：若在网络请求期间 generation 已变化，或切换了目标 ID，或已停止，丢弃迟到响应
        if (generation !== reqGen || !polling.value || options.submissionId.value !== reqId) {
          return
        }

        result.value = res
        if (options.isTerminal(res)) {
          stop()
          options.onSuccess?.(res)
        } else {
          // 仅当本次请求完整返回且未达到终态时，安排下一次轮询，杜绝慢网络下的请求堆叠
          scheduleNext()
        }
      } catch (err: unknown) {
        if (generation !== reqGen || !polling.value || options.submissionId.value !== reqId) {
          return
        }
        error.value = err instanceof Error ? err.message : '获取评测结果异常'
        stop()
      }
    }, interval)
  }

  watch(
    options.submissionId,
    (newId) => {
      stop()
      result.value = null
      error.value = null

      if (newId && typeof newId === 'number' && Number.isSafeInteger(newId) && newId > 0) {
        activeId = newId
        startTime = Date.now()
        polling.value = true
        const reqGen = generation
        const reqId = newId

        // 立即首查一次
        options
          .fetcher(newId)
          .then((res) => {
            if (generation !== reqGen || !polling.value || options.submissionId.value !== reqId) {
              return
            }
            result.value = res
            if (!options.isTerminal(res)) {
              scheduleNext()
            } else {
              stop()
              options.onSuccess?.(res)
            }
          })
          .catch((err: unknown) => {
            if (generation !== reqGen || !polling.value || options.submissionId.value !== reqId) {
              return
            }
            error.value = err instanceof Error ? err.message : '获取评测结果异常'
            stop()
          })
      } else {
        activeId = null
      }
    },
    { immediate: true },
  )

  if (getCurrentInstance()) {
    onBeforeUnmount(stop)
  }

  return {
    result,
    polling,
    error,
    stop,
  }
}

