import { ref, type Ref } from 'vue'
import { adminApi } from '@/api/admin'
import { ApiError } from '@/api/request'
import type { ObjectiveType } from '@/types'

export interface StandardCandidate {
  /** 标准复合主键 `${type}_${id}`，避免 ALGO 和 DB 题号重叠冲突。 */
  uniqueKey: string
  id: number
  type: 'ALGO' | 'DB' | 'OBJECTIVE'
  title: string
  subType?: ObjectiveType
  difficulty?: string
  defaultScore?: number
  /** 兼容既有组件字段 */
  questionType: 'ALGO' | 'DB' | 'OBJECTIVE'
  questionId: number
}

export type CandidateCategory =
  | 'ALGO'
  | 'DB'
  | 'OBJECTIVE'
  | 'ALL_OBJ'
  | 'SINGLE'
  | 'MULTIPLE'
  | 'JUDGE'
  | 'FILL'

export interface QuestionCandidatesOptions {
  category?: Ref<CandidateCategory | string> | CandidateCategory | string
  defaultSize?: number
}

export function useQuestionCandidates(options?: QuestionCandidatesOptions) {
  const keyword = ref('')
  const page = ref(1)
  const size = ref(options?.defaultSize ?? 20)
  const total = ref(0)
  const candidates = ref<StandardCandidate[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  let requestSeq = 0

  function getActiveCategory(override?: string): string {
    if (override !== undefined) return override
    if (options?.category) {
      if (typeof options.category === 'object' && 'value' in options.category) {
        return (options.category as Ref<string>).value
      }
      return options.category as string
    }
    return 'ALGO'
  }

  async function load(categoryOverride?: string, targetPage?: number): Promise<void> {
    if (targetPage !== undefined) {
      page.value = targetPage
    }
    const currentSeq = ++requestSeq
    loading.value = true
    error.value = null

    try {
      const activeCategory = getActiveCategory(categoryOverride)
      const input = keyword.value.trim()
      const match = /^#?([1-9]\d*)$/.exec(input)
      const id = match ? Number(match[1]) : undefined
      if (id !== undefined && !Number.isSafeInteger(id)) {
        throw new Error('题号超出有效范围')
      }

      const params = {
        id,
        keyword: id === undefined ? input || undefined : undefined,
        page: page.value,
        size: size.value,
      }

      let resTotal = 0
      let resList: StandardCandidate[] = []

      if (activeCategory === 'ALGO') {
        const res = await adminApi.listQuestions(params)
        resTotal = res.total
        resList = res.list.map((q) => ({
          uniqueKey: `ALGO_${q.id}`,
          id: q.id,
          type: 'ALGO' as const,
          questionType: 'ALGO' as const,
          questionId: q.id,
          title: q.title,
          difficulty: q.difficulty,
          defaultScore: 20,
        }))
      } else if (activeCategory === 'DB') {
        const res = await adminApi.listDbQuestions(params)
        resTotal = res.total
        resList = res.list.map((q) => ({
          uniqueKey: `DB_${q.id}`,
          id: q.id,
          type: 'DB' as const,
          questionType: 'DB' as const,
          questionId: q.id,
          title: q.title,
          difficulty: q.type,
          defaultScore: 20,
        }))
      } else if (activeCategory === 'ALL_OBJ' || activeCategory === 'OBJECTIVE') {
        const res = await adminApi.listObjectiveQuestions(params)
        resTotal = res.total
        resList = res.list.map((q) => ({
          uniqueKey: `OBJECTIVE_${q.id}`,
          id: q.id,
          type: 'OBJECTIVE' as const,
          questionType: 'OBJECTIVE' as const,
          questionId: q.id,
          title: q.content || `客观题 #${q.id}`,
          subType: q.type,
          difficulty: q.type,
          defaultScore: q.score > 0 ? q.score : q.type === 'JUDGE' ? 2 : 5,
        }))
      } else {
        // 客观题单题型过滤
        const res = await adminApi.listObjectiveQuestions({
          ...params,
          type: activeCategory,
        })
        resTotal = res.total
        resList = res.list.map((q) => ({
          uniqueKey: `OBJECTIVE_${q.id}`,
          id: q.id,
          type: 'OBJECTIVE' as const,
          questionType: 'OBJECTIVE' as const,
          questionId: q.id,
          title: q.content || `客观题 #${q.id}`,
          subType: q.type,
          difficulty: q.type,
          defaultScore: q.score > 0 ? q.score : q.type === 'JUDGE' ? 2 : 5,
        }))
      }

      if (currentSeq === requestSeq) {
        candidates.value = resList
        total.value = resTotal
      }
    } catch (cause: unknown) {
      if (currentSeq === requestSeq) {
        candidates.value = []
        total.value = 0
        error.value =
          cause instanceof ApiError || cause instanceof Error
            ? cause.message
            : '检索失败，请重试'
      }
    } finally {
      if (currentSeq === requestSeq) {
        loading.value = false
      }
    }
  }

  function reset(): void {
    page.value = 1
    void load()
  }

  function cleanup(): void {
    requestSeq++
  }

  return {
    keyword,
    page,
    size,
    total,
    candidates,
    loading,
    error,
    load,
    reset,
    cleanup,
  }
}
