import { describe, expect, it, vi } from 'vitest'
import type { PageResult } from '@/types'
import {
  fetchAllPagedItems,
  pickRandomFromPagedPool,
  randomInt,
  sampleBatchFromPagedPool,
  sampleDistinctIndices,
  shuffleItems,
} from './randomSample'

interface Item {
  id: number
  title: string
}

function makePagedFetcher(total: number, startId = 1) {
  return vi.fn(async (page: number, size: number): Promise<PageResult<Item>> => {
    const startIndex = (page - 1) * size
    const endIndex = Math.min(total, startIndex + size)
    const list: Item[] = []
    for (let idx = startIndex; idx < endIndex; idx++) {
      const id = startId + idx
      list.push({ id, title: `Q-${id}` })
    }
    return { list, total, page, size }
  })
}

describe('randomSample', () => {
  it('randomInt 与 shuffleItems 生成合法下标与等长置换', () => {
    expect(randomInt(0)).toBe(0)
    expect(randomInt(1)).toBe(0)
    expect(randomInt(10, () => 0.999999)).toBe(9)
    expect(randomInt(10, () => 0)).toBe(0)

    const source = [1, 2, 3, 4, 5]
    const shuffled = shuffleItems(source)
    expect(shuffled).toHaveLength(5)
    expect(shuffled.slice().sort((a, b) => a - b)).toEqual(source)
  })

  it('pickRandomFromPagedPool 可跨分页按全局下标均匀抽题且命中已知当前页不额外发请求', async () => {
    const fetchPage = makePagedFetcher(828)

    // 指向全局第 827 题（0-based index 826 -> 第 83 页下标 6），证明绝不局限在第一页
    const pickedLate = await pickRandomFromPagedPool({
      fetchPage,
      pageSize: 10,
      knownTotal: 828,
      knownPage: 1,
      knownList: Array.from({ length: 10 }, (_, i) => ({ id: i + 1, title: `Q-${i + 1}` })),
      rng: () => 826.5 / 828,
    })

    expect(pickedLate?.item.id).toBe(827)
    expect(fetchPage).toHaveBeenCalledTimes(1)
    expect(fetchPage).toHaveBeenCalledWith(83, 10)

    // 指向已知第 1 页第 4 项（index 3），复用缓存，0 次网络请求
    fetchPage.mockClear()
    const pickedCurrentPage = await pickRandomFromPagedPool({
      fetchPage,
      pageSize: 10,
      knownTotal: 828,
      knownPage: 1,
      knownList: Array.from({ length: 10 }, (_, i) => ({ id: i + 1, title: `Q-${i + 1}` })),
      rng: () => 3.2 / 828,
    })
    expect(pickedCurrentPage?.item.id).toBe(4)
    expect(fetchPage).not.toHaveBeenCalled()
  })

  it('pickRandomFromPagedPool 在末页缩水时安全回退而不返回空', async () => {
    const fetchPage = vi.fn(async (page: number, size: number): Promise<PageResult<Item>> => {
      if (page === 1) {
        return {
          list: [{ id: 1, title: 'Q-1' }],
          total: 25,
          page: 1,
          size,
        }
      }
      return { list: [], total: 1, page, size }
    })

    const picked = await pickRandomFromPagedPool({
      fetchPage,
      pageSize: 10,
      rng: () => 0.95,
    })
    expect(picked?.item.id).toBe(1)
  })

  it('sampleBatchFromPagedPool 在超大规模题库中跨精确分页抽题并包含末页权重一致', async () => {
    const fetchPage = makePagedFetcher(828)
    const seq = [0.001, 0.25, 0.5, 0.75, 0.9999]
    let cursor = 0

    const batch = await sampleBatchFromPagedPool({
      count: 5,
      fetchPage,
      probeSize: 5,
      maxSingleFetch: 100,
      rng: () => seq[cursor++ % seq.length],
    })

    expect(batch?.poolTotal).toBe(828)
    expect(batch?.items).toHaveLength(5)
    const ids = batch!.items.map((item) => item.id)
    expect(new Set(ids).size).toBe(5)
    // 最后一题（828）必须可被精确索引到（0.9999 * 828 -> 827 -> 第 166 页）
    expect(ids).toContain(828)
    expect(fetchPage).toHaveBeenCalledWith(166, 5)
  })

  it('sampleBatchFromPagedPool 优先避开 softExcludeIds 并在候选不足时安全补足', async () => {
    const fetchPage = makePagedFetcher(8)
    const batch = await sampleBatchFromPagedPool({
      count: 5,
      fetchPage,
      probeSize: 5,
      maxSingleFetch: 100,
      excludeIds: new Set([1, 2]),
      softExcludeIds: new Set([3, 4]),
    })

    // 排除 [1,2] 剩余 6 题，优先抽非 [3,4] 的 5,6,7,8，再从 softExcludeIds 补 1 题凑满 5 题
    expect(batch?.items).toHaveLength(5)
    const ids = batch!.items.map((item) => item.id)
    expect(ids).not.toContain(1)
    expect(ids).not.toContain(2)
    expect([5, 6, 7, 8].every((id) => ids.includes(id))).toBe(true)
  })

  it('sampleDistinctIndices 与 fetchAllPagedItems 正确处理大集合全量分页拉取', async () => {
    const indices = sampleDistinctIndices(300, 10, new Set([0, 1, 2]))
    expect(indices).toHaveLength(10)
    expect(indices.every((i) => i >= 3 && i < 300)).toBe(true)

    const fetchPage = makePagedFetcher(235)
    const all = await fetchAllPagedItems(fetchPage)
    expect(all?.total).toBe(235)
    expect(all?.list).toHaveLength(235)
    expect(fetchPage).toHaveBeenCalledWith(1, 100)
    expect(fetchPage).toHaveBeenCalledWith(2, 100)
    expect(fetchPage).toHaveBeenCalledWith(3, 100)
  })
})
