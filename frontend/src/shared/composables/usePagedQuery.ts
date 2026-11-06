import { ref, reactive, type Ref } from 'vue'

export interface PagedResult<T> {
  list: T[]
  total: number
}

export interface PagedQueryOptions<T, P> {
  fetcher: (params: P & { page: number; size: number }) => Promise<PagedResult<T>>
  defaultParams?: P
  defaultSize?: number
  immediate?: boolean
}

export function usePagedQuery<T, P extends Record<string, unknown> = Record<string, unknown>>(
  options: PagedQueryOptions<T, P>,
) {
  const page = ref(1)
  const size = ref(options.defaultSize ?? 20)
  const params = reactive<P>({ ...(options.defaultParams as P) })
  const list = ref<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)

  let requestSeq = 0 // 自增请求序号，杜绝迟到响应串页（Race Condition 防御）

  async function load(targetPage?: number): Promise<void> {
    if (targetPage !== undefined) {
      page.value = targetPage
    }
    const currentSeq = ++requestSeq
    loading.value = true
    error.value = null
    try {
      const queryParams = {
        ...params,
        page: page.value,
        size: size.value,
      } as P & { page: number; size: number }
      const res = await options.fetcher(queryParams)
      if (currentSeq === requestSeq) {
        list.value = res.list
        total.value = res.total
        // 自动回退保护：删除最后一项后如果当前页超出最大页码，自动回退有效页
        const maxPage = Math.max(1, Math.ceil(res.total / size.value))
        if (page.value > maxPage && res.total > 0) {
          await load(maxPage)
        }
      }
    } catch (err: unknown) {
      if (currentSeq === requestSeq) {
        list.value = []
        total.value = 0
        error.value = err instanceof Error ? err.message : '加载列表失败'
      }
    } finally {
      if (currentSeq === requestSeq) {
        loading.value = false
      }
    }
  }

  function reset(): void {
    void load(1)
  }

  if (options.immediate ?? true) {
    void load()
  }

  return {
    page,
    size,
    params,
    list,
    total,
    loading,
    error,
    load,
    reset,
  }
}
