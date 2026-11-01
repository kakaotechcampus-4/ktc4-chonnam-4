import type { LearningReport, Recommendation, ReportGoal, RoleplayDifficulty, SupportLevel } from '../../api'
import {
  DIFFICULTY_REASON_LABELS,
  DIRECTION_LABELS,
  GOAL_STATUS_LABELS,
  OBSERVATION_LABELS,
  SUPPORT_COLUMNS,
  SUPPORT_LABELS,
  TECHNICAL_LABELS,
  countText,
  percentText,
} from '../../lib/reportLabels'
import { cardClass, tableClass, tableHeadRowClass, tableRowClass } from '../styles'
import { RecommendationCard } from './RecommendationCard'
import { cn } from '@/lib/utils'

const SUPPORT_ORDER: SupportLevel[] = ['S0', 'S1', 'S2', 'S3']

const chipClass =
  'inline-block rounded-[var(--instructor-radius-badge)] bg-[var(--instructor-primary-subtle)] px-2.5 py-0.5 text-xs font-semibold text-[var(--instructor-link)]'

/** 다음 역할극 난이도 한 줄. 리포트에 담긴 결정과 아동의 최신 결정이 같은 모양이 되게 맞춘다. */
type NextDifficulty = {
  scenarioLevel: string
  nextSupportLevel: SupportLevel
  direction: 'LOWER' | 'KEEP' | 'RAISE'
  reason: string
}

function fromReport(report: LearningReport): NextDifficulty {
  const d = report.difficultyDecision
  return { scenarioLevel: d.scenarioLevel, nextSupportLevel: d.nextSupportLevel, direction: d.direction, reason: d.reason }
}

function fromLatest(latest: RoleplayDifficulty): NextDifficulty {
  return {
    scenarioLevel: latest.scenarioLevel,
    nextSupportLevel: latest.initialSupportLevel,
    direction: latest.direction,
    reason: latest.reason,
  }
}

function NextDifficultyText({ next }: { next: NextDifficulty }) {
  return (
    <p className="text-sm leading-6">
      <b>
        {next.scenarioLevel} {DIRECTION_LABELS[next.direction]}
      </b>{' '}
      · 시작 지원 {SUPPORT_LABELS[next.nextSupportLevel]}. {DIFFICULTY_REASON_LABELS[next.reason] ?? next.reason}
    </p>
  )
}

/**
 * 개요 탭(시안 11_시안-강사-역할극-리포트). 한 줄 요약, 목표별로 필요했던 도움, 다음 역할극 난이도, 다음 수업 제안 순서다.
 * AI 요약이 아직 없으면(report 가 null) 서버가 계산한 난이도와 추천만 보인다.
 */
function ReportOverview({
  report,
  recommendations,
  latestDifficulty,
  classId,
}: {
  report: LearningReport | null
  recommendations: Recommendation[]
  latestDifficulty: RoleplayDifficulty | null
  classId: string | null
}) {
  const next = report ? fromReport(report) : latestDifficulty ? fromLatest(latestDifficulty) : null
  const technical = report?.roleplay.technicalExclusions ?? []
  return (
    <div className={cn(cardClass, 'flex flex-col gap-6 p-6')}>
      {report && (
        <div>
          <p className="text-[19px] leading-[1.6] break-keep">{report.summary}</p>
          <p className="mt-1 text-xs text-[var(--instructor-text-subtle)]">
            AI 요약 · 관찰 기록으로 작성했고 근거와 맞는지 한 번 더 검사했어요
          </p>
        </div>
      )}

      {report && (
        <section aria-labelledby="goal-help-heading" className="flex flex-col gap-3">
          <h3 id="goal-help-heading" className="text-sm font-semibold text-[var(--instructor-text-muted)]">
            목표별로 필요했던 도움
          </h3>
          <GoalHelpTable goals={report.roleplay.goals} />
        </section>
      )}

      <div className="grid gap-3.5 md:grid-cols-2">
        {report && (
          <div className="rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-4 py-3.5">
            <p className="mb-2 text-xs text-[var(--instructor-text-muted)]">효과가 있었던 도움</p>
            {report.roleplay.effectiveSupport.length > 0 ? (
              <div className="mb-2 flex flex-wrap gap-1.5">
                {report.roleplay.effectiveSupport.map((support) => (
                  <span key={support} className={chipClass}>
                    {support}
                  </span>
                ))}
              </div>
            ) : (
              <p className="text-sm">지원을 더 받지 않고 도달했어요.</p>
            )}
            {report.narrative?.effectiveSupports[0] && (
              <p className="text-sm leading-6 break-keep">{report.narrative.effectiveSupports[0].text}</p>
            )}
          </div>
        )}
        <div className="rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-4 py-3.5">
          <p className="mb-2 text-xs text-[var(--instructor-text-muted)]">다음 역할극 난이도</p>
          {next ? <NextDifficultyText next={next} /> : <p className="text-sm">아직 정하지 않았어요.</p>}
        </div>
      </div>

      <section aria-labelledby="recommendation-heading" className="flex flex-col gap-2">
        <h3 id="recommendation-heading" className="text-sm font-semibold text-[var(--instructor-text-muted)]">
          다음 수업 제안 · 선생님이 골라 주세요
        </h3>
        {recommendations.length === 0 ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">이 활동에서 만든 제안이 없어요.</p>
        ) : (
          recommendations.map((recommendation) => (
            <RecommendationCard key={recommendation.recommendationId} recommendation={recommendation} classId={classId} />
          ))
        )}
      </section>

      {report && (
        <p className="text-xs text-[var(--instructor-text-subtle)]">
          관찰 기록 {report.roleplay.observations.length}개로 썼어요 ·{' '}
          {technical.length > 0 ? '기술 문제가 있었어요' : '기술 문제 없음'} · 난이도 근거와 기술 기록은 각 탭에서 봐요
        </p>
      )}
    </div>
  )
}

