import { computed, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { questionApi } from '@/api/question'
import type { TestCase } from '@/types'
import { formatTestCaseBytes, useQuestionTestCases } from './useQuestionTestCases'

vi.mock('@/api/question', () => ({
  questionApi: {
    getTestCaseSummary: vi.fn(),
    listTestCases: vi.fn(),
    toggleTestCaseSample: vi.fn(),
    setSampleCaseIds: vi.fn(),
    importTestCasesZip: vi.fn(),
    exportTestCasesZip: vi.fn(),
  },
}))

function testCase(id: number, isSample = 0): TestCase {
  return {
    id,
    caseNum: id,
    input: null,
    expectedOutput: '',
    isSample,
  }
}

describe('useQuestionTestCases composable', () => {
  const success = vi.fn()
  const error = vi.fn()
  const onImportComplete = vi.fn<() => Promise<void>>()
  const questionId = ref<number | null>(42)

  beforeEach(() => {
    vi.clearAllMocks()
    questionId.value = 42
    onImportComplete.mockResolvedValue()
  })

  function createSubject() {
    return useQuestionTestCases({
      questionId: computed(() => questionId.value),
      notify: { success, error },
      onImportComplete,
    })
  }

  it('同步服务端统计与测试用例列表，并以列表为样例计数来源', async () => {
    vi.mocked(questionApi.getTestCaseSummary).mockResolvedValue({
      totalCount: 2,
      sampleCount: 1,
      totalInputBytes: 10,
      totalOutputBytes: 20,
      totalBytes: 30,
    })
    vi.mocked(questionApi.listTestCases).mockResolvedValue([testCase(1, 1), testCase(2)])

    const subject = createSubject()
    await subject.refresh()

    expect(questionApi.getTestCaseSummary).toHaveBeenCalledWith(42)
    expect(questionApi.listTestCases).toHaveBeenCalledWith(42)
    expect(subject.testCaseCount.value).toBe(2)
    expect(subject.sampleCasesCount.value).toBe(1)
    expect(subject.totalDataBytes.value).toBe(30)
  })

  it('应用样例策略后同步本地状态，不需要重新请求列表', async () => {
    vi.mocked(questionApi.getTestCaseSummary).mockResolvedValue({
      totalCount: 3,
      sampleCount: 0,
      totalInputBytes: 0,
      totalOutputBytes: 0,
      totalBytes: 0,
    })
    vi.mocked(questionApi.listTestCases).mockResolvedValue([testCase(1), testCase(2), testCase(3)])
    vi.mocked(questionApi.setSampleCaseIds).mockResolvedValue()

    const subject = createSubject()
    await subject.refresh()
    await subject.applySamplePreset('first2')

    expect(questionApi.setSampleCaseIds).toHaveBeenCalledWith(42, [1, 2])
    expect(subject.testCases.value.map((item) => item.isSample)).toEqual([1, 1, 0])
    expect(subject.sampleCasesCount.value).toBe(2)
  })

  it('切换单个样例失败时不乐观修改本地记录', async () => {
    vi.mocked(questionApi.getTestCaseSummary).mockResolvedValue({
      totalCount: 1,
      sampleCount: 0,
      totalInputBytes: 0,
      totalOutputBytes: 0,
      totalBytes: 0,
    })
    vi.mocked(questionApi.listTestCases).mockResolvedValue([testCase(8)])
    vi.mocked(questionApi.toggleTestCaseSample).mockRejectedValue(new Error('网络异常'))

    const subject = createSubject()
    await subject.refresh()
    await subject.toggleSample(subject.testCases.value[0])

    expect(subject.testCases.value[0].isSample).toBe(0)
    expect(error).toHaveBeenCalledWith('网络异常')
  })

  it('未保存题目时不打开导入框，并给出明确提示', () => {
    questionId.value = null
    const subject = createSubject()

    subject.openZipModal()

    expect(subject.showZipModal.value).toBe(false)
    expect(error).toHaveBeenCalledWith('请先保存题目基本信息后再导入测试集 Zip')
  })

  it('导入 Zip 后保留用户选项、关闭弹窗并触发页面刷新', async () => {
    vi.mocked(questionApi.importTestCasesZip).mockResolvedValue([testCase(1), testCase(2)])
    const subject = createSubject()
    subject.openZipModal()
    subject.zipFile.value = new File(['test data'], 'cases.zip', { type: 'application/zip' })
    subject.zipReplace.value = false
    subject.zipFirstAsSample.value = true

    await subject.submitZipImport()

    expect(questionApi.importTestCasesZip).toHaveBeenCalledWith(
      42,
      subject.zipFile.value,
      false,
      true,
    )
    expect(subject.showZipModal.value).toBe(false)
    expect(success).toHaveBeenCalledWith('成功导入 2 组测试点！')
    expect(onImportComplete).toHaveBeenCalledOnce()
  })

  it('格式化测试数据体积时保留可读单位', () => {
    expect(formatTestCaseBytes(0)).toBe('0 B')
    expect(formatTestCaseBytes(512)).toBe('512 B')
    expect(formatTestCaseBytes(1536)).toBe('1.5 KB')
    expect(formatTestCaseBytes(2 * 1024 * 1024)).toBe('2.00 MB')
  })
})
