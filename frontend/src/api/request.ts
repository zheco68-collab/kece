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

/** 携带业务错误码的异常，供调用方按 code 区分处理。 */
export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

const request: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

// 请求拦截：附加 JWT
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

// 无感刷新令牌控制状态与并发队列
let isRefreshing = false
let refreshSubscribers: Array<(token: string) => void> = []

function onRefreshed(newToken: string) {
  refreshSubscribers.forEach((cb) => cb(newToken))
  refreshSubscribers = []
}

function onRefreshFailed() {
  refreshSubscribers = []
}

interface CustomRequestConfig extends AxiosRequestConfig {
  _retry?: boolean
}

// 响应拦截：解包统一响应体与自动刷新 Token
request.interceptors.response.use(
  async (response: AxiosResponse<ApiResponse>) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 200) {
        return body.data as never
      }

      const originalConfig = response.config as CustomRequestConfig

      // 4002 代表 Token 过期或未登录
      if (body.code === 4002) {
        const userStore = useUserStore()
        const isAuthUrl =
          originalConfig.url?.includes('/auth/login') ||
          originalConfig.url?.includes('/auth/register') ||
          originalConfig.url?.includes('/auth/refresh')

        if (!userStore.refreshToken || isAuthUrl || originalConfig._retry) {
          userStore.clearAuth()
          return Promise.reject(new ApiError(body.code, body.message || '登录已过期，请重新登录'))
        }

        originalConfig._retry = true

        if (!isRefreshing) {
          isRefreshing = true
          try {
            // 使用原生 axios 请求 refresh 接口，避免拦截器死循环
            const refreshRes = await axios.post<ApiResponse<AuthResult>>(
              '/api/v1/auth/refresh',
              {
                refreshToken: userStore.refreshToken,
              },
              { timeout: 10000 },
            )

            if (refreshRes.data?.code === 200 && refreshRes.data.data) {
              const newAuth = refreshRes.data.data
              userStore.setAuth(newAuth)
              onRefreshed(newAuth.token)

              originalConfig.headers = originalConfig.headers ?? {}
              originalConfig.headers.Authorization = `Bearer ${newAuth.token}`
              return (await request(originalConfig)) as never
            } else {
              userStore.clearAuth()
              onRefreshFailed()
              return Promise.reject(
                new ApiError(refreshRes.data?.code || 4002, refreshRes.data?.message || '会话已过期'),
              )
            }
          } catch (err) {
            userStore.clearAuth()
            onRefreshFailed()
            return Promise.reject(err)
          } finally {
            isRefreshing = false
          }
        }

        // 正在刷新中，加入等待队列
        return new Promise((resolve, reject) => {
          refreshSubscribers.push(async (newToken: string) => {
            try {
              originalConfig.headers = originalConfig.headers ?? {}
              originalConfig.headers.Authorization = `Bearer ${newToken}`
              resolve(await request(originalConfig))
            } catch (err) {
              reject(err)
            }
          })
        })
      }

      return Promise.reject(new ApiError(body.code, body.message || '请求失败'))
    }
    return response.data as never
  },
  async (error) => {
    const originalConfig = error?.config as CustomRequestConfig | undefined
    const responseData = error?.response?.data
    const status = error?.response?.status

    // 如果后端携带 ApiResponse 结构
    if (responseData && typeof responseData === 'object' && 'code' in responseData) {
      if (responseData.code === 4002 && originalConfig && !originalConfig._retry) {
        const userStore = useUserStore()
        if (userStore.refreshToken && !originalConfig.url?.includes('/auth/')) {
          originalConfig._retry = true
          if (!isRefreshing) {
            isRefreshing = true
            try {
              const refreshRes = await axios.post<ApiResponse<AuthResult>>(
                '/api/v1/auth/refresh',
                {
                  refreshToken: userStore.refreshToken,
                },
                { timeout: 10000 },
              )
              if (refreshRes.data?.code === 200 && refreshRes.data.data) {
                const newAuth = refreshRes.data.data
                userStore.setAuth(newAuth)
                onRefreshed(newAuth.token)
                originalConfig.headers = originalConfig.headers ?? {}
                originalConfig.headers.Authorization = `Bearer ${newAuth.token}`
                return (await request(originalConfig)) as never
              }
            } catch {
              userStore.clearAuth()
              onRefreshFailed()
            } finally {
              isRefreshing = false
            }
          }
        }
      }
      return Promise.reject(new ApiError(responseData.code, responseData.message || '服务器业务异常'))
    }

    const code = status ?? 5000
    const message = error?.message || '网络连接异常，请检查网络'
    return Promise.reject(new ApiError(code, message))
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
