import type { PageResult } from '@/types'

/**
 * 返回 [0, maxExclusive) 范围内的均匀随机整数。
 */
export function randomInt(maxExclusive: number, rng: () => number = Math.random): number {
  if (!Number.isFinite(maxExclusive) || maxExclusive <= 1) return 0
  const bound = Math.floor(maxExclusive)
  return Math.min(bound - 1, Math.floor(rng() * bound))
}

/**
 * 分页拉取匹配条件的全部条目（单页遵循后端 PageParam 上限 100，避免 size 过大被静默截断）。
 */
export async function fetchAllPagedItems<T>(
  fetchPage: (page: number, size: number) => Promise<PageResult<T>>,
  isStale?: () => boolean,
): Promise<{ list: T[]; total: number } | null> {
  const first = await fetchPage(1, 100)
  if (isStale?.()) return null
  if (first.total <= first.list.length || first.list.length === 0) {
    return { list: first.list, total: first.total }
  }

  const pageSize = first.list.length
  const totalPages = Math.ceil(first.total / pageSize)
  const restPages = Array.from({ length: totalPages - 1 }, (_, i) => i + 2)
  const restResults = await Promise.all(restPages.map((p) => fetchPage(p, pageSize)))
  if (isStale?.()) return null

  return {
    list: first.list.concat(...restResults.map((res) => res.list)),
    total: first.total,
  }
}

/** Fisher-Yates 原地等概率洗牌的不可变副本。 */
export function shuffleItems<T>(items: readonly T[], rng: () => number = Math.random): T[] {
  const copy = items.slice()
  for (let i = copy.length - 1; i > 0; i--) {
    const j = randomInt(i + 1, rng)
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
  }
  return copy
}

/**
 * 从 [0, total) 中无放回地均匀抽取最多 count 个全局下标，自动跳过 excludeIndices。
 */
export function sampleDistinctIndices(
  total: number,
  count: number,
  excludeIndices?: ReadonlySet<number>,
  rng: () => number = Math.random,
): number[] {
  const safeTotal = Math.floor(total)
  const targetCount = Math.floor(count)
  if (safeTotal <= 0 || targetCount <= 0) return []

  const excludedCount = excludeIndices?.size ?? 0
  const available = Math.max(0, safeTotal - excludedCount)
  if (available === 0) return []

  const want = Math.min(targetCount, available)
  // 剩余可用规模较小时直接列出候选下标洗牌，避免随机重试退化。
  if (safeTotal <= 256 || want > available / 2) {
    const candidates: number[] = []
    for (let i = 0; i < safeTotal; i++) {
      if (!excludeIndices?.has(i)) candidates.push(i)
    }
    return shuffleItems(candidates, rng).slice(0, want)
  }

  const picked = new Set<number>()
  let guard = want * 20 + 32
  while (picked.size < want && guard-- > 0) {
    const candidate = randomInt(safeTotal, rng)
    if (!excludeIndices?.has(candidate) && !picked.has(candidate)) {
      picked.add(candidate)
    }
  }
  return Array.from(picked)
}

export interface PickRandomFromPagedPoolOptions<T> {
  fetchPage: (page: number, size: number) => Promise<PageResult<T>>
  pageSize: number
  knownTotal?: number
  knownPage?: number
  knownList?: readonly T[]
  isStale?: () => boolean
  rng?: () => number
}

/**
 * 按全局下标 1/N 等概率从服务端分页列表中随机抽取 1 条记录。
 * 命中已知当前页或探测页时复用已有数据，避免额外网络请求与末页权重倾斜。
 */
