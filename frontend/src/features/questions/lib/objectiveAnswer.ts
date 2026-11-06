import type { ObjectiveType } from '@/types'

type ObjectiveAnswerType = ObjectiveType | string | null | undefined

export function objectiveOptionLetter(index: number): string {
  return String.fromCharCode(65 + index)
}

export function objectiveOptionText(option: string): string {
  return option.replace(/^[A-Za-z][.、)）:：]\s*/, '')
}

export function normalizeJudgeAnswer(value: string | null | undefined): '对' | '错' | null {
  if (!value) return null
  const normalized = value.trim().toUpperCase()
  if (['对', 'TRUE', 'T', '1', '正确', 'YES', '√', '✓'].includes(normalized)) return '对'
  if (['错', 'FALSE', 'F', '0', '错误', 'NO', '×', '✕', '✗'].includes(normalized)) return '错'
  return null
}

export function parseMultipleAnswer(value: string | null | undefined): string[] {
  if (!value) return []
  const normalized = value.trim().toUpperCase()
  const rawChoices = normalized.includes(',') ? normalized.split(',') : normalized.split('')
  return [...new Set(rawChoices.map((choice) => choice.trim()).filter(Boolean))].sort()
}

export function toggleMultipleAnswer(current: readonly string[], letter: string): string[] {
  const normalized = letter.trim().toUpperCase()
  if (!normalized) return [...current]
  const next = new Set(current.map((choice) => choice.trim().toUpperCase()).filter(Boolean))
  if (next.has(normalized)) {
    next.delete(normalized)
  } else {
    next.add(normalized)
  }
  return [...next].sort()
}

export function isObjectiveOptionSelected(
  answer: string | null | undefined,
  letter: string,
  type: ObjectiveAnswerType,
): boolean {
  const normalizedLetter = letter.trim().toUpperCase()
  if (type === 'MULTIPLE') {
    return parseMultipleAnswer(answer).includes(normalizedLetter)
  }
  return (answer ?? '').trim().toUpperCase() === normalizedLetter
}

export function updateObjectiveAnswer(
  answer: string | null | undefined,
  letter: string,
  type: ObjectiveAnswerType,
): string {
  if (type !== 'MULTIPLE') return letter.trim().toUpperCase()
  return toggleMultipleAnswer(parseMultipleAnswer(answer), letter).join(',')
}
