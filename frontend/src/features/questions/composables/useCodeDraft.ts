export interface CodeDraftOptions {
  /** 新规范 Key，例如 kece:draft:${userId}:${type}:${questionId}:${lang} */
  targetKey: string
  /** 历史旧规范 Key 列表，例如 ['kece:code_draft:q_1_cpp', 'kece:db_draft_1'] */
  legacyKeys?: string[]
}

export function useCodeDraft(options: CodeDraftOptions) {
  function getDraft(defaultValue = ''): string {
    try {
      const current = localStorage.getItem(options.targetKey)
      if (current !== null) return current

      if (options.legacyKeys && options.legacyKeys.length > 0) {
        for (const oldKey of options.legacyKeys) {
          const legacyVal = localStorage.getItem(oldKey)
          if (legacyVal !== null) {
            // 静默平滑迁移：存入新 Key，并移除旧 Key
            localStorage.setItem(options.targetKey, legacyVal)
            localStorage.removeItem(oldKey)
            return legacyVal
          }
        }
      }
    } catch {
      // 存储异常时返回默认值
    }
    return defaultValue
  }

  function saveDraft(code: string): void {
    try {
      localStorage.setItem(options.targetKey, code)
    } catch {
      // 忽略存储满异常
    }
  }

  function clearDraft(): void {
    try {
      localStorage.removeItem(options.targetKey)
      options.legacyKeys?.forEach((k) => {
        try {
          localStorage.removeItem(k)
        } catch {
          // ignore
        }
      })
    } catch {
      // ignore
    }
  }

  return {
    getDraft,
    saveDraft,
    clearDraft,
  }
}

