import type { ActivityStatus, ActivityType } from '../api'

type Countable = { status: ActivityStatus; activityType: ActivityType }

export type StatusCounts = {
  total: number
  notStarted: number
  inProgress: number
  /** 일시 중지와 복구 필요. 시안의 "복구 필요" 칸이다. 서버 상태 그대로 센다. */
  needsAttention: number
  completed: number
}

/** 활동 상태별 개수. 진행 중은 IN_PROGRESS 만 센다(일시 중지·복구 필요는 따로). */
export function countByStatus(activities: Countable[]): StatusCounts {
  const counts: StatusCounts = { total: activities.length, notStarted: 0, inProgress: 0, needsAttention: 0, completed: 0 }
  for (const { status } of activities) {
    if (status === 'NOT_STARTED') counts.notStarted++
    else if (status === 'IN_PROGRESS') counts.inProgress++
    else if (status === 'PAUSED' || status === 'RECOVERY_NEEDED') counts.needsAttention++
    else if (status === 'COMPLETED') counts.completed++
  }
  return counts
}

/** 활동 관리 "전체 활동 상태 분포"의 줄 순서. 진행 흐름(시작 전 → 진행 → 멈춤 → 완료) 순이다. */
export const STATUS_ORDER: ActivityStatus[] = ['NOT_STARTED', 'IN_PROGRESS', 'PAUSED', 'RECOVERY_NEEDED', 'COMPLETED']

/** 서버 상태 다섯 가지를 하나씩 센다. 대시보드의 countByStatus 와 달리 일시 중지·복구 필요를 나누어 센다. */
export function countEachStatus(activities: { status: ActivityStatus }[]): Record<ActivityStatus, number> {
  const counts = Object.fromEntries(STATUS_ORDER.map((status) => [status, 0])) as Record<ActivityStatus, number>
  for (const { status } of activities) counts[status]++
  return counts
}

const TYPE_ORDER: ActivityType[] = ['FIRST_COMBINED', 'SELF_EMOTION_QUIZ', 'OTHER_EMOTION_QUIZ', 'ROLEPLAY']

export type TypeCompletion = { activityType: ActivityType; completed: number; total: number }

/** 시안 "활동 현황"의 유형별 완료 수(예: 역할극 7/10). 배정한 적 있는 유형만 정해진 순서로 돌려준다. */
export function completionByType(activities: Countable[]): TypeCompletion[] {
  return TYPE_ORDER.flatMap((activityType) => {
    const ofType = activities.filter((activity) => activity.activityType === activityType)
    if (ofType.length === 0) return []
    return [{ activityType, completed: ofType.filter((a) => a.status === 'COMPLETED').length, total: ofType.length }]
  })
}

type Timed = { assignedAt: string; startedAt: string | null; completedAt: string | null }

export type ActivityEventKind = 'COMPLETED' | 'STARTED' | 'ASSIGNED'

export type ActivityEvent<T> = { activity: T; kind: ActivityEventKind; at: string }

/**
 * 시안 "최근 학습 타임라인". 활동마다 가장 최근에 일어난 일(완료 > 시작 > 배정) 하나만 시간순(최근 먼저)으로 늘어놓는다.
 * 서버에 따로 사건 기록이 없어서 활동의 세 시각에서 만든다.
 */
export function recentEvents<T extends Timed>(activities: T[], limit: number): ActivityEvent<T>[] {
  return activities
    .map((activity): ActivityEvent<T> => {
      if (activity.completedAt) return { activity, kind: 'COMPLETED', at: activity.completedAt }
      if (activity.startedAt) return { activity, kind: 'STARTED', at: activity.startedAt }
      return { activity, kind: 'ASSIGNED', at: activity.assignedAt }
    })
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
    .slice(0, limit)
}

export const EVENT_LABELS: Record<ActivityEventKind, string> = {
  COMPLETED: '완료',
  STARTED: '시작',
  ASSIGNED: '배정',
}
