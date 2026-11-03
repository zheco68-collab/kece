export type QuestionType = 'ALGO' | 'DB' | 'OBJECTIVE'

export interface QuestionCandidate {
  questionType: QuestionType
  questionId: number
  title: string
  difficulty?: string
}
