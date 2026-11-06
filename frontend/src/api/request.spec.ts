import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import axios, { type AxiosResponse } from 'axios'
import request, { http, ApiError, authChannel } from './request'
import { useUserStore } from '@/stores/user'

describe('request & auth interceptors', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('成功响应 (code 200) 自动解包返回 data 字段', async () => {
    request.defaults.adapter = async (config) => {
      return {
        data: { code: 200, message: 'ok', data: { name: 'KECE' }, timestamp: Date.now() },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse
    }

    const data = await http.get<{ name: string }>('/test')
    expect(data).toEqual({ name: 'KECE' })
  })

  it('业务失败 (code != 200) 抛出 ApiError 携带对应业务码', async () => {
    request.defaults.adapter = async (config) => {
      return {
        data: { code: 4001, message: '参数非法', data: null, timestamp: Date.now() },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse
    }

    await expect(http.get('/bad-request')).rejects.toThrow(ApiError)
    try {
      await http.get('/bad-request')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      expect((err as ApiError).code).toBe(4001)
      expect((err as ApiError).message).toBe('参数非法')
    }
  })

  it('并发 4002 触发 Single-flight 无感刷新，刷新接口只调一次且重放所有等待请求', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'old-token',
      refreshToken: 'valid-refresh-token',
      user: { id: 1, username: 'test', email: 'test@kece.com', roles: ['STUDENT'] },
    })

    let refreshCallCount = 0
    vi.spyOn(axios, 'post').mockImplementation(async (url: string) => {
      if (url === '/api/v1/auth/refresh') {
        refreshCallCount++
        // 模拟一点延迟以确保并发请求排队
        await new Promise((resolve) => setTimeout(resolve, 20))
        return {
          data: {
            code: 200,
            message: 'ok',
            data: {
              token: 'new-refreshed-token',
              refreshToken: 'new-valid-refresh-token',
              user: { id: 1, username: 'test', email: 'test@kece.com', roles: ['STUDENT'] },
            },
            timestamp: Date.now(),
          },
        } as AxiosResponse
      }
      throw new Error(`Unexpected url: ${url}`)
    })

    const executedHeaders: string[] = []
    request.defaults.adapter = async (config) => {
      const authHeader = (config.headers as Record<string, string>)?.Authorization
      executedHeaders.push(authHeader)

      if (authHeader === 'Bearer old-token') {
        return {
          data: { code: 4002, message: 'token expired', data: null, timestamp: Date.now() },
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse
      }

      if (authHeader === 'Bearer new-refreshed-token') {
        return {
          data: { code: 200, message: 'ok', data: `data-for-${config.url}`, timestamp: Date.now() },
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse
      }

      throw new Error(`Unknown auth header: ${authHeader}`)
    }

    const [res1, res2] = await Promise.all([http.get('/req-1'), http.get('/req-2')])

    // 验证刷新接口只调用了一次
    expect(refreshCallCount).toBe(1)
    expect(res1).toBe('data-for-/req-1')
    expect(res2).toBe('data-for-/req-2')
    expect(userStore.token).toBe('new-refreshed-token')
  })

  it('4002 刷新失败时拒绝所有并发等待请求，消除死锁 Promise 并清空凭据广播登出', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'old-token',
      refreshToken: 'expired-refresh-token',
      user: { id: 1, username: 'test', email: 'test@kece.com', roles: ['STUDENT'] },
    })

    let refreshCallCount = 0
    vi.spyOn(axios, 'post').mockImplementation(async (url: string) => {
      if (url === '/api/v1/auth/refresh') {
        refreshCallCount++
        await new Promise((resolve) => setTimeout(resolve, 20))
        return {
          data: {
            code: 4002,
            message: 'Refresh token invalid',
            data: null,
            timestamp: Date.now(),
          },
        } as AxiosResponse
      }
      throw new Error(`Unexpected url: ${url}`)
    })

    request.defaults.adapter = async (config) => {
      return {
        data: { code: 4002, message: 'token expired', data: null, timestamp: Date.now() },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse
    }

    const postMessageSpy = vi.fn()
    if (authChannel) {
      vi.spyOn(authChannel, 'postMessage').mockImplementation(postMessageSpy)
    }
    const storageSetItemSpy = vi.spyOn(Storage.prototype, 'setItem')

    // 并发两个请求
    const p1 = http.get('/req-a')
    const p2 = http.get('/req-b')

    // 关键断言：两个并发请求都必须被 reject，不得挂起死锁
    await expect(p1).rejects.toThrow()
    await expect(p2).rejects.toThrow()

    expect(refreshCallCount).toBe(1)
    expect(userStore.token).toBeNull()
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('kece:logout_event')).toBeTruthy()
    expect(
      storageSetItemSpy.mock.calls.filter(([key]) => key === 'kece:logout_event'),
    ).toHaveLength(1)
    if (authChannel) {
      expect(postMessageSpy).toHaveBeenCalledTimes(1)
    }
  })
})
