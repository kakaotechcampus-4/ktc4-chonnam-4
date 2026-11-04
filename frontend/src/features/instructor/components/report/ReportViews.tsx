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
import { StatCard } from '../StatCard'
import { GoalStatusChip, SupportChip } from './ReportChips'
import { RecommendationTile } from './RecommendationTile'
import { recommendationsPath, technicalCount, type NextLevel } from './reportHelpers'
import { cn } from '@/lib/utils'

const SUPPORT_ORDER: SupportLevel[] = ['S0', 'S1', 'S2', 'S3']

const chipClass =
  'inline-block rounded-[var(--instructor-radius-badge)] bg-[var(--instructor-primary-subtle)] px-2.5 py-0.5 text-xs font-semibold text-[var(--instructor-link)]'

// 시안 T-RPT-02~08 의 두 칸(왼쪽 지표·근거 1.5 : 오른쪽 요약·기록 1). 표정 퀴즈 탭과 같은 비율이다.
const twoColumnClass = 'grid grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] items-start gap-4'
const sectionCardClass = cn(cardClass, 'flex flex-col gap-3 p-5')
const sectionTitleClass = 'text-sm font-semibold'
// 카드 안의 옅은 상자(시안의 증거 줄·기술 기록 안내).
const insetClass =
  'rounded-[var(--instructor-radius-control)] bg-[var(--instructor-surface-muted)] px-3 py-2.5 text-xs leading-5 text-[var(--instructor-text-muted)]'