export async function pickRandomFromPagedPool<T>(
  options: PickRandomFromPagedPoolOptions<T>,
): Promise<{ item: T; total: number } | null> {
  const { fetchPage, pageSize, isStale, rng = Math.random } = options
  const safeSize = Math.max(1, Math.floor(pageSize))

  let poolTotal = options.knownTotal
  const pageCache = new Map<number, readonly T[]>()

  if (options.knownPage !== undefined && options.knownList && options.knownList.length > 0) {
    pageCache.set(options.knownPage, options.knownList)
  }

  if (poolTotal === undefined || poolTotal < 0) {
    const probe = await fetchPage(1, safeSize)
    if (isStale?.()) return null
    poolTotal = probe.total
    pageCache.set(probe.page || 1, probe.list)
  }

  if (poolTotal <= 0) return null

  const targetIndex = randomInt(poolTotal, rng)
  const targetPage = Math.floor(targetIndex / safeSize) + 1
  const offsetInPage = targetIndex % safeSize

  let pageItems = pageCache.get(targetPage)
  if (!pageItems) {
    const res = await fetchPage(targetPage, safeSize)
    if (isStale?.()) return null
    pageItems = res.list
    pageCache.set(targetPage, pageItems)
  }

  const exactItem = pageItems[offsetInPage]
  if (exactItem !== undefined) {
    return { item: exactItem, total: poolTotal }
  }

  if (pageItems.length > 0) {
    return { item: pageItems[randomInt(pageItems.length, rng)], total: poolTotal }
  }

  // 若服务端数据并发缩水导致目标页为空，回退读取首页避免返回空值。
  const cachedFirst = pageCache.get(1)
  if (cachedFirst && cachedFirst.length > 0) {
    return { item: cachedFirst[randomInt(cachedFirst.length, rng)], total: poolTotal }
  }
  const fallback = await fetchPage(1, safeSize)
  if (isStale?.() || fallback.list.length === 0) return null
  return { item: fallback.list[randomInt(fallback.list.length, rng)], total: fallback.total }
}

export interface PickRandomWithUnsolvedPriorityOptions<T> {
  fetchPage: (
    userStatus: 'UNSOLVED' | 'SOLVED' | undefined,
    page: number,
    size: number,
  ) => Promise<PageResult<T>>
  pageSize: number
  isLoggedIn: boolean
  statusFilter: 'ALL' | 'UNSOLVED' | 'SOLVED'
  knownTotal: number
  knownPage: number
  knownList: readonly T[]
  isStale?: () => boolean
  rng?: () => number
}

/**
 * 跨全库分页均匀随机抽取一题：
 * 已登录且筛选为 ALL 时优先从全库 UNSOLVED 中抽取，若全库均已通过则回退到完整筛选集合。
 */
export async function pickRandomWithUnsolvedPriority<T>(
  options: PickRandomWithUnsolvedPriorityOptions<T>,
): Promise<{ item: T; total: number } | null> {
  const {
    fetchPage,
    pageSize,
    isLoggedIn,
    statusFilter,
    knownTotal,
    knownPage,
    knownList,
    isStale,
    rng,
  } = options

  if (isLoggedIn && statusFilter === 'ALL') {
    const unsolvedPick = await pickRandomFromPagedPool({
      fetchPage: (p, s) => fetchPage('UNSOLVED', p, s),
      pageSize,
      isStale,
      rng,
    })
    if (isStale?.() || unsolvedPick) return unsolvedPick
  }

  const activeUserStatus = statusFilter === 'ALL' ? undefined : statusFilter
  return pickRandomFromPagedPool({
    fetchPage: (p, s) => fetchPage(activeUserStatus, p, s),
    pageSize,
    knownTotal,
    knownPage,
    knownList,
    isStale,
    rng,
  })
}

export interface SampleBatchFromPagedPoolOptions<T> {
  count: number
  fetchPage: (page: number, size: number) => Promise<PageResult<T>>
  probeSize?: number
  maxSingleFetch?: number
  excludeIds?: ReadonlySet<number>
  softExcludeIds?: ReadonlySet<number>
  isStale?: () => boolean
  rng?: () => number
}

function collectEligible<T extends { id: number }>(
  source: readonly T[],
  excludeIds: ReadonlySet<number>,
  softExcludeIds: ReadonlySet<number> | undefined,
  seenIds: Set<number>,
  picked: T[],
  softFallback: T[],
  limit: number,
) {
  for (const item of source) {
    if (picked.length >= limit) break
    if (excludeIds.has(item.id) || seenIds.has(item.id)) continue
    seenIds.add(item.id)
    if (softExcludeIds?.has(item.id)) {
      softFallback.push(item)
    } else {
      picked.push(item)
    }
  }
}

