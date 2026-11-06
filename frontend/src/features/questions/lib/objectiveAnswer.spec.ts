import { describe, expect, it } from 'vitest'
import {
  isObjectiveOptionSelected,
  normalizeJudgeAnswer,
  objectiveOptionLetter,
  objectiveOptionText,
  parseMultipleAnswer,
  toggleMultipleAnswer,
  updateObjectiveAnswer,
} from './objectiveAnswer'

describe('objectiveAnswer helpers', () => {
  it('标准化选项编号与带前缀的选项文本', () => {
    expect(objectiveOptionLetter(0)).toBe('A')
    expect(objectiveOptionLetter(3)).toBe('D')
    expect(objectiveOptionText('B. 第二项')).toBe('第二项')
  })

  it('将多选答案去重、排序，并支持无逗号的历史答案', () => {
    expect(parseMultipleAnswer('c,A,B,A')).toEqual(['A', 'B', 'C'])
    expect(parseMultipleAnswer('BD')).toEqual(['B', 'D'])
    expect(toggleMultipleAnswer(['A', 'C'], 'b')).toEqual(['A', 'B', 'C'])
    expect(toggleMultipleAnswer(['A', 'B'], 'a')).toEqual(['B'])
  })

  it('按题型计算选中态与更新后的答案', () => {
    expect(isObjectiveOptionSelected('A,C', 'C', 'MULTIPLE')).toBe(true)
    expect(isObjectiveOptionSelected('B', 'B', 'SINGLE')).toBe(true)
    expect(updateObjectiveAnswer('A,C', 'B', 'MULTIPLE')).toBe('A,B,C')
    expect(updateObjectiveAnswer('A', 'D', 'SINGLE')).toBe('D')
  })

  it('兼容判断题历史保存值', () => {
    expect(normalizeJudgeAnswer('TRUE')).toBe('对')
    expect(normalizeJudgeAnswer('✗')).toBe('错')
    expect(normalizeJudgeAnswer('unknown')).toBeNull()
  })
})
