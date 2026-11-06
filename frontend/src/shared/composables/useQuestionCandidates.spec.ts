import { beforeEach, describe, expect, it, vi } from 'vitest'
import { adminApi } from '@/api/admin'
import type { DbQuestion, ObjectiveQuestion, PageResult, Question } from '@/types'
import { useQuestionCandidates } from './useQuestionCandidates'

vi.mock('@/api/admin', () => ({
  adminApi: {
    listQuestions: vi.fn(),
    listDbQuestions: vi.fn(),
    listObjectiveQuestions: vi.fn(),
  },
}))

describe('useQuestionCandidates composable', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function pageResult<T>(list: T[], total: number, page = 1, size = 10): PageResult<T> {
    return { list, total, page, size }
  }

  function question(id: number, title: string, difficulty: Question['difficulty']): Question {
    return {
      id,
      title,
      difficulty,
      description: '',
      timeLimit: null,
      memoryLimit: null,
      languages: [],
      status: 1,
      createdBy: 1,
      sampleTestCases: [],
      createdAt: '',
      updatedAt: '',
    }
  }

  function dbQuestion(id: number, title: string): DbQuestion {
    return {
      id,
      title,
      description: '',
      dialect: 'mysql',
      type: 'SELECT',
      schemaSql: '',
      referenceSql: null,
      verifySql: null,
      orderSensitive: false,
      status: 1,
      createdBy: 1,
      testCases: [],
      createdAt: '',
      updatedAt: '',
    }
  }

  function objectiveQuestion(id: number, content: string, score: number): ObjectiveQuestion {
    return {
      id,
      type: 'SINGLE',
      content,
      options: [],
      answer: null,
      matchType: 'EXACT',
      score,
      status: 1,
      createdBy: 1,
      createdAt: '',
      updatedAt: '',
    }
  }

  it('精确题号查询 (#103) 正确解析为 id=103，并标准化 StandardCandidate', async () => {
    vi.mocked(adminApi.listQuestions).mockResolvedValue(
      pageResult([question(103, '两数之和', 'EASY')], 1),
    )

    const { keyword, candidates, total, loading, load } = useQuestionCandidates({
      category: 'ALGO',
      defaultSize: 10,
    })

    keyword.value = '#103'
    await load()

    expect(adminApi.listQuestions).toHaveBeenCalledWith({
      id: 103,
      keyword: undefined,
      page: 1,
      size: 10,
    })
    expect(total.value).toBe(1)
    expect(loading.value).toBe(false)
    expect(candidates.value).toEqual([
      {
        uniqueKey: 'ALGO_103',
        id: 103,
        type: 'ALGO',
        questionType: 'ALGO',
        questionId: 103,
        title: '两数之和',
        difficulty: 'EASY',
        defaultScore: 20,
      },
    ])
  })

  it('关键词检索正确传递 keyword 参数并支持客观题分类', async () => {
    vi.mocked(adminApi.listObjectiveQuestions).mockResolvedValue(
      pageResult([objectiveQuestion(5, '什么是多态？', 7)], 1, 1, 12),
    )

    const { keyword, candidates, load } = useQuestionCandidates({
      category: 'OBJECTIVE',
      defaultSize: 12,
    })

    keyword.value = '多态'
    await load()

    expect(adminApi.listObjectiveQuestions).toHaveBeenCalledWith({
      id: undefined,
      keyword: '多态',
      page: 1,
      size: 12,
    })
    expect(candidates.value[0]).toEqual({
      uniqueKey: 'OBJECTIVE_5',
      id: 5,
      type: 'OBJECTIVE',
      questionType: 'OBJECTIVE',
      questionId: 5,
      title: '什么是多态？',
      subType: 'SINGLE',
      difficulty: 'SINGLE',
      defaultScore: 7,
    })
  })

  it('防竞态保护：迟到的旧请求不覆盖最新结果', async () => {
    let resolveFirst: (value: PageResult<DbQuestion>) => void = () => {
      throw new Error('测试未初始化')
    }
    const slowPromise = new Promise<PageResult<DbQuestion>>((resolve) => {
      resolveFirst = resolve
    })

    vi.mocked(adminApi.listDbQuestions).mockImplementation((params) => {
      if (params.page === 1) {
        return slowPromise
      }
      return Promise.resolve(pageResult([dbQuestion(2, 'SQL Page 2')], 20, 2))
    })

    const { candidates, load } = useQuestionCandidates({
      category: 'DB',
      defaultSize: 10,
    })

    const p1 = load('DB', 1)
    const p2 = load('DB', 2)

    await p2
    expect(candidates.value[0].title).toBe('SQL Page 2')

    resolveFirst(pageResult([dbQuestion(1, 'SQL Page 1 Outdated')], 20))
    await p1

    expect(candidates.value[0].title).toBe('SQL Page 2')
  })
})

