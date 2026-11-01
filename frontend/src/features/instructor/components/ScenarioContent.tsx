import type { ReactNode } from 'react'
import type { Scenario } from '../api'
import { cardClass } from './styles'
import { cn } from '@/lib/utils'

// 시안(T-SCN-02)의 유도 질문 단계 이름. S3 은 저장된 시작 질문이 없으면 보이지 않는다.
const SUPPORT_LEVELS: { key: string; label: string }[] = [
  { key: 'S0', label: '지원 없음' },
  { key: 'S1', label: '가벼운 힌트' },
  { key: 'S2', label: '선택지 제시' },
  { key: 'S3', label: '시범 후 따라하기' },
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

/** 시나리오 내용(왼쪽)의 섹션들. editable 로 넘긴 섹션은 그 자리에 편집 칸을 대신 보인다. */
function ScenarioSections({ scenario, replace = {} }: { scenario: Scenario; replace?: Partial<Record<'summary' | 'microGoals', ReactNode>> }) {
  const characters = scenario.characters.map((character) => character.role ?? character.id ?? '').filter(Boolean)
  return (
    <>
      <Section title="상황">
        {replace.summary ?? (
          <p className="text-sm text-[var(--instructor-text-muted)]">{scenario.summary ?? '상황 설명이 없습니다.'}</p>
        )}
      </Section>
      <Section title="인물">
        {characters.length === 0 ? (
          <p className="text-sm text-[var(--instructor-text-subtle)]">등장인물이 없습니다.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {characters.map((role, index) => (
              <li key={`${index}-${role}`} className="rounded-full bg-[var(--instructor-surface-muted)] px-3 py-1 text-xs">
                {role}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section title="고정 사실">
        <TextList items={scenario.scenarioFacts} empty="고정 사실이 없습니다." />
      </Section>
      <Section title="금지 추론">
        <TextList items={scenario.prohibitedInferences} empty="금지 추론이 없습니다." />
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

/** 지원 단계별 시작 질문(시안 오른쪽 "S0~S3 유도 질문"). 문자열이 아닌 값은 보이지 않는다. */
function OpeningPrompts({ scenario }: { scenario: Scenario }) {
  const levels = SUPPORT_LEVELS.filter(({ key }) => key !== 'S3' || typeof scenario.openingPrompts[key] === 'string')
  return (
    <div className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
      <h3 className="text-sm font-semibold">S0~S3 유도 질문</h3>
      {levels.map(({ key, label }) => {
        const prompt = scenario.openingPrompts[key]
        return (
          <div key={key} className="rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border-subtle)] px-4 py-3">
            <p className="mb-1 text-xs text-[var(--instructor-text-subtle)]">
              <span className="mr-2 rounded-full bg-[var(--instructor-surface-muted)] px-2 py-0.5 font-semibold">{key}</span>
              {label}
            </p>
            <p className="text-sm">{typeof prompt === 'string' && prompt.trim() ? prompt : '(유도 없음)'}</p>
          </div>
        )
      })}
      <p className="rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-4 py-3 text-xs text-[var(--instructor-text-subtle)]">
        검토 시 확인: 금지 표현 포함 여부, 고정 사실과 상황의 충돌, 증거 판정 가능성
      </p>
    </div>
  )
}

export { OpeningPrompts, ScenarioSections }
