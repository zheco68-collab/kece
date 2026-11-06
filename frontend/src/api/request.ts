import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { useUserStore } from '@/stores/user'
import type { AuthResult } from '@/types'

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 携带业务错误码与 HTTP 状态码的异常，供调用方按 code / status 区分处理。 */
export class ApiError extends Error {
  code: number
  status?: number

  constructor(code: number, message: string, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

const request: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

// 跨 Tab 登出广播机制
export const authChannel =
  typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('kece_auth_channel') : null

export function notifyCrossTabLogout(): void {
  try {
    authChannel?.postMessage({ type: 'LOGOUT' })
  } catch {
    // 忽略不支持或已关闭错误
  }
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('kece:logout_event', String(Date.now()))
    }
  } catch {
    // 忽略存储限制错误
  }
}

// 请求拦截：附加 JWT
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    try {
      const userStore = useUserStore()
      if (userStore.token) {
        config.headers.Authorization = `Bearer ${userStore.token}`
      }
    } catch {
      // 允许在 store 初始化前或纯环境测试中降级
    }
    return config
  },
  (error) => Promise.reject(error),
)

// Single-flight 刷新状态锁与等待者队列
let refreshPromise: Promise<string> | null = null
let refreshSubscribers: Array<{
  resolve: (token: string) => void
  reject: (err: unknown) => void
}> = []

async function getValidAccessToken(refreshToken: string): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const refreshRes = await axios.post<ApiResponse<AuthResult>>(
          '/api/v1/auth/refresh',
          { refreshToken },
          { timeout: 10000 },
        )
        if (refreshRes.data?.code === 200 && refreshRes.data.data?.token) {
          const newAuth = refreshRes.data.data
          try {
            const userStore = useUserStore()
            userStore.setAuth(newAuth)
          } catch {
            // store 未激活时忽略
          }
          const newToken = newAuth.token
          refreshSubscribers.forEach((cb) => cb.resolve(newToken))
          return newToken
        }
        const err = new ApiError(
          refreshRes.data?.code || 4002,
          refreshRes.data?.message || '会话已过期，请重新登录',
          refreshRes.status,
        )
        throw err
      } catch (err) {
        refreshSubscribers.forEach((cb) => cb.reject(err))
        try {
          const userStore = useUserStore()
          userStore.clearAuth()
        } catch {
          // store 未激活时忽略
        }
        notifyCrossTabLogout()
        throw err
      } finally {
        refreshSubscribers = []
        refreshPromise = null
      }
    })()
    return refreshPromise
  }

  return new Promise((resolve, reject) => {
    refreshSubscribers.push({ resolve, reject })
  })
}

interface CustomRequestConfig extends AxiosRequestConfig {
  _retry?: boolean
}

async function handleTokenExpiredRetry(originalConfig: CustomRequestConfig): Promise<unknown> {
  let refreshToken: string | null = null
  try {
    const userStore = useUserStore()
    refreshToken = userStore.refreshToken
  } catch {
    // 忽略
  }

  const isAuthUrl =
    !!originalConfig.url &&
    (originalConfig.url.includes('/auth/login') ||
      originalConfig.url.includes('/auth/register') ||
      originalConfig.url.includes('/auth/refresh'))

  if (!refreshToken || isAuthUrl || originalConfig._retry) {
    try {
      const userStore = useUserStore()
      userStore.clearAuth()
    } catch {
      // 忽略
    }
    notifyCrossTabLogout()
    throw new ApiError(4002, '登录已过期，请重新登录')
  }

  originalConfig._retry = true
  const newToken = await getValidAccessToken(refreshToken)
  originalConfig.headers = originalConfig.headers ?? {}
  originalConfig.headers.Authorization = `Bearer ${newToken}`
  return request(originalConfig)
}

// 响应拦截：解包统一响应体与自动刷新 Token
request.interceptors.response.use(
  async (response: AxiosResponse<ApiResponse>) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) {
        return body.data as never
      }

      if (body.code === 4002) {
        const originalConfig = response.config as CustomRequestConfig
        return (await handleTokenExpiredRetry(originalConfig)) as never
      }

      return Promise.reject(new ApiError(body.code, body.message || '请求失败', response.status))
    }
    return response.data as never
  },
  async (error) => {
    const originalConfig = error?.config as CustomRequestConfig | undefined
    const responseData = error?.response?.data
    const status = error?.response?.status

    const is4002 =
      (responseData && typeof responseData === 'object' && responseData.code === 4002) ||
      status === 401

    if (is4002 && originalConfig && !originalConfig._retry) {
      return (await handleTokenExpiredRetry(originalConfig)) as never
    }

    if (responseData && typeof responseData === 'object' && 'code' in responseData) {
      return Promise.reject(
        new ApiError(responseData.code, responseData.message || '服务器业务异常', status),
      )
    }

    const code = status ?? 5000
    const message = error?.message || '网络连接异常，请检查网络'
    return Promise.reject(new ApiError(code, message, status))
  },
)

/** 类型化请求助手：响应拦截器已解包，实际返回业务 data。 */
export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig) =>
    request.get(url, config) as unknown as Promise<T>,
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request.post(url, data, config) as unknown as Promise<T>,
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request.put(url, data, config) as unknown as Promise<T>,
  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request.patch(url, data, config) as unknown as Promise<T>,
  delete: <T>(url: string, config?: AxiosRequestConfig) =>
    request.delete(url, config) as unknown as Promise<T>,
}

export default request
