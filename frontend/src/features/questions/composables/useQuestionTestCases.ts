import { ref, type ComputedRef } from 'vue'
import { questionApi } from '@/api/question'
import { ApiError } from '@/api/request'
import type { TestCase } from '@/types'

export type SamplePreset = 'first1' | 'first2' | 'all' | 'none'

interface QuestionTestCaseNotifier {
  success(message: string): void
  error(message: string): void
}

export interface QuestionTestCasesOptions {
  questionId: ComputedRef<number | null>
  notify: QuestionTestCaseNotifier
  /** Zip 导入成功后由页面重新读取题目本身，保持页面级加载语义。 */
  onImportComplete: () => Promise<void>
}

export function formatTestCaseBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function getErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error && error.message) return error.message
  return fallback
}

/**
 * 编程题测试用例管理 module。
 *
 * Interface 只暴露视图需要的状态和用户动作；统计同步、样例策略、Zip 文件校验、
 * 并发开关及 API 错误转换均封装在 implementation 内，避免题目编辑页重复承担这些规则。
 */
export function useQuestionTestCases(options: QuestionTestCasesOptions) {
  const testCaseCount = ref(0)
  const sampleCasesCount = ref(0)
  const totalDataBytes = ref(0)
  const testCases = ref<TestCase[]>([])
  const loadingCases = ref(false)
  const togglingCaseId = ref<number | null>(null)
  const settingSamples = ref(false)

  const showZipModal = ref(false)
  const zipFile = ref<File | null>(null)
  const zipReplace = ref(true)
  const zipFirstAsSample = ref(true)
  const zipUploading = ref(false)
  const isZipDragOver = ref(false)
  const zipExporting = ref(false)

  function syncSampleCount(): void {
    testCaseCount.value = testCases.value.length
    sampleCasesCount.value = testCases.value.filter((testCase) => testCase.isSample === 1).length
  }

  async function loadTestCases(): Promise<void> {
    const questionId = options.questionId.value
    if (questionId == null) return

    loadingCases.value = true
    try {
      testCases.value = (await questionApi.listTestCases(questionId)) || []
      syncSampleCount()
    } catch (error: unknown) {
      // 题目基础信息仍可编辑；测试点列表失败只记录诊断，避免覆盖页面主错误。
      console.error('加载测试点列表失败', error)
    } finally {
      loadingCases.value = false
    }
  }

  async function refresh(): Promise<void> {
    const questionId = options.questionId.value
    if (questionId == null) return

    const summary = await questionApi.getTestCaseSummary(questionId)
    testCaseCount.value = summary.totalCount || 0
    sampleCasesCount.value = summary.sampleCount || 0
    totalDataBytes.value = summary.totalBytes || 0
    await loadTestCases()
  }

  async function toggleSample(testCase: TestCase): Promise<void> {
    const questionId = options.questionId.value
    if (questionId == null || togglingCaseId.value !== null) return

    const targetSample = testCase.isSample !== 1
    togglingCaseId.value = testCase.id
    try {
      await questionApi.toggleTestCaseSample(questionId, testCase.id, targetSample)
      testCase.isSample = targetSample ? 1 : 0
      syncSampleCount()
      options.notify.success(`测试点 #${testCase.caseNum} 已${targetSample ? '设为' : '取消'}公开样例`)
    } catch (error: unknown) {
      options.notify.error(getErrorMessage(error, '修改样例状态失败'))
    } finally {
      togglingCaseId.value = null
    }
  }

  async function applySamplePreset(preset: SamplePreset): Promise<void> {
    const questionId = options.questionId.value
    if (questionId == null || testCases.value.length === 0 || settingSamples.value) return

    const targetIds =
      preset === 'first1'
        ? [testCases.value[0].id]
        : preset === 'first2'
          ? testCases.value.slice(0, 2).map((testCase) => testCase.id)
          : preset === 'all'
            ? testCases.value.map((testCase) => testCase.id)
            : []

    settingSamples.value = true
    try {
      await questionApi.setSampleCaseIds(questionId, targetIds)
      const targetSet = new Set(targetIds)
      testCases.value.forEach((testCase) => {
        testCase.isSample = targetSet.has(testCase.id) ? 1 : 0
      })
      syncSampleCount()
      options.notify.success('样例设置已生效')
    } catch (error: unknown) {
      options.notify.error(getErrorMessage(error, '更新样例设置失败'))
    } finally {
      settingSamples.value = false
    }
  }

  function openZipModal(): void {
    if (options.questionId.value == null) {
      options.notify.error('请先保存题目基本信息后再导入测试集 Zip')
      return
    }
    zipFile.value = null
    showZipModal.value = true
  }

  function handleZipFileSelect(event: Event): void {
    const target = event.target as HTMLInputElement
    if (target.files?.length) {
      zipFile.value = target.files[0]
    }
  }

  function handleZipDrop(event: DragEvent): void {
    isZipDragOver.value = false
    const file = event.dataTransfer?.files[0]
    if (!file) return
    if (file.name.toLowerCase().endsWith('.zip')) {
      zipFile.value = file
    } else {
      options.notify.error('仅支持上传 .zip 格式的压缩包')
    }
  }

  async function submitZipImport(): Promise<void> {
    const questionId = options.questionId.value
    if (!zipFile.value || questionId == null || zipUploading.value) return

    zipUploading.value = true
    try {
      const imported = await questionApi.importTestCasesZip(
        questionId,
        zipFile.value,
        zipReplace.value,
        zipFirstAsSample.value,
      )
      options.notify.success(`成功导入 ${imported.length} 组测试点！`)
      showZipModal.value = false
      await options.onImportComplete()
    } catch (error: unknown) {
      options.notify.error(getErrorMessage(error, 'Zip 导入失败，请检查文件格式或网络连接'))
    } finally {
      zipUploading.value = false
    }
  }

  async function exportZip(): Promise<void> {
    const questionId = options.questionId.value
    if (questionId == null || zipExporting.value) return

    zipExporting.value = true
    try {
      await questionApi.exportTestCasesZip(questionId)
      options.notify.success('测试点 Zip 导出成功')
    } catch (error: unknown) {
      options.notify.error(getErrorMessage(error, '导出 Zip 失败，请检查登录状态'))
    } finally {
      zipExporting.value = false
    }
  }

  return {
    testCaseCount,
    sampleCasesCount,
    totalDataBytes,
    testCases,
    loadingCases,
    togglingCaseId,
    settingSamples,
    showZipModal,
    zipFile,
    zipReplace,
    zipFirstAsSample,
    zipUploading,
    isZipDragOver,
    zipExporting,
    refresh,
    toggleSample,
    applySamplePreset,
    openZipModal,
    handleZipFileSelect,
    handleZipDrop,
    submitZipImport,
    exportZip,
  }
}
