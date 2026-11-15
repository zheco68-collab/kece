import { ref, watch, type Ref } from 'vue'
import * as vueRouter from 'vue-router'
import type { LocationQuery, RouteLocationNormalizedLoaded, Router } from 'vue-router'

export type BankStatusFilter = 'ALL' | 'UNSOLVED' | 'SOLVED'
export type BankQueryKey = 'keyword' | 'difficulty' | 'type' | 'tag' | 'status' | 'page'

export const BANK_QUERY_KEYS: readonly BankQueryKey[] = [
  'keyword',
  'difficulty',
  'type',
  'tag',
  'status',
  'page',
]

export interface BankQueryState<
  TDifficulty extends string = string,
  TType extends string = string,
> {
  keyword: string
  difficulty: TDifficulty | ''
  type: TType | ''
  tag: string
  status: BankStatusFilter
  page: number
}

export interface BankQueryRefs<
  TDifficulty extends string = string,
  TType extends string = string,
> {
  keyword: Ref<string>
  difficulty: Ref<TDifficulty | ''>
  type: Ref<TType | ''>
  tag: Ref<string>
  status: Ref<BankStatusFilter>
  page: Ref<number>
}

export interface ReadBankQueryOptions<
  TDifficulty extends string = string,
  TType extends string = string,
> {
  fields?: readonly BankQueryKey[]
  validDifficulties?: readonly TDifficulty[]
  validTypes?: readonly TType[]
}

export interface UseBankUrlSyncOptions<
  TDifficulty extends string = string,
  TType extends string = string,
> extends ReadBankQueryOptions<TDifficulty, TType> {
  refs?: Partial<BankQueryRefs<TDifficulty, TType>>
  autoSync?: boolean
}

function readSingleQueryString(value: unknown): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) {
    const first = value.find((item): item is string => typeof item === 'string')
    return first ?? ''
  }
  return ''
}

function readPositiveInt(value: unknown, fallback = 1): number {
  const raw = readSingleQueryString(value).trim()
  if (!/^[1-9]\d*$/.test(raw)) return fallback
  const parsed = Number(raw)
  return Number.isSafeInteger(parsed) && parsed >= 1 ? parsed : fallback
}

function readStatus(value: unknown): BankStatusFilter {
  const normalized = readSingleQueryString(value).trim().toUpperCase()
  if (normalized === 'UNSOLVED' || normalized === 'SOLVED' || normalized === 'ALL') {
    return normalized
  }
  return 'ALL'
}

function readAllowedValue<T extends string>(
  value: unknown,
  allowed?: readonly T[],
): T | '' {
  const raw = readSingleQueryString(value).trim()
  if (!raw) return ''
  if (!allowed?.length) return raw as T
  const matched = allowed.find((item) => item === raw || item.toUpperCase() === raw.toUpperCase())
  return matched ?? ''
}

/** 从路由 query 对象安全解析题库列表筛选与分页状态（对空 query 或异常值提供默认值）。 */
export function readBankQuery<
  TDifficulty extends string = string,
  TType extends string = string,
>(
  query?: LocationQuery | Record<string, unknown> | null,
  options?: ReadBankQueryOptions<TDifficulty, TType>,
): BankQueryState<TDifficulty, TType> {
  const activeFields = new Set<BankQueryKey>(options?.fields ?? BANK_QUERY_KEYS)
  const source = query ?? {}

  return {
    keyword: activeFields.has('keyword') ? readSingleQueryString(source.keyword).trim() : '',
    difficulty: activeFields.has('difficulty')
      ? readAllowedValue(source.difficulty, options?.validDifficulties)
      : '',
    type: activeFields.has('type') ? readAllowedValue(source.type, options?.validTypes) : '',
    tag: activeFields.has('tag') ? readSingleQueryString(source.tag).trim() : '',
    status: activeFields.has('status') ? readStatus(source.status) : 'ALL',
    page: activeFields.has('page') ? readPositiveInt(source.page, 1) : 1,
  }
}

function normalizeExistingQuery(
  existingQuery?: LocationQuery | Record<string, unknown> | null,
): Record<string, string | string[]> {
  if (!existingQuery) return {}
  const result: Record<string, string | string[]> = {}
  for (const [key, raw] of Object.entries(existingQuery)) {
    if (typeof raw === 'string') {
      result[key] = raw
    } else if (Array.isArray(raw)) {
      const strings = raw.filter((item): item is string => typeof item === 'string')
      if (strings.length > 0) {
        result[key] = strings
      }
    }
  }
  return result
}

/**
 * 构建精简 URL query 对象：
 * - page <= 1、status === 'ALL'、空字符串不写入冗余参数；
 * - 保留 existingQuery 中非题库筛选的上下文参数（如 setId / practiceCode）。
 */
export function buildBankQuery(
  state: Partial<BankQueryState>,
  existingQuery?: LocationQuery | Record<string, unknown> | null,
  fields: readonly BankQueryKey[] = BANK_QUERY_KEYS,
): Record<string, string | string[]> {
  const activeFields = new Set<BankQueryKey>(fields)
  const nextQuery = normalizeExistingQuery(existingQuery)

  for (const key of activeFields) {
    delete nextQuery[key]
  }

  if (activeFields.has('keyword')) {
    const keyword = (state.keyword ?? '').trim()
    if (keyword) nextQuery.keyword = keyword
  }

  if (activeFields.has('difficulty')) {
    const difficulty = (state.difficulty ?? '').trim()
    if (difficulty) nextQuery.difficulty = difficulty
  }

  if (activeFields.has('type')) {
    const type = (state.type ?? '').trim()
    if (type) nextQuery.type = type
  }

  if (activeFields.has('tag')) {
    const tag = (state.tag ?? '').trim()
    if (tag) nextQuery.tag = tag
  }

  if (activeFields.has('status')) {
    const status = (state.status ?? 'ALL').toUpperCase()
    if (status === 'UNSOLVED' || status === 'SOLVED') {
      nextQuery.status = status
    }
  }

  if (activeFields.has('page')) {
    const page = state.page
    if (typeof page === 'number' && Number.isSafeInteger(page) && page > 1) {
      nextQuery.page = String(page)
    }
  }

  return nextQuery
}

