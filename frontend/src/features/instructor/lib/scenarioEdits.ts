import type { ReviewScenario, ScenarioEdits } from '../api'

/** 서버가 받는 작은 목표 필요 증거 개수. 명시한 배열은 1~3개다. */
export const MAX_EVIDENCE = 3

export type MicroGoalForm = { microGoalId: string; description: string; evidence: string }

/** 수정 화면의 칸. 필요 증거는 한 줄에 하나다. */
export type ScenarioForm = { title: string; summary: string; microGoals: MicroGoalForm[] }

export function toForm(scenario: ReviewScenario): ScenarioForm {
  return {
    title: scenario.title,
    summary: scenario.summary,
    microGoals: scenario.microGoals.map((goal) => ({
      microGoalId: goal.microGoalId,
      description: goal.description,
      evidence: goal.requiredEvidence.join('\n'),
    })),
  }
}

function evidenceLines(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
}

/** 원본과 다른 칸만 싣는다. 바뀐 것이 없으면 undefined 라 확정 요청에 edits 를 넣지 않는다. */
export function toEdits(scenario: ReviewScenario, form: ScenarioForm): ScenarioEdits | undefined {
  const edits: ScenarioEdits = {}
  if (form.title.trim() !== scenario.title) edits.title = form.title.trim()
  if (form.summary.trim() !== scenario.summary) edits.summary = form.summary.trim()
  const microGoals = form.microGoals.flatMap((goal) => {
    const original = scenario.microGoals.find((g) => g.microGoalId === goal.microGoalId)
    if (!original) return []
    const description = goal.description.trim()
    const evidence = evidenceLines(goal.evidence)
    const patch: NonNullable<ScenarioEdits['microGoals']>[number] = { microGoalId: goal.microGoalId }
    if (description !== original.description) patch.description = description
    if (evidence.join('\n') !== original.requiredEvidence.join('\n')) patch.requiredEvidence = evidence
    return patch.description === undefined && patch.requiredEvidence === undefined ? [] : [patch]
  })
  if (microGoals.length > 0) edits.microGoals = microGoals
  return Object.keys(edits).length > 0 ? edits : undefined
}

/** 확인 화면에 보일 모습. 확정 요청에 실을 수정 내용을 그대로 덮어, 보이는 내용과 승인되는 내용이 같게 한다. */
export function applyEdits(scenario: ReviewScenario, edits: ScenarioEdits | undefined): ReviewScenario {
  if (!edits) return scenario
  return {
    ...scenario,
    title: edits.title ?? scenario.title,
    summary: edits.summary ?? scenario.summary,
    microGoals: scenario.microGoals.map((goal) => {
      const edited = edits.microGoals?.find((g) => g.microGoalId === goal.microGoalId)
      if (!edited) return goal
      return {
        ...goal,
        description: edited.description ?? goal.description,
        requiredEvidence: edited.requiredEvidence ?? goal.requiredEvidence,
      }
    }),
  }
}

/** 서버가 거절할 입력을 미리 알려 준다. 빈 칸이 없고 필요 증거가 1~3개여야 한다. */
export function formProblems(form: ScenarioForm): string[] {
  const problems: string[] = []
  if (!form.title.trim()) problems.push('제목을 적어 주세요.')
  if (!form.summary.trim()) problems.push('상황을 적어 주세요.')
  form.microGoals.forEach((goal, index) => {
    if (!goal.description.trim()) problems.push(`작은 목표 ${index + 1}의 설명을 적어 주세요.`)
    const count = evidenceLines(goal.evidence).length
    if (count < 1 || count > MAX_EVIDENCE) {
      problems.push(`작은 목표 ${index + 1}의 필요 증거는 1~${MAX_EVIDENCE}개여야 합니다.`)
    }
  })
  return problems
}

/** "제목·상황·작은 목표 2개"처럼 무엇을 고쳤는지 요약한다. */
export function describeEdits(edits: ScenarioEdits | undefined): string {
  if (!edits) return '내용 변경 없음'
  const parts = [
    edits.title !== undefined ? '제목' : null,
    edits.summary !== undefined ? '상황' : null,
    edits.microGoals ? `작은 목표 ${edits.microGoals.length}개` : null,
  ].filter(Boolean)
  return `${parts.join('·')} 수정 · 새 버전으로 승인`
}
