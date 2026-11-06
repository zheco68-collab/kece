import { computed } from 'vue'
import { useRoute } from 'vue-router'

export function useQuestionNavContext() {
  const route = useRoute()

  const setId = computed(() => {
    const raw = route?.query?.setId
    return raw ? String(raw) : null
  })

  const itemId = computed(() => {
    const raw = route?.query?.itemId
    return raw ? String(raw) : null
  })

  const practiceCode = computed(() => {
    const raw = route?.query?.practiceCode
    return raw ? String(raw).toUpperCase() : null
  })

  const pqId = computed(() => {
    const raw = route?.query?.pqId
    return raw ? String(raw) : null
  })

  const contextQuery = computed<Record<string, string>>(() => {
    const q: Record<string, string> = {}
    if (practiceCode.value) {
      q.practiceCode = practiceCode.value
      if (pqId.value) q.pqId = pqId.value
    } else if (setId.value) {
      q.setId = setId.value
      if (itemId.value) q.itemId = itemId.value
    }
    return q
  })

  const hasContext = computed(() => !!practiceCode.value || !!setId.value)

  function getBackRoute(fallbackPath = '/questions'): { path: string; query?: Record<string, string> } {
    if (practiceCode.value) {
      return {
        path: `/practice/${encodeURIComponent(practiceCode.value)}`,
        query: pqId.value ? { pqId: pqId.value } : undefined,
      }
    }
    if (setId.value) {
      return {
        path: `/problem-sets/${encodeURIComponent(setId.value)}`,
        query: itemId.value ? { itemId: itemId.value } : undefined,
      }
    }
    return { path: fallbackPath }
  }

  return {
    setId,
    itemId,
    practiceCode,
    pqId,
    contextQuery,
    hasContext,
    getBackRoute,
  }
}

