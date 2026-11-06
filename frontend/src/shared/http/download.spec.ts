import { describe, it, expect, vi, beforeEach } from 'vitest'
import { downloadFile, openDownloadFile, requestDownloadFile } from './download'
import { setActivePinia, createPinia } from 'pinia'
import { useUserStore } from '@/stores/user'

describe('downloadFile 统一下载器', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('下载 JSON 错误响应时应抛出 ApiError，不应创建 Blob 下载', async () => {
    const userStore = useUserStore()
    userStore.token = 'test-token'

    const mockResponse = {
      ok: false,
      status: 400,
      headers: {
        get: (h: string) => (h.toLowerCase() === 'content-type' ? 'application/json;charset=UTF-8' : null),
      },
      json: async () => ({ code: 4001, message: '参数非法' }),
    } as unknown as Response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse))

    await expect(downloadFile({ url: '/api/test' })).rejects.toThrow('参数非法')
  })

  it('成功下载二进制文件时应正确解析 UTF-8 文件名并完成链接回收', async () => {
    const userStore = useUserStore()
    userStore.token = 'test-token'

    const createObjectURLMock = vi.fn().mockReturnValue('blob:mock-url')
    const revokeObjectURLMock = vi.fn()
    window.URL.createObjectURL = createObjectURLMock
    window.URL.revokeObjectURL = revokeObjectURLMock

    const appendChildMock = vi.spyOn(document.body, 'appendChild').mockImplementation((node) => node)
    const removeChildMock = vi.spyOn(document.body, 'removeChild').mockImplementation((node) => node)

    const mockResponse = {
      ok: true,
      status: 200,
      headers: {
        get: (h: string) => {
          if (h.toLowerCase() === 'content-type') return 'application/zip'
          if (h.toLowerCase() === 'content-disposition') return "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E7%94%A8%E4%BE%8B.zip"
          return null
        },
      },
      blob: async () => new Blob(['dummy content']),
    } as unknown as Response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse))

    await downloadFile({ url: '/api/v1/download/zip', defaultFilename: 'fallback.zip' })

    expect(createObjectURLMock).toHaveBeenCalled()
    expect(appendChildMock).toHaveBeenCalled()
    expect(removeChildMock).toHaveBeenCalled()
    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:mock-url')
  })

  it('预览 PDF 时通过已鉴权的 Blob 打开新页，不把令牌拼接到 URL', async () => {
    const userStore = useUserStore()
    userStore.token = 'test-token'
    const replace = vi.fn()
    const previewWindow = { location: { replace }, close: vi.fn() }
    vi.spyOn(window, 'open').mockReturnValue(previewWindow as unknown as Window)
    window.URL.createObjectURL = vi.fn().mockReturnValue('blob:preview-url')
    window.URL.revokeObjectURL = vi.fn()

    const mockResponse = {
      ok: true,
      status: 200,
      headers: { get: () => 'application/pdf' },
      blob: async () => new Blob(['pdf']),
    } as unknown as Response
    const fetchMock = vi.fn().mockResolvedValue(mockResponse)
    vi.stubGlobal('fetch', fetchMock)

    await openDownloadFile({ url: '/api/v1/exams/1/export?format=pdf' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/exams/1/export?format=pdf',
      expect.objectContaining({ headers: { Authorization: 'Bearer test-token' } }),
    )
    expect(replace).toHaveBeenCalledWith('blob:preview-url')
  })

  it('请求文件时返回解析后的文件名，供下载和预览共用', async () => {
    const mockResponse = {
      ok: true,
      status: 200,
      headers: {
        get: (header: string) =>
          header.toLowerCase() === 'content-disposition'
            ? 'attachment; filename=exam.pdf'
            : 'application/pdf',
      },
      blob: async () => new Blob(['pdf']),
    } as unknown as Response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse))

    const file = await requestDownloadFile({ url: '/api/export', defaultFilename: 'fallback.pdf' })
    expect(file.filename).toBe('exam.pdf')
    expect(file.blob).toBeInstanceOf(Blob)
  })
})

