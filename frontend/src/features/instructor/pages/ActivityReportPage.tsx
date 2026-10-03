import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  ApiError,
  getActivity,
  getChild,
  getClassroom,
  getLearningGoal,
  getQuizResult,
  type QuizResult,
} from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ActivityStatusBadge } from '../components/ActivityStatusBadge'
import { PageTabs } from '../components/PageTabs'
import { cardClass, errorTextClass } from '../components/styles'
import { cn } from '@/lib/utils'

const QUIZ_TAB = '표정 퀴즈'

/**
 * 활동 리포트 (시안 T-RPT-02~08). S1 은 표정 퀴즈 탭만 연다(ADR 2026-10-03 D5).
 * 결과는 아동이 붙은 문항을 모두 마쳐야 생긴다. 그 전에는 서버가 409 QUIZ_NOT_COMPLETED 로 답하므로 "진행 중"으로 보인다.
 */
function ActivityReportPage() {
  const { activityId } = useParams<{ activityId: string }>()

  const activityQuery = useQuery({
    queryKey: ['activities', activityId],
    queryFn: () => getActivity(activityId!),
    enabled: !!activityId,
  })
  const activity = activityQuery.data?.activity
  const quizItemCount = activityQuery.data?.quizItems.length ?? 0

  const childQuery = useQuery({
    queryKey: ['children', activity?.childId],
    queryFn: () => getChild(activity!.childId),
    enabled: !!activity,
  })
  const child = childQuery.data

  const classroomQuery = useQuery({
    queryKey: ['classrooms', child?.classId],
    queryFn: () => getClassroom(child!.classId),
    enabled: !!child,
  })

  const goalQuery = useQuery({
    queryKey: ['learning-goals', activity?.goalId],
    queryFn: () => getLearningGoal(activity!.goalId),
    enabled: !!activity,
  })

  // 시작 전 활동은 결과가 있을 수 없어 묻지 않는다.
  const resultQuery = useQuery({
    queryKey: ['activities', activityId, 'quiz-result'],
    queryFn: () => getQuizResult(activityId!),
    enabled: !!activity && activity.status !== 'NOT_STARTED',
  })
  const isQuizInProgress =
    resultQuery.error instanceof ApiError && resultQuery.error.code === 'QUIZ_NOT_COMPLETED'

  const title = child
    ? `활동 리포트 · ${child.displayName}${classroomQuery.data ? ` (${classroomQuery.data.name})` : ''}`
    : '활동 리포트'

  return (
    <InstructorLayout
      title={title}
      subheader={
        <PageTabs
          label="활동 리포트"
          selected={QUIZ_TAB}
          tabs={[
            { label: '개요' },
            { label: QUIZ_TAB, available: true },
            { label: '역할극' },
            { label: '난이도 근거' },
            { label: '기술 기록' },
            { label: 'AI 요약 수정' },
          ]}
        />
      }
    >
      {child && (
        <Link
          to={`/classrooms/${child.classId}/children/${child.childId}`}
          className="mb-4 inline-block text-sm text-[var(--instructor-text-muted)] hover:underline"
        >
          ← 활동 이력
        </Link>
      )}

      {activityQuery.isLoading ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
      ) : activityQuery.isError || !activity ? (
        <p className={errorTextClass}>
          {activityQuery.error instanceof Error ? activityQuery.error.message : '활동을 찾을 수 없습니다.'}
        </p>
      ) : (
        <section role="tabpanel" aria-label={QUIZ_TAB} className="flex flex-col gap-4">
          <div className="flex items-center gap-3">
            <h2 className="text-base font-bold">{goalQuery.data?.title ?? '학습 목표'}</h2>
            <ActivityStatusBadge status={activity.status} />
          </div>

          {activity.status === 'NOT_STARTED' ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">
              아직 시작 전이에요. 아동이 퀴즈를 마치면 결과가 보입니다.
            </p>
          ) : resultQuery.isLoading ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
          ) : isQuizInProgress ? (
            <p className="text-sm text-[var(--instructor-text-muted)]">
              퀴즈를 진행 중이에요. 아동이 모든 문항을 마치면 결과가 보입니다.
            </p>
          ) : resultQuery.isError || !resultQuery.data ? (
            <p className={errorTextClass}>
              {resultQuery.error instanceof Error ? resultQuery.error.message : '결과를 불러오지 못했습니다.'}
            </p>
          ) : (
            <QuizResultView result={resultQuery.data} quizItemCount={quizItemCount} />
          )}
        </section>
      )}
    </InstructorLayout>
  )
}

function QuizResultView({ result, quizItemCount }: { result: QuizResult; quizItemCount: number }) {
  // 카메라·네트워크 같은 기술 문제로 판정하지 못한 문항은 서버가 분모에서 뺀다. 정답률을 잘못 읽지 않게 따로 보인다.
  const excludedCount = Math.max(quizItemCount - result.validQuestionCount, 0)
  const accuracy =
    result.overallAccuracy === null ? '-' : `${Math.round(result.overallAccuracy * 100)}%`

  return (
    <>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="정답률" value={accuracy} />
        <StatCard
          label="정답 / 판정 문항"
          value={`${result.correctQuestionCount} / ${result.validQuestionCount}`}
        />
        <StatCard
          label="힌트 사용"
          value={`${result.totalHintCount}회`}
          note={`힌트 뒤 정답 ${result.resolvedAfterHintCount}문항`}
        />
        <StatCard
          label="기술 문제로 제외"
          value={`${excludedCount}문항`}
          note={excludedCount > 0 ? '정답률 계산에서 뺐어요' : undefined}
        />
      </div>

      <div className={cn(cardClass, 'p-5 text-sm')}>
        <h3 className="mb-2 font-semibold">첫 난이도</h3>
        {result.initialDifficultyUsed ? (
          <p>
            이 결과로 첫 역할극 난이도를 정했어요. 난이도 {result.scenarioLevel ?? '-'} · 지원 수준{' '}
            {result.initialSupportLevel ?? '-'}
            {result.difficultyFallbackApplied && (
              <span className="text-[var(--instructor-text-muted)]"> (판정 문항이 부족해 기본값을 썼어요)</span>
            )}
          </p>
        ) : (
          <p className="text-[var(--instructor-text-muted)]">
            첫 난이도는 이전에 마친 퀴즈 결과로 이미 정해져 있어요.
          </p>
        )}
      </div>
    </>
  )
}

function StatCard({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className={cn(cardClass, 'p-5')}>
      <p className="text-xs text-[var(--instructor-text-muted)]">{label}</p>
      <p className="mt-2 text-2xl font-bold">{value}</p>
      {note && <p className="mt-1 text-xs text-[var(--instructor-text-muted)]">{note}</p>}
    </div>
  )
}

export { ActivityReportPage }
