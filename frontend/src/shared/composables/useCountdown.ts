import { computed, getCurrentInstance, onBeforeUnmount, ref } from 'vue'

export interface CountdownOptions {
  /** 到达 0 秒时只触发一次；页面决定如何锁定或提示。 */
  onExpire?: () => void
}

/**
 * 以绝对结束时间计算剩余时间的倒计时。
 *
 * 调用方每次从服务端取得新的 `remainingSeconds` 后调用 `start` 即可校时；
 * 不限时传入 `null`/`undefined`。本模块不处理交卷、提示或页面状态。
 */
export function useCountdown(options: CountdownOptions = {}) {
  const remainingSec = ref<number | null>(null)
  const isExpired = computed(() => remainingSec.value !== null && remainingSec.value <= 0)
  const isUrgent = computed(
    () => remainingSec.value !== null && remainingSec.value > 0 && remainingSec.value < 300,
  )

  let timer: ReturnType<typeof setInterval> | null = null
  let endAt: number | null = null
  let expiryHandled = false

  function stop(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function notifyExpired(): void {
    if (expiryHandled) return
    expiryHandled = true
    options.onExpire?.()
  }

  function tick(): void {
    if (endAt === null) {
      remainingSec.value = null
      return
    }

    const seconds = Math.max(0, Math.ceil((endAt - Date.now()) / 1000))
    remainingSec.value = seconds
    if (seconds === 0) {
      stop()
      notifyExpired()
    }
  }

  function start(seconds: number | null | undefined): void {
    stop()
    expiryHandled = false

    if (seconds === null || seconds === undefined) {
      endAt = null
      remainingSec.value = null
      return
    }

    if (!Number.isFinite(seconds) || seconds <= 0) {
      endAt = Date.now()
      remainingSec.value = 0
      notifyExpired()
      return
    }

    endAt = Date.now() + Math.ceil(seconds) * 1000
    tick()
    timer = setInterval(tick, 1000)
  }

  if (getCurrentInstance()) {
    onBeforeUnmount(stop)
  }

  return {
    remainingSec,
    isExpired,
    isUrgent,
    start,
    stop,
  }
}
