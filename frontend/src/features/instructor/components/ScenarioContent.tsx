import type { ReactNode } from 'react'
import type { ReviewConditions, ReviewScenario } from '../api'
import { cardClass } from './styles'
import { cn } from '@/lib/utils'

// 시안(T-SCN-02)의 유도 질문 단계 이름. 서버는 S0~S2 시작 질문만 준다.
const SUPPORT_LEVELS: { key: string; label: string }[] = [
  { key: 'S0', label: '지원 없음' },
  { key: 'S1', label: '가벼운 힌트' },
  { key: 'S2', label: '선택지 제시' },
]

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-t border-[var(--instructor-border-subtle)] px-5 py-4">
      <h3 className="mb-2 text-sm font-semibold">{title}</h3>
      {children}
    </section>
  )
}

function TextList({ items, empty }: { items: string[]; empty: string }) {
  if (items.length === 0) return <p className="text-sm text-[var(--instructor-text-subtle)]">{empty}</p>
  return (
    <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-[var(--instructor-text-muted)]">
      {items.map((item, index) => (
        <li key={`${index}-${item}`}>{item}</li>
      ))}
    </ul>
  )
}

/** 시나리오 내용(왼쪽)의 섹션들. replace 로 넘긴 섹션은 그 자리에 편집 칸을 대신 보인다. */
function ScenarioSections({
  scenario,
  replace = {},
}: {
  scenario: ReviewScenario
  replace?: Partial<Record<'summary' | 'microGoals', ReactNode>>
}) {
  return (
    <>
      <Section title="상황">
        {replace.summary ?? <p className="text-sm text-[var(--instructor-text-muted)]">{scenario.summary}</p>}
      </Section>
      <Section title="인물">
        {scenario.characters.length === 0 ? (
          <p className="text-sm text-[var(--instructor-text-subtle)]">등장인물이 없습니다.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {scenario.characters.map((character) => (
              <li key={character.id} className="rounded-full bg-[var(--instructor-surface-muted)] px-3 py-1 text-xs">
                {character.role}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section title="고정 사실">
        <TextList items={scenario.scenarioFacts} empty="고정 사실이 없습니다." />
      </Section>
      <Section title="작은 목표 · 학습 증거">
        {replace.microGoals ?? (
          <ol className="flex flex-col gap-2 text-sm">
            {scenario.microGoals.map((goal, index) => (
              <li key={goal.microGoalId}>
                <p>
                  {index + 1}. {goal.description}
                </p>
                <p className="text-xs text-[var(--instructor-text-subtle)]">필요 증거: {goal.requiredEvidence.join(' · ')}</p>
              </li>
            ))}
          </ol>
        )}
      </Section>
    </>
  )
}

/** 지원 단계별 시작 질문(시안 오른쪽 "유도 질문"). 질문마다 이어지는 작은 목표 번호를 함께 보인다. */
function OpeningPrompts({ scenario }: { scenario: ReviewScenario }) {
  const goalNumber = (key: string) => {
    const index = scenario.microGoals.findIndex((goal) => goal.microGoalId === scenario.startingQuestionGoalIds[key])
    return index < 0 ? null : index + 1
  }
  return (
    <div className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
      <h3 className="text-sm font-semibold">S0~S2 유도 질문</h3>
      {SUPPORT_LEVELS.map(({ key, label }) => {
        const prompt = scenario.startingQuestions[key]
        const number = goalNumber(key)
        return (
          <div key={key} className="rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border-subtle)] px-4 py-3">
            <p className="mb-1 text-xs text-[var(--instructor-text-subtle)]">
              <span className="mr-2 rounded-full bg-[var(--instructor-surface-muted)] px-2 py-0.5 font-semibold">{key}</span>
              {label}
              {number !== null && ` · 작은 목표 ${number}`}
            </p>
            <p className="text-sm">{prompt?.trim() ? prompt : '(유도 없음)'}</p>
          </div>
        )
      })}
      <p className="rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-4 py-3 text-xs text-[var(--instructor-text-subtle)]">
        검토 시 확인: 금지 표현 포함 여부, 고정 사실과 상황의 충돌, 증거 판정 가능성
      </p>
    </div>
  )
}

/** 이번 활동의 목표와 조건. 후보가 이 조건에 맞는지 강사가 같이 보며 판단한다. */
function ReviewConditionsCard({ conditions }: { conditions: ReviewConditions }) {
  const characters = conditions.characters
    .map((character) => (typeof character.role === 'string' ? character.role : null))
    .filter((role): role is string => role !== null)
  const rows: [string, string][] = [
    ['목표', conditions.title],
    ['난이도', conditions.scenarioLevel],
    ['상황', conditions.situationType ?? '지정 안 함'],
    ['등장인물', characters.length > 0 ? characters.join(', ') : '지정 안 함'],
    ['필수 행동', conditions.requiredElements.length > 0 ? conditions.requiredElements.join(', ') : '지정 안 함'],
    ['금지 표현', conditions.forbiddenExpressions.length > 0 ? conditions.forbiddenExpressions.join(', ') : '없음'],
  ]
  return (
    <div className={cn(cardClass, 'p-5')}>
      <h3 className="mb-3 text-sm font-semibold">이번 활동 조건</h3>
      <dl className="flex flex-col gap-2 text-sm">
        {rows.map(([label, value]) => (
          <div key={label} className="grid grid-cols-[72px_minmax(0,1fr)] gap-2">
            <dt className="text-[var(--instructor-text-subtle)]">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

export { OpeningPrompts, ReviewConditionsCard, ScenarioSections }
