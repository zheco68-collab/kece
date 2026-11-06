import { beforeEach, describe, expect, it } from 'vitest'
import { useCodeDraft } from './useCodeDraft'

describe('useCodeDraft composable', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('读取现有 targetKey 草稿', () => {
    localStorage.setItem('kece:draft:u1:algo:10:cpp', 'int main() {}')
    const { getDraft, saveDraft, clearDraft } = useCodeDraft({
      targetKey: 'kece:draft:u1:algo:10:cpp',
      legacyKeys: ['kece:code_draft:q_10_cpp'],
    })

    expect(getDraft('default')).toBe('int main() {}')
    saveDraft('int main() { return 0; }')
    expect(localStorage.getItem('kece:draft:u1:algo:10:cpp')).toBe('int main() { return 0; }')
    clearDraft()
    expect(localStorage.getItem('kece:draft:u1:algo:10:cpp')).toBeNull()
  })

  it('旧 Key 存在时静默无感迁移至 targetKey 并清理旧 Key', () => {
    localStorage.setItem('kece:code_draft:q_10_cpp', 'legacy code snippet')

    const { getDraft } = useCodeDraft({
      targetKey: 'kece:draft:u1:algo:10:cpp',
      legacyKeys: ['kece:code_draft:q_10_cpp', 'kece:old_draft_10'],
    })

    // 首次读取触发静默迁移
    const code = getDraft('default')
    expect(code).toBe('legacy code snippet')

    // 验证新 Key 已写入，旧 Key 已被移除
    expect(localStorage.getItem('kece:draft:u1:algo:10:cpp')).toBe('legacy code snippet')
    expect(localStorage.getItem('kece:code_draft:q_10_cpp')).toBeNull()
  })

  it('均无草稿时返回 defaultCode', () => {
    const { getDraft } = useCodeDraft({
      targetKey: 'kece:draft:u1:algo:99:cpp',
      legacyKeys: ['kece:code_draft:q_99_cpp'],
    })

    expect(getDraft('template code')).toBe('template code')
  })
})