/** 다음 역할극 난이도 한 줄. 리포트에 담긴 결정과 아동의 최신 결정이 같은 모양이 되게 맞춘다. */
type NextDifficulty = {
  scenarioLevel: NextLevel['scenarioLevel']
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

/** 지원을 한 단계씩 올린 횟수. 시안의 "유도 횟수" 자리다. 리포트에는 턴 번호가 없어 목표마다 오른 단계 수를 더한다. */
function promptCount(goals: ReportGoal[]): number {
  return goals
    .filter((goal) => goal.status !== 'EXCLUDED')
    .reduce(
      (sum, goal) =>
        sum + Math.max(0, SUPPORT_ORDER.indexOf(goal.finalSupport) - SUPPORT_ORDER.indexOf(goal.startSupport)),
      0,
    )
}

/** 시안 오른쪽 칸의 "AI 요약". 계약상 강사 수정이 없어 시안의 "수정" 링크는 두지 않는다. */
function AiSummaryCard({ summary }: { summary: string }) {
  return (
    <section aria-labelledby="ai-summary-heading" className={sectionCardClass}>
      <h3 id="ai-summary-heading" className={sectionTitleClass}>
        AI 요약
      </h3>
      <p className="text-sm leading-[1.7] break-keep">{summary}</p>
      <p className="text-xs text-[var(--instructor-text-subtle)]">
        관찰 기록으로 작성했고 근거와 맞는지 한 번 더 검사했어요
      </p>
    </section>
  )
}

/**
 * 개요 탭. 시안에는 역할극 탭만 그려져 있어 같은 두 칸 배치를 쓴다.
 * 왼쪽은 목표별로 필요했던 도움과 다음 수업 제안, 오른쪽은 AI 요약·다음 역할극 난이도·효과가 있었던 도움이다.
 * AI 요약이 아직 없으면(report 가 null) 서버가 계산한 난이도와 제안만 보인다. 제안 카드를 누르면 추천 상세에서 고른다.
 */
function ReportOverview({
  report,
  recommendations,
  latestDifficulty,
  classId,
  childId,
}: {
  report: LearningReport | null
  recommendations: Recommendation[]
  latestDifficulty: RoleplayDifficulty | null
  classId: string | null
  childId: string
}) {
  const next = report ? fromReport(report) : latestDifficulty ? fromLatest(latestDifficulty) : null
  const level: NextLevel | null = next ? { scenarioLevel: next.scenarioLevel, supportLevel: next.nextSupportLevel } : null
  const technical = report ? technicalCount(report) : 0
  return (
    <div className={twoColumnClass}>
      <div className="flex flex-col gap-4">
        {report && (
          <section aria-labelledby="goal-help-heading" className={sectionCardClass}>
            <h3 id="goal-help-heading" className={sectionTitleClass}>
              목표별로 필요했던 도움
            </h3>
            <GoalHelpTable goals={report.roleplay.goals} />
          </section>
        )}

        <section aria-labelledby="recommendation-heading" className={sectionCardClass}>
          <div>
            <h3 id="recommendation-heading" className={sectionTitleClass}>
              다음 수업 제안
            </h3>
            <p className="mt-1 text-xs text-[var(--instructor-text-subtle)]">
              카드를 누르면 추천 상세에서 고를 수 있어요. 선생님이 고르기 전에는 아무것도 배정하지 않아요.
            </p>
          </div>
          {recommendations.length === 0 ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">이 활동에서 만든 제안이 없어요.</p>
          ) : (
            <div className="grid gap-3 md:grid-cols-2">
              {recommendations.map((recommendation) => (
                <RecommendationTile
                  key={recommendation.recommendationId}
                  recommendation={recommendation}
                  level={level}
                  to={
                    classId
                      ? recommendationsPath({ classId, childId, recommendationId: recommendation.recommendationId })
                      : undefined
                  }
                />
              ))}
            </div>
          )}
        </section>
      </div>

      <div className="flex flex-col gap-4">
        {report && <AiSummaryCard summary={report.summary} />}

        <section aria-labelledby="next-difficulty-heading" className={sectionCardClass}>
          <h3 id="next-difficulty-heading" className={sectionTitleClass}>
            다음 역할극 난이도
          </h3>
          {next ? <NextDifficultyText next={next} /> : <p className="text-sm">아직 정하지 않았어요.</p>}
        </section>

        {report && (
          <section aria-labelledby="effective-support-heading" className={sectionCardClass}>
            <h3 id="effective-support-heading" className={sectionTitleClass}>
              효과가 있었던 도움
            </h3>
            {report.roleplay.effectiveSupport.length > 0 ? (
              <div className="flex flex-wrap gap-1.5">
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
          </section>
        )}

        {report && (
          <p className="text-xs text-[var(--instructor-text-subtle)]">
            관찰 기록 {report.roleplay.observations.length}개로 썼어요 ·{' '}
            {technical > 0 ? `기술 문제 ${technical}건은 기술 기록 탭에서 봐요` : '기술 문제 없음'}
          </p>
        )}
      </div>
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

/** 지원이 어디서 시작해 어디까지 갔는지. 시안처럼 기호를 쓰고 받은 도움을 함께 적는다. */
function supportPath(goal: ReportGoal): string {
  const from = `${goal.startSupport} ${SUPPORT_LABELS[goal.startSupport]}`
  if (goal.startSupport === goal.finalSupport) return from
  return `${from} → ${goal.finalSupport} ${SUPPORT_LABELS[goal.finalSupport]}`
}

/** 작은 목표 하나의 증거(시안 "작은 목표별 증거"의 한 칸). 아동 발화 원문은 없고, 지원 경로·기준별 관찰·관찰 문장을 보인다. */
function GoalEvidence({ goal }: { goal: ReportGoal }) {
  return (
    <section
      aria-label={goal.title}
      className="flex flex-col gap-2.5 rounded-[var(--instructor-radius-control)] border border-[var(--instructor-border)] px-4 py-3.5"
    >
      <div className="flex items-start justify-between gap-3">
        <h4 className="text-sm font-semibold break-keep">{goal.title}</h4>
        <GoalStatusChip status={goal.status} />
      </div>
      <div className={cn(insetClass, 'flex flex-col gap-1 break-keep')}>
        <p>지원 {supportPath(goal)}</p>
        {goal.criteria.map((criterion) => (
          <p key={criterion.description}>
            {criterion.description} · {criterion.status ? OBSERVATION_LABELS[criterion.status] : '관찰하지 못함'}
          </p>
        ))}
        {goal.observations.map((observation) => (
          <p key={observation}>{observation}</p>
        ))}
      </div>
    </section>
  )
}

/**
 * 역할극 탭(시안 T-RPT-02~08). 왼쪽은 지표 세 칸과 작은 목표별 증거, 오른쪽은 AI 요약과 유도 기록이다.
 * 시안의 "턴 수"는 리포트에 없어 판단한 목표 수로, "유도 횟수"는 지원을 올린 단계 수로 채운다.
 * 지원 수준은 이번 시작 지원과 서버가 정한 다음 시작 지원이다.
 */
function RoleplayGoalsView({ report }: { report: LearningReport }) {
  const { roleplay, metrics, difficultyDecision } = report
  const judged = roleplay.goals.filter((goal) => goal.status !== 'EXCLUDED')
  const technical = technicalCount(report)
  return (
    <div className={twoColumnClass}>
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-3 gap-4">
          <StatCard
            label="판단한 목표"
            value={`${metrics.validGoalCount}개`}
            note={
              metrics.excludedGoalCount > 0
                ? `기술 문제로 ${metrics.excludedGoalCount}개 뺌`
                : `이번 역할극 ${roleplay.scenarioLevel}`
            }
          />
          <StatCard label="유도 횟수" value={`${promptCount(roleplay.goals)}회`} note="지원을 한 단계 올린 횟수" />
          <StatCard
            label="지원 수준"
            value={
              <div className="flex flex-wrap items-center gap-1.5">
                <SupportChip level={roleplay.startSupportLevel} prefix="시작" />
                <span aria-hidden className="text-[var(--instructor-text-subtle)]">
                  →
                </span>
                <SupportChip level={difficultyDecision.nextSupportLevel} prefix="다음" />
              </div>
            }
            note={`다음 역할극은 ${SUPPORT_LABELS[difficultyDecision.nextSupportLevel]}부터`}
          />
        </div>

        <section aria-labelledby="goal-evidence-heading" className={sectionCardClass}>
          <h3 id="goal-evidence-heading" className={sectionTitleClass}>
            작은 목표별 증거
          </h3>
          {roleplay.goals.map((goal) => (
            <GoalEvidence key={goal.microGoalId} goal={goal} />
          ))}
        </section>
      </div>

      <div className="flex flex-col gap-4">
        <AiSummaryCard summary={report.summary} />
        <section aria-labelledby="prompt-log-heading" className={sectionCardClass}>
          <h3 id="prompt-log-heading" className={sectionTitleClass}>
            유도 기록
          </h3>
          <ul className="flex flex-col gap-2 text-sm">
            {judged.map((goal) => (
              <li key={goal.microGoalId} className="flex items-start gap-2">
                <SupportChip level={goal.finalSupport} />
                <span className="break-keep">
                  {goal.title} · {GOAL_STATUS_LABELS[goal.status]}
                </span>
              </li>
            ))}
          </ul>
          <p className={insetClass}>
            {technical > 0
              ? `기술 기록 탭: 기술 문제 ${technical}건은 아동의 어려움으로 보지 않고 따로 보여요.`
              : '기술 문제로 뺀 답이나 목표는 없어요.'}
          </p>
        </section>
      </div>
    </div>
  )
}

/** 난이도 근거 탭. 왼쪽은 다음 역할극 레벨과 그 수치, 오른쪽은 정하는 규칙이다. */
function DifficultyBasisView({ report }: { report: LearningReport }) {
  const d = report.difficultyDecision
  const metrics = report.metrics
  return (
    <div className={twoColumnClass}>
      <section aria-labelledby="difficulty-basis-heading" className={cn(sectionCardClass, 'text-sm leading-6')}>
        <h3 id="difficulty-basis-heading" className={sectionTitleClass}>
          다음 역할극 난이도
        </h3>
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
      </section>
      <section aria-labelledby="difficulty-rule-heading" className={sectionCardClass}>
        <h3 id="difficulty-rule-heading" className={sectionTitleClass}>
          정하는 규칙
        </h3>
        <p className="text-sm leading-6 break-keep text-[var(--instructor-text-muted)]">
          점수는 시작 지원에서 답 알려 주기까지 남은 단계로 나눠 목표마다 0~1 로 매기고, 기술 문제로 뺀 목표는 세지 않아요. 50% 미만이면 낮추고, 80%
          이상이 두 번 이어지면 올려요.
        </p>
      </section>
    </div>
  )
}

/** 기술 기록 탭. 음성 인식처럼 아동의 어려움이 아닌 문제를 따로 보여, 난이도·추천 계산에서 뺐다고 알린다. */
function TechnicalRecordView({ report }: { report: LearningReport }) {
  const technical = report.roleplay.technicalExclusions
  const excluded = report.roleplay.goals.filter((goal) => goal.status === 'EXCLUDED')
  return (
    <div className={twoColumnClass}>
      <section aria-labelledby="technical-heading" className={cn(sectionCardClass, 'text-sm leading-6')}>
        <h3 id="technical-heading" className={sectionTitleClass}>
          기술 기록
        </h3>
        {technical.length === 0 && excluded.length === 0 ? (
          <p>기술 문제로 뺀 답이나 목표가 없어요.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {technical.map((code) => (
              <li key={code} className={insetClass}>
                {TECHNICAL_LABELS[code] ?? code}
              </li>
            ))}
            {excluded.map((goal) => (
              <li key={goal.microGoalId} className={insetClass}>
                '{goal.title}' 목표는 판단할 답이 없어 결과에서 뺐어요
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="technical-rule-heading" className={sectionCardClass}>
        <h3 id="technical-rule-heading" className={sectionTitleClass}>
          계산에서 빼는 기록
        </h3>
        <p className="text-sm leading-6 break-keep text-[var(--instructor-text-muted)]">
          이 기록은 아동의 어려움으로 보지 않아요. 다음 난이도와 추천을 계산할 때도 빼요.
        </p>
      </section>
    </div>
  )
}

export { DifficultyBasisView, ReportOverview, RoleplayGoalsView, TechnicalRecordView }
