import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCountdown } from './useCountdown'

describe('useCountdown composable', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-06T00:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('基于绝对结束时间推进，停滞后会立即校正而非逐秒慢走', () => {
    const { remainingSec, isUrgent, start } = useCountdown()

    start(305)
    expect(remainingSec.value).toBe(305)
    expect(isUrgent.value).toBe(false)

    vi.advanceTimersByTime(6_000)
    expect(remainingSec.value).toBe(299)
    expect(isUrgent.value).toBe(true)
  })

  it('到期时停止计时并且只通知一次', () => {
    const onExpire = vi.fn()
    const { isExpired, remainingSec, start } = useCountdown({ onExpire })

    start(1)
    vi.advanceTimersByTime(1_000)
    expect(remainingSec.value).toBe(0)
    expect(isExpired.value).toBe(true)
    expect(onExpire).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(10_000)
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('不限时场景不创建剩余秒数，也不会触发到期回调', () => {
    const onExpire = vi.fn()
    const { isExpired, remainingSec, start } = useCountdown({ onExpire })

    start(null)
    vi.advanceTimersByTime(60_000)

    expect(remainingSec.value).toBeNull()
    expect(isExpired.value).toBe(false)
    expect(onExpire).not.toHaveBeenCalled()
  })
})