function queriesEqual(
  a: Record<string, string | string[]>,
  b: Record<string, string | string[]>,
): boolean {
  const keysA = Object.keys(a)
  const keysB = Object.keys(b)
  if (keysA.length !== keysB.length) return false

  for (const key of keysA) {
    if (!Object.prototype.hasOwnProperty.call(b, key)) return false
    const va = a[key]
    const vb = b[key]
    if (Array.isArray(va) || Array.isArray(vb)) {
      if (!Array.isArray(va) || !Array.isArray(vb) || va.length !== vb.length) return false
      for (let i = 0; i < va.length; i += 1) {
        if (va[i] !== vb[i]) return false
      }
    } else if (va !== vb) {
      return false
    }
  }
  return true
}

function safeUseRoute(): Partial<RouteLocationNormalizedLoaded> | undefined {
  try {
    const useRouteFn = (vueRouter as Partial<typeof vueRouter>).useRoute
    return typeof useRouteFn === 'function' ? useRouteFn() : undefined
  } catch {
    return undefined
  }
}

function safeUseRouter(): Partial<Router> | undefined {
  try {
    const useRouterFn = (vueRouter as Partial<typeof vueRouter>).useRouter
    return typeof useRouterFn === 'function' ? useRouterFn() : undefined
  } catch {
    return undefined
  }
}

/**
 * 题库列表 URL 查询状态同步 composable。
 * 防御式兼容仅 mock 了 `useRouter: () => ({ push })`（缺 `useRoute` 或 `router.replace`）的单测环境。
 */
export function useBankUrlSync<
  TDifficulty extends string = string,
  TType extends string = string,
>(options: UseBankUrlSyncOptions<TDifficulty, TType> = {}) {
  const fields = options.fields ?? BANK_QUERY_KEYS
  const activeFields = new Set<BankQueryKey>(fields)
  const route = safeUseRoute()
  const router = safeUseRouter()

  const initial = readBankQuery(route?.query, options)

  const keyword = options.refs?.keyword ?? ref<string>(initial.keyword)
  const difficulty = options.refs?.difficulty ?? ref<TDifficulty | ''>(initial.difficulty)
  const type = options.refs?.type ?? ref<TType | ''>(initial.type)
  const tag = options.refs?.tag ?? ref<string>(initial.tag)
  const status = options.refs?.status ?? ref<BankStatusFilter>(initial.status)
  const page = options.refs?.page ?? ref<number>(initial.page)

  function applyState(state: BankQueryState<TDifficulty, TType>) {
    if (activeFields.has('keyword')) keyword.value = state.keyword
    if (activeFields.has('difficulty')) difficulty.value = state.difficulty
    if (activeFields.has('type')) type.value = state.type
    if (activeFields.has('tag')) tag.value = state.tag
    if (activeFields.has('status')) status.value = state.status
    if (activeFields.has('page')) page.value = state.page
  }

  // 当外部传入了已存在的 refs 时，初始化同步一次当前 URL query
  if (options.refs) {
    applyState(initial)
  }

  let lastSyncedQuery = normalizeExistingQuery(route?.query)

  function readFromUrl(): BankQueryState<TDifficulty, TType> {
    const parsed = readBankQuery(route?.query, options)
    applyState(parsed)
    lastSyncedQuery = normalizeExistingQuery(route?.query)
    return parsed
  }

  function syncToUrl(): void {
    const nextQuery = buildBankQuery(
      {
        keyword: keyword.value,
        difficulty: difficulty.value,
        type: type.value,
        tag: tag.value,
        status: status.value,
        page: page.value,
      },
      route?.query,
      fields,
    )

    if (queriesEqual(nextQuery, lastSyncedQuery)) {
      return
    }

    lastSyncedQuery = nextQuery

    if (!router || typeof router.replace !== 'function') {
      return
    }

    try {
      void Promise.resolve(router.replace({ query: nextQuery })).catch(() => {
        /* 忽略同路由重复导航等非致命异常 */
      })
    } catch {
      /* 忽略 mock router 异常 */
    }
  }

  function reset(): void {
    applyState({
      keyword: '',
      difficulty: '',
      type: '',
      tag: '',
      status: 'ALL',
      page: 1,
    })
  }

  if (options.autoSync ?? true) {
    const watchedSources: Array<Ref<unknown>> = []
    if (activeFields.has('keyword')) watchedSources.push(keyword)
    if (activeFields.has('difficulty')) watchedSources.push(difficulty)
    if (activeFields.has('type')) watchedSources.push(type)
    if (activeFields.has('tag')) watchedSources.push(tag)
    if (activeFields.has('status')) watchedSources.push(status)
    if (activeFields.has('page')) watchedSources.push(page)

    if (watchedSources.length > 0) {
      watch(watchedSources, () => {
        syncToUrl()
      })
    }

    if (route) {
      watch(
        () => route.query,
        (nextRouteQuery) => {
          const normalized = normalizeExistingQuery(nextRouteQuery)
          if (queriesEqual(normalized, lastSyncedQuery)) return
          lastSyncedQuery = normalized
          applyState(readBankQuery(nextRouteQuery, options))
        },
      )
    }
  }

  return {
    keyword,
    difficulty,
    type,
    tag,
    status,
    page,
    readFromUrl,
    syncToUrl,
    reset,
  }
}
