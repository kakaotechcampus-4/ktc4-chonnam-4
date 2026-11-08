import { useQuery } from '@tanstack/react-query'
import {
  ApiError,
  getActivity,
  getLearningGoal,
  getRoleplayDifficulty,
  listLearningReports,
  listRecommendations,
} from '../../api'

/** 없는 리포트·난이도는 오류가 아니라 "아직 없음"이라 null 로 바꾼다. */
async function orNullOn404<T>(load: () => Promise<T>): Promise<T | null> {
  try {
    return await load()
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

/**
 * 아동 한 명의 가장 최근 역할극 학습. 기준은 다음 역할극 난이도(API-REC-06)다. 난이도는 AI 요약과 상관없이 서버가 역할극을 마칠 때마다 정해서,
 * 요약을 만들지 못한 활동도 그 학습 기록(sourceRecordId)으로 추천을 읽을 수 있다. 요약 한 줄은 완료 리포트 목록의 첫 항목이 그 활동일 때만 쓴다.
 */
function useChildLatestLearning(childId: string | null) {
  const difficultyQuery = useQuery({
    queryKey: ['children', childId, 'roleplay-difficulty'],
    queryFn: () => orNullOn404(() => getRoleplayDifficulty(childId!)),
    enabled: !!childId,
  })
  const difficulty = difficultyQuery.data ?? null
  const reportsQuery = useQuery({
    queryKey: ['learning-records', { childId, size: 1 }],
    queryFn: () => listLearningReports(childId!, 1),
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

/**
 * 활동과 그 학습 목표 제목. 시안의 "기준 리포트 · 9/9 친구가 속상할 때 위로하기"·"민준 · 친구가 속상할 때 위로하기" 줄에 쓴다.
 * 활동 리포트 화면과 같은 캐시 키라 그 화면으로 가면 다시 부르지 않는다.
 */
function useActivityGoal(activityId: string | null) {
  const activityQuery = useQuery({
    queryKey: ['activities', activityId],
    queryFn: () => getActivity(activityId!),
    enabled: !!activityId,
  })
  const activity = activityQuery.data?.activity ?? null
  const goalQuery = useQuery({
    queryKey: ['learning-goals', activity?.goalId],
    queryFn: () => getLearningGoal(activity!.goalId!),
    enabled: !!activity?.goalId,
  })
  return { activity, goalTitle: goalQuery.data?.title ?? null }
}

export { orNullOn404, useActivityGoal, useChildLatestLearning }