/**
 * 从服务端分页题库中均匀无放回抽取 count 条不重复题目。
 * - poolTotal <= probeSize：直接复用探测页
 * - poolTotal <= maxSingleFetch：单次拉全并洗牌（默认上限 100 对应后端 PageParam 上限）
 * - poolTotal > maxSingleFetch：按全局下标均匀采样精确分页后抽取，消除末页与簇采样偏差
 */
export async function sampleBatchFromPagedPool<T extends { id: number }>(
  options: SampleBatchFromPagedPoolOptions<T>,
): Promise<{ items: T[]; poolTotal: number } | null> {
  const {
    count,
    fetchPage,
    probeSize = 5,
    maxSingleFetch = 100,
    excludeIds = new Set<number>(),
    softExcludeIds,
    isStale,
    rng = Math.random,
  } = options

  if (count <= 0) return { items: [], poolTotal: 0 }

  const safeProbeSize = Math.max(1, Math.floor(probeSize))
  const safeMaxSingle = Math.max(safeProbeSize, Math.min(100, Math.floor(maxSingleFetch)))

  const probe = await fetchPage(1, safeProbeSize)
  if (isStale?.()) return null

  const poolTotal = probe.total
  if (poolTotal <= 0) return { items: [], poolTotal: 0 }

  if (poolTotal <= safeProbeSize || poolTotal <= safeMaxSingle) {
    let allItems = probe.list
    if (poolTotal > safeProbeSize) {
      const full = await fetchPage(1, poolTotal)
      if (isStale?.()) return null
      allItems = full.list
    }
    const picked: T[] = []
    const softFallback: T[] = []
    collectEligible(
      shuffleItems(allItems, rng),
      excludeIds,
      softExcludeIds,
      new Set<number>(),
      picked,
      softFallback,
      count,
    )
    const items =
      picked.length >= count ? picked : picked.concat(softFallback.slice(0, count - picked.length))
    return { items, poolTotal }
  }

  const pageCache = new Map<number, readonly T[]>([[1, probe.list]])
  const triedIndices = new Set<number>()
  const seenIds = new Set<number>()
  const picked: T[] = []
  const softFallback: T[] = []

  // 最多 3 轮精确全局下标采样，以防少量条目命中 excludeIds / softExcludeIds 时仍需补足 count。
  for (let round = 0; round < 3 && picked.length < count; round++) {
    const needThisRound = count - picked.length
    const sampledIndices = sampleDistinctIndices(poolTotal, needThisRound, triedIndices, rng)
    if (sampledIndices.length === 0) break
    for (const idx of sampledIndices) triedIndices.add(idx)

    const missingPages = Array.from(
      new Set(
        sampledIndices
          .map((idx) => Math.floor(idx / safeProbeSize) + 1)
          .filter((p) => !pageCache.has(p)),
      ),
    )

    if (missingPages.length > 0) {
      const fetched = await Promise.all(
        missingPages.map(async (targetPage) => ({
          targetPage,
          res: await fetchPage(targetPage, safeProbeSize),
        })),
      )
      if (isStale?.()) return null
      for (const { targetPage, res } of fetched) {
        pageCache.set(targetPage, res.list)
      }
    }

    const roundItems: T[] = []
    for (const idx of sampledIndices) {
      const targetPage = Math.floor(idx / safeProbeSize) + 1
      const offsetInPage = idx % safeProbeSize
      const item = pageCache.get(targetPage)?.[offsetInPage]
      if (item !== undefined) {
        roundItems.push(item)
      }
    }
    collectEligible(roundItems, excludeIds, softExcludeIds, seenIds, picked, softFallback, count)
  }

  if (picked.length < count) {
    const cachedExtras = shuffleItems(Array.from(pageCache.values()).flat(), rng)
    collectEligible(cachedExtras, excludeIds, softExcludeIds, seenIds, picked, softFallback, count)
  }

  const items =
    picked.length >= count ? picked : picked.concat(softFallback.slice(0, count - picked.length))
  return { items: items.slice(0, count), poolTotal }
}
