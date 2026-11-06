import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  ApiError,
  getActivity,
  getChild,
  getClassroom,
  getLearningGoal,
  getLearningReport,
  getQuizResult,
  getRoleplayDifficulty,
  listRecommendations,
  type Activity,
  type QuizResult,
} from '../api'
import { InstructorLayout } from '../layout/InstructorLayout'
import { ActivityStatusBadge } from '../components/ActivityStatusBadge'
import { PageTabs } from '../components/PageTabs'
import { StatCard } from '../components/StatCard'
import {
  DifficultyBasisView,
  ReportOverview,
  RoleplayGoalsView,
  TechnicalRecordView,
} from '../components/report/ReportViews'
import { orNullOn404 } from '../components/report/learningQueries'
import { recommendationsPath, technicalCount } from '../components/report/reportHelpers'
import { cardClass, outlineButtonClass, primaryButtonClass } from '../components/styles'
import { ACTIVITY_TYPE_LABELS } from '../lib/activityTypes'
import { cn } from '@/lib/utils'
import { Notice } from '../components/Feedback'

const TABS = [
  { key: 'overview', label: '개요' },
  { key: 'quiz', label: '표정 퀴즈' },
  { key: 'roleplay', label: '역할극' },
  { key: 'difficulty', label: '난이도 근거' },
  { key: 'technical', label: '기술 기록' },
] as const
type TabKey = (typeof TABS)[number]['key']
// 계약(API-REC-01)은 강사 수정을 두지 않는다. 팀이 정할 때까지 자리만 둔다.
const EDIT_TAB_LABEL = 'AI 요약 수정'

function hasRoleplay(activity: Activity): boolean {
  return activity.activityType === 'FIRST_COMBINED' || activity.activityType === 'ROLEPLAY'
}

/**
 * 활동 리포트 (시안 T-RPT-02~08). 표정 퀴즈 탭과 역할극 리포트 탭(개요·역할극·난이도 근거·기술 기록)을 연다.
 * 역할극이 끝나면 서버가 목표별 결과·다음 난이도·추천을 먼저 만들고, AI 요약은 생성·평가를 통과한 뒤에 보인다.
 * 요약이 아직 없거나 만들지 못했으면 리포트 API 는 404 이고, 개요 탭은 아동의 최신 난이도에 담긴 학습 기록으로 추천만 보인다.
 * 퀴즈 결과는 아동이 붙은 문항을 모두 마쳐야 생긴다. 그 전에는 서버가 409 QUIZ_NOT_COMPLETED 로 답하므로 "진행 중"으로 보인다.
 */
