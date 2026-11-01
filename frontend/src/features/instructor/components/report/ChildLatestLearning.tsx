import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  ApiError,
  getRoleplayDifficulty,
  listLearningReports,
  listRecommendations,
  type Child,
} from '../../api'
import { DIRECTION_LABELS, SUPPORT_LABELS } from '../../lib/reportLabels'
import { tableRowClass } from '../styles'
import { RecommendationCard } from './RecommendationCard'

/**
 * 아동 한 명의 가장 최근 역할극 학습. 기준은 다음 역할극 난이도(API-REC-06)다. 난이도는 AI 요약과 상관없이 서버가 역할극을 마칠 때마다 정해서,
 * 요약을 만들지 못한 활동도 그 학습 기록(sourceRecordId)으로 추천을 읽을 수 있다. 요약 한 줄은 완료 리포트 목록의 첫 항목이 그 활동일 때만 쓴다.
 */
function useChildLatestLearning(childId: string) {
  const difficultyQuery = useQuery({
    queryKey: ['children', childId, 'roleplay-difficulty'],
    queryFn: async () => {
      try {
        return await getRoleplayDifficulty(childId)
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }
    },
  })
  const difficulty = difficultyQuery.data ?? null
  const reportsQuery = useQuery({
    queryKey: ['learning-records', { childId, size: 1 }],
    queryFn: () => listLearningReports(childId, 1),
    enabled: !!difficulty,
  })
  const latestReport = reportsQuery.data?.[0]
  const report = latestReport && latestReport.activityId === difficulty?.sourceActivityId ? latestReport : null
  const recordId = difficulty?.sourceRecordId ?? null
  const recommendationsQuery = useQuery({
    queryKey: ['learning-records', recordId, 'recommendations'],
    queryFn: () => listRecommendations(recordId!),
    enabled: !!recordId,
  })
  return {
    difficulty,
    report,
    recommendations: recommendationsQuery.data ?? [],
    isLoading: difficultyQuery.isLoading || reportsQuery.isLoading || recommendationsQuery.isLoading,
    error: difficultyQuery.error ?? reportsQuery.error ?? recommendationsQuery.error,
  }
}

/** 학급 개요 "최근 학습 요약"의 한 줄. 누르면 그 활동 리포트로 간다. */
function RecentLearningRow({ child }: { child: Child }) {
  const { difficulty, report, recommendations, isLoading } = useChildLatestLearning(child.childId)
  if (isLoading || !difficulty) return null
  const open = recommendations.filter((r) => r.decision === null || r.decision === 'DEFER').length
  return (
    <li className={tableRowClass}>
      <Link
        to={`/activities/${difficulty.sourceActivityId}/report`}
        className="flex flex-col gap-0.5 px-1 py-3 hover:bg-[var(--instructor-surface-muted)]"
      >
        <span className="text-sm font-semibold">
          {child.displayName}
          <span className="ml-2 text-xs font-normal text-[var(--instructor-text-muted)]">
            다음 역할극 {difficulty.scenarioLevel} {DIRECTION_LABELS[difficulty.direction]} · 확인할 제안 {open}개
          </span>
        </span>
        <span className="text-sm text-[var(--instructor-text-muted)] break-keep">
          {report ? report.summary : 'AI 요약을 만드는 중이거나 만들지 못했어요.'}
        </span>
      </Link>
    </li>
  )
}

/** 추천·다음목표 화면의 아동 한 명 칸. 다음 난이도와 확인할 제안을 보이고, 제안을 고르면 활동 만들기로 이어진다. */
function ChildRecommendations({ child }: { child: Child }) {
  const { difficulty, report, recommendations, isLoading, error } = useChildLatestLearning(child.childId)
  return (
    <section aria-labelledby={`child-${child.childId}`} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={`child-${child.childId}`} className="text-base font-semibold">
          {child.displayName}
        </h2>
        {difficulty && (
          <Link
            to={`/activities/${difficulty.sourceActivityId}/report`}
            className="text-sm text-[var(--instructor-link)] hover:underline"
          >
            리포트 보기
          </Link>
        )}
      </div>
      {isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : error ? (
        <p className="text-sm text-[var(--instructor-danger-text)]">{error.message}</p>
      ) : !difficulty ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">아직 마친 역할극이 없어요.</p>
      ) : (
        <>
          <p className="text-sm">
            다음 역할극 <b>{difficulty.scenarioLevel}</b> · 시작 지원 {SUPPORT_LABELS[difficulty.initialSupportLevel]}
            {report && <span className="text-[var(--instructor-text-muted)]"> · {report.summary}</span>}
          </p>
          {recommendations.length === 0 ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">만든 제안이 없어요.</p>
          ) : (
            recommendations.map((recommendation) => (
              <RecommendationCard
                key={recommendation.recommendationId}
                recommendation={recommendation}
                classId={child.classId}
              />
            ))
          )}
        </>
      )}
    </section>
  )
}

export { ChildRecommendations, RecentLearningRow }
