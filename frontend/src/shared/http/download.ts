import { useUserStore } from '@/stores/user'
import { ApiError } from '@/api/request'

export interface DownloadOptions {
  url: string
  method?: 'GET' | 'POST'
  body?: unknown
  defaultFilename?: string
  headers?: Record<string, string>
}

export interface DownloadedFile {
  blob: Blob
  filename: string
}

function resolveFilename(contentDisposition: string | null, fallback: string): string {
  if (!contentDisposition) return fallback

  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1])
    } catch {
      return utf8Match[1]
    }
  }

  const basicMatch = contentDisposition.match(/filename="?([^";]+)"?/i)
  if (!basicMatch?.[1]) return fallback
  try {
    return decodeURIComponent(basicMatch[1])
  } catch {
    return basicMatch[1]
  }
}

/** 统一处理鉴权、错误响应与文件名解析；不直接操作 DOM。 */
export async function requestDownloadFile(options: DownloadOptions): Promise<DownloadedFile> {
  const userStore = useUserStore()
  const headers: Record<string, string> = { ...(options.headers || {}) }

  if (userStore.token) {
    headers.Authorization = `Bearer ${userStore.token}`
  }

  const fetchOptions: RequestInit = {
    method: options.method || 'GET',
    headers,
  }

  if (options.body && options.method === 'POST') {
    if (typeof options.body === 'string') {
      fetchOptions.body = options.body
    } else {
      headers['Content-Type'] = 'application/json'
      fetchOptions.body = JSON.stringify(options.body)
    }
  }

  const response = await fetch(options.url, fetchOptions)

  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    let errMsg = '下载失败'
    let code = response.status
    try {
      const json: unknown = await response.json()
      if (typeof json === 'object' && json !== null) {
        const payload = json as { code?: unknown; message?: unknown }
        if (typeof payload.message === 'string' && payload.message.trim()) errMsg = payload.message
        if (typeof payload.code === 'number') code = payload.code
      }
    } catch {
      // JSON 错误响应解析失败时保留通用提示。
    }
    throw new ApiError(code, errMsg)
  }

  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      userStore.clearAuth()
      throw new ApiError(4002, '登录已过期，请重新登录')
    }
    throw new ApiError(response.status, `文件下载失败 (HTTP ${response.status})`)
  }

  return {
    blob: await response.blob(),
    filename: resolveFilename(response.headers.get('content-disposition'), options.defaultFilename || 'download'),
  }
}

/**
 * 统一下载器：
 * 1. 自动附加当前登录 Bearer Token
 * 2. 识别 application/json 错误响应，避免误将错误信息保存为损坏的 zip/docx/csv
 * 3. 智能解析 Content-Disposition 中的 RFC 5987 / UTF-8 文件名
 * 4. 自动管理 a 标签点击与 ObjectURL 回收
 */
export async function downloadFile(options: DownloadOptions): Promise<void> {
  const file = await requestDownloadFile(options)
  const blobUrl = window.URL.createObjectURL(file.blob)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = file.filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(blobUrl)
}

/**
 * 用已鉴权的 Blob 打开新页预览，避免将令牌附在预览 URL 上。
 * 先同步打开空白页以保留用户点击带来的弹窗许可，下载失败时会自动关闭该页。
 */
export async function openDownloadFile(options: DownloadOptions): Promise<void> {
  const previewWindow = window.open('', '_blank')
  try {
    const file = await requestDownloadFile(options)
    const blobUrl = window.URL.createObjectURL(file.blob)
    if (previewWindow) {
      previewWindow.location.replace(blobUrl)
    } else {
      window.open(blobUrl, '_blank')
    }
    window.setTimeout(() => window.URL.revokeObjectURL(blobUrl), 60_000)
  } catch (cause) {
    previewWindow?.close()
    throw cause
  }
}