function ActivityReportPage() {
  const { activityId } = useParams<{ activityId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()

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
    queryFn: () => getLearningGoal(activity!.goalId!),
    // 독립 퀴즈는 목표가 없다.
    enabled: !!activity?.goalId,
  })

  const withQuiz = !!activity && activity.activityType !== 'ROLEPLAY'
  // 시작 전 활동은 결과가 있을 수 없어 묻지 않는다.
  const resultQuery = useQuery({
    queryKey: ['activities', activityId, 'quiz-result'],
    queryFn: () => getQuizResult(activityId!),
    enabled: withQuiz && activity.status !== 'NOT_STARTED',
  })
  const isQuizInProgress =
    resultQuery.error instanceof ApiError && resultQuery.error.code === 'QUIZ_NOT_COMPLETED'

  const roleplayDone = !!activity && hasRoleplay(activity) && activity.status === 'COMPLETED'
  const reportQuery = useQuery({
    queryKey: ['activities', activityId, 'learning-record'],
    queryFn: () => orNullOn404(() => getLearningReport(activityId!)),
    enabled: roleplayDone,
  })
  const report = reportQuery.data ?? null
  const difficultyQuery = useQuery({
    queryKey: ['children', activity?.childId, 'roleplay-difficulty'],
    queryFn: () => orNullOn404(() => getRoleplayDifficulty(activity!.childId)),
    enabled: roleplayDone,
  })
  // 아동의 최신 난이도가 이 활동에서 나온 것일 때만 이 활동의 것으로 쓴다.
  const latestDifficulty =
    difficultyQuery.data && difficultyQuery.data.sourceActivityId === activityId ? difficultyQuery.data : null
  const recordId = report?.recordId ?? latestDifficulty?.sourceRecordId ?? null
  const recommendationsQuery = useQuery({
    queryKey: ['learning-records', recordId, 'recommendations'],
    queryFn: () => listRecommendations(recordId!),
    enabled: !!recordId,
  })

  const available: Record<TabKey, boolean> = {
    overview: !!activity && hasRoleplay(activity),
    quiz: withQuiz,
    roleplay: !!report,
    difficulty: !!report,
    technical: !!report,
  }
  const requested = searchParams.get('tab')
  const fallback: TabKey = activity && hasRoleplay(activity) && (roleplayDone || !withQuiz) ? 'overview' : 'quiz'
  const tab: TabKey =
    TABS.some((t) => t.key === requested) && available[requested as TabKey] ? (requested as TabKey) : fallback
  const tabLabel = TABS.find((t) => t.key === tab)!.label

  function selectTab(label: string) {
    const next = TABS.find((t) => t.label === label)
    if (next) setSearchParams(next.key === fallback ? {} : { tab: next.key }, { replace: true })
  }

  const title = child
    ? `활동 리포트 · ${child.displayName}${classroomQuery.data ? ` (${classroomQuery.data.name})` : ''}`
    : '활동 리포트'
  const hasRecommendations = (recommendationsQuery.data?.length ?? 0) > 0

  return (
    <InstructorLayout
      title={title}
      // 시안의 카드처럼 두 칸이 화면 아래까지 늘어난다.
      fill
      // 시안 헤더의 "내보내기"·"추천 보기". 내보내기는 형식·API 가 아직 없어 학급 개요의 "접근 코드 출력"처럼 비활성으로 둔다.
      actions={
        <>
          <button type="button" disabled title="준비 중인 기능입니다" className={outlineButtonClass}>
            내보내기
          </button>
          {child && hasRecommendations && (
            <Link to={recommendationsPath({ classId: child.classId, childId: child.childId })} className={primaryButtonClass}>
              추천 보기
            </Link>
          )}
        </>
      }
      subheader={
        <PageTabs
          label="활동 리포트"
          selected={tabLabel}
          onSelect={selectTab}
          tabs={[
            ...TABS.map((t) => ({
              label: t.label,
              available: available[t.key],
              count: t.key === 'technical' && report ? technicalCount(report) : undefined,
            })),
            { label: EDIT_TAB_LABEL },
          ]}
        />
      }
    >
      {/* fill 은 본문의 바로 아래 자식을 늘인다. 경로 줄은 그대로 두고 탭 내용만 남은 높이를 채운다. */}
      <div className="flex flex-col">
        {child && (
          <nav aria-label="현재 위치" className="mb-4 text-xs text-[var(--instructor-text-subtle)]">
            <Link to={`/classrooms/${child.classId}?tab=children`} className="hover:underline">
              {classroomQuery.data?.name ?? '학급'}
            </Link>
            <span aria-hidden className="mx-1 text-[var(--instructor-border-strong)]">
              ›
            </span>
            <Link to={`/classrooms/${child.classId}/children/${child.childId}`} className="hover:underline">
              {child.displayName} 활동 이력
            </Link>
          </nav>
        )}

        {activityQuery.isLoading ? (
          <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
        ) : activityQuery.isError || !activity ? (
          <Notice tone="error">
            {activityQuery.error instanceof Error ? activityQuery.error.message : '활동을 찾을 수 없습니다.'}
          </Notice>
        ) : (
          <section role="tabpanel" aria-label={tabLabel} className="flex flex-1 flex-col gap-4">
            <div className="flex items-center gap-3">
              <h2 className="text-base font-semibold">
                {activity.goalId ? (goalQuery.data?.title ?? '학습 목표') : ACTIVITY_TYPE_LABELS[activity.activityType]}
              </h2>
              <ActivityStatusBadge status={activity.status} />
            </div>

            {tab === 'quiz' &&
              (activity.status === 'NOT_STARTED' ? (
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
                <Notice tone="error">
                  {resultQuery.error instanceof Error ? resultQuery.error.message : '결과를 불러오지 못했습니다.'}
                </Notice>
              ) : (
                <QuizResultView result={resultQuery.data} quizItemCount={quizItemCount} />
              ))}

            {tab === 'overview' &&
              (!roleplayDone ? (
                <p className="text-sm text-[var(--instructor-text-muted)]">
                  역할극을 마치면 목표별로 필요했던 도움과 다음 수업 제안이 여기에 보여요.
                </p>
              ) : reportQuery.isLoading || difficultyQuery.isLoading ? (
                <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
              ) : reportQuery.isError ? (
                <Notice tone="error">{reportQuery.error.message}</Notice>
              ) : (
                <>
                  {!report && (
                    <Notice tone="info">
                      AI 요약을 만드는 중이거나 만들지 못했어요. 서버가 기록으로 정한 다음 난이도와 제안은 아래에서 볼 수 있어요.
                    </Notice>
                  )}
                  <ReportOverview
                    report={report}
                    recommendations={recommendationsQuery.data ?? []}
                    latestDifficulty={latestDifficulty}
                    classId={child?.classId ?? null}
                    childId={activity.childId}
                  />
                </>
              ))}

            {tab === 'roleplay' && report && <RoleplayGoalsView report={report} />}
            {tab === 'difficulty' && report && <DifficultyBasisView report={report} />}
            {tab === 'technical' && report && <TechnicalRecordView report={report} />}
          </section>
        )}
      </div>
    </InstructorLayout>
  )
}

function QuizResultView({ result, quizItemCount }: { result: QuizResult; quizItemCount: number }) {
  // 카메라·네트워크 같은 기술 문제로 판정하지 못한 문항은 서버가 분모에서 뺀다. 정답률을 잘못 읽지 않게 따로 보인다.
  const excludedCount = Math.max(quizItemCount - result.validQuestionCount, 0)
  const accuracy =
    result.overallAccuracy === null ? '-' : `${Math.round(result.overallAccuracy * 100)}%`

  return (
    <div className="grid grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] items-start gap-4">
      <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
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

      <div className={cn(cardClass, 'flex flex-col gap-3 p-5 text-sm leading-[1.7]')}>
        <h3 className="font-semibold">첫 난이도</h3>
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
    </div>
  )
}

export { ActivityReportPage }