/**
 * 목표별로 어느 도움에서 도달했는지 보이는 표. 도달한 칸에 점을 찍고, 시작 지원부터 그 앞까지는 지나온 칸으로 옅게 표시한다.
 * 점만으로 뜻을 알리지 않도록 칸마다 화면 읽기용 글자를 둔다.
 */
function GoalHelpTable({ goals }: { goals: ReportGoal[] }) {
  return (
    <table className={tableClass}>
      <thead>
        <tr className={tableHeadRowClass}>
          <th scope="col" className="px-4 py-3 font-normal">
            작은 목표
          </th>
          {SUPPORT_COLUMNS.map((column) => (
            <th key={column.level} scope="col" className="px-2 py-3 text-center font-normal">
              {column.label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {goals.map((goal) => {
          const start = SUPPORT_ORDER.indexOf(goal.startSupport)
          const final = SUPPORT_ORDER.indexOf(goal.finalSupport)
          const reached = goal.result === 'REACHED'
          return (
            <tr key={goal.microGoalId} className={tableRowClass}>
              <th scope="row" className="px-4 py-3 text-left font-normal break-keep">
                {goal.title}
                {!reached && (
                  <span className="ml-2 text-xs text-[var(--instructor-text-muted)]">{GOAL_STATUS_LABELS[goal.status]}</span>
                )}
              </th>
              {SUPPORT_ORDER.map((level, index) => {
                const hit = reached && index === final
                const passed = index >= start && index < final
                return (
                  <td key={level} className="px-2 py-3 text-center">
                    {hit ? (
                      <span className="mx-auto block size-[22px] rounded-full bg-[var(--instructor-success-fg)] shadow-[0_0_0_4px_var(--instructor-success-bg)]">
                        <span className="sr-only">{SUPPORT_LABELS[level]}에서 도달</span>
                      </span>
                    ) : passed ? (
                      <span className="mx-auto block size-2 rounded-full bg-[var(--instructor-border-strong)]">
                        <span className="sr-only">{SUPPORT_LABELS[level]} 받음</span>
                      </span>
                    ) : null}
                  </td>
                )
              })}
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

/** 역할극 탭. 작은 목표마다 시작·최종 지원, 기준별 관찰, 관찰 문장을 보인다. 아동 발화 원문은 없다. */
function RoleplayGoalsView({ report }: { report: LearningReport }) {
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-[var(--instructor-text-muted)]">
        난이도 {report.roleplay.scenarioLevel} · 시작 지원 {SUPPORT_LABELS[report.roleplay.startSupportLevel]} · 판단한 목표{' '}
        {report.metrics.validGoalCount}개
      </p>
      {report.roleplay.goals.map((goal) => (
        <section key={goal.microGoalId} aria-label={goal.title} className={cn(cardClass, 'flex flex-col gap-3 p-5')}>
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h3 className="text-base font-semibold">{goal.title}</h3>
            <p className="text-sm text-[var(--instructor-text-muted)]">
              {SUPPORT_LABELS[goal.startSupport]}에서 시작 · {SUPPORT_LABELS[goal.finalSupport]}까지 · {GOAL_STATUS_LABELS[goal.status]}
            </p>
          </div>
          {goal.criteria.length > 0 && (
            <ul className="flex flex-col gap-1 text-sm">
              {goal.criteria.map((criterion) => (
                <li key={criterion.description}>
                  <span className="font-semibold">{criterion.description}</span>
                  <span className="text-[var(--instructor-text-muted)]">
                    {' '}
                    · {criterion.status ? OBSERVATION_LABELS[criterion.status] : '관찰하지 못함'}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {goal.observations.length > 0 && (
            <ol className="list-decimal pl-5 text-sm leading-6 text-[var(--instructor-text-muted)]">
              {goal.observations.map((observation) => (
                <li key={observation}>{observation}</li>
              ))}
            </ol>
          )}
        </section>
      ))}
    </div>
  )
}

/** 난이도 근거 탭. 다음 역할극 레벨을 어떤 규칙으로 정했는지 수치와 함께 보인다. */
function DifficultyBasisView({ report }: { report: LearningReport }) {
  const d = report.difficultyDecision
  const metrics = report.metrics
  return (
    <div className={cn(cardClass, 'flex max-w-3xl flex-col gap-4 p-6 text-sm leading-6')}>
      <NextDifficultyText next={fromReport(report)} />
      <dl className="grid grid-cols-[10rem_1fr] gap-x-4 gap-y-2">
        <dt className="text-[var(--instructor-text-muted)]">이번 역할극</dt>
        <dd>{d.previousLevel}</dd>
        <dt className="text-[var(--instructor-text-muted)]">점수율</dt>
        <dd>
          {percentText(d.scoreRate)}
          <span className="text-[var(--instructor-text-muted)]"> (판단한 목표 {metrics.validGoalCount}개)</span>
        </dd>
        <dt className="text-[var(--instructor-text-muted)]">답 없이 도달</dt>
        <dd>{countText(metrics.goalAchievement, '개')}</dd>
        <dt className="text-[var(--instructor-text-muted)]">지원을 더 받지 않고 도달</dt>
        <dd>{countText(metrics.independentAchievement, '개')}</dd>
        <dt className="text-[var(--instructor-text-muted)]">이어진 상향 후보</dt>
        <dd>{d.consecutiveUpgradeCandidates}번</dd>
        <dt className="text-[var(--instructor-text-muted)]">정책 버전</dt>
        <dd className="font-mono text-xs">{d.policyVersion}</dd>
      </dl>
      <p className="text-xs text-[var(--instructor-text-subtle)]">
        점수는 시작 지원에서 답 알려 주기까지 남은 단계로 나눠 목표마다 0~1 로 매기고, 기술 문제로 뺀 목표는 세지 않아요. 50% 미만이면 낮추고, 80% 이상이
        두 번 이어지면 올려요.
      </p>
    </div>
  )
}

/** 기술 기록 탭. 음성 인식처럼 아동의 어려움이 아닌 문제를 따로 보여, 난이도·추천 계산에서 뺐다고 알린다. */
function TechnicalRecordView({ report }: { report: LearningReport }) {
  const technical = report.roleplay.technicalExclusions
  const excluded = report.roleplay.goals.filter((goal) => goal.status === 'EXCLUDED')
  return (
    <div className={cn(cardClass, 'flex max-w-3xl flex-col gap-3 p-6 text-sm leading-6')}>
      {technical.length === 0 && excluded.length === 0 ? (
        <p>기술 문제로 뺀 답이나 목표가 없어요.</p>
      ) : (
        <>
          <ul className="list-disc pl-5">
            {technical.map((code) => (
              <li key={code}>{TECHNICAL_LABELS[code] ?? code}</li>
            ))}
            {excluded.map((goal) => (
              <li key={goal.microGoalId}>'{goal.title}' 목표는 판단할 답이 없어 결과에서 뺐어요</li>
            ))}
          </ul>
          <p className="text-[var(--instructor-text-muted)]">
            이 기록은 아동의 어려움으로 보지 않아요. 다음 난이도와 추천을 계산할 때도 빼요.
          </p>
        </>
      )}
    </div>
  )
}

export { DifficultyBasisView, ReportOverview, RoleplayGoalsView, TechnicalRecordView }
