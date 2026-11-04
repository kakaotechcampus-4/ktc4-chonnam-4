import { Link } from 'react-router-dom'
import { useQueries } from '@tanstack/react-query'
import { getRoleplayDifficulty, type Child, type RoleplayDifficulty } from '../../api'
import { ActivityStatusBadge } from '../ActivityStatusBadge'
import { tableRowClass } from '../styles'
import { formatDate } from '../../lib/dateTime'
import { orNullOn404, useActivityGoal } from './learningQueries'

/**
 * 학급 개요의 "최근 학습 요약"(시안 T-CLS-03). 아동마다 가장 최근에 마친 역할극을 최근 순으로 한 줄씩 보인다.
 * 기준은 다음 역할극 난이도(API-REC-06)라 AI 요약이 없어도 줄이 생긴다. 학급 단위 API 가 없어 아동마다 부른다.
 */
function RecentLearningList({ classChildren }: { classChildren: Child[] }) {
  const difficultyQueries = useQueries({
    queries: classChildren.map((child) => ({
      queryKey: ['children', child.childId, 'roleplay-difficulty'],
      queryFn: () => orNullOn404(() => getRoleplayDifficulty(child.childId)),
    })),
  })
  const rows = classChildren
    .flatMap((child, index) => {
      const difficulty = difficultyQueries[index]?.data
      return difficulty ? [{ child, difficulty }] : []
    })
    .sort((a, b) => b.difficulty.decidedAt.localeCompare(a.difficulty.decidedAt))

  if (rows.length === 0) {
    return (
      <p className="text-sm text-[var(--instructor-text-subtle)]">
        {difficultyQueries.some((query) => query.isLoading)
          ? '불러오는 중...'
          : '역할극을 마친 아동의 최근 학습이 여기에 모여요. 누르면 그 활동 리포트로 가요.'}
      </p>
    )
  }
  return (
    <ul aria-label="최근 학습" className="flex flex-col">
      {rows.map(({ child, difficulty }) => (
        <RecentLearningRow key={child.childId} child={child} difficulty={difficulty} />
      ))}
    </ul>
  )
}

/** 한 줄: 아동 · 활동 목표, 활동 상태, 마친 날. 누르면 그 활동 리포트로 간다. */
function RecentLearningRow({ child, difficulty }: { child: Child; difficulty: RoleplayDifficulty }) {
  const { activity, goalTitle } = useActivityGoal(difficulty.sourceActivityId)
  return (
    <li className={tableRowClass}>
      <Link
        to={`/activities/${difficulty.sourceActivityId}/report`}
        className="flex items-center gap-3 px-1 py-3 hover:bg-[var(--instructor-surface-muted)]"
      >
        <span aria-hidden className="size-7 shrink-0 rounded-full bg-[var(--instructor-avatar)]" />
        <span className="min-w-0 flex-1 truncate text-sm">
          {child.displayName}
          {goalTitle && ` · ${goalTitle}`}
        </span>
        {activity && <ActivityStatusBadge status={activity.status} />}
        <span className="w-14 shrink-0 text-right text-xs text-[var(--instructor-text-subtle)]">
          {formatDate(activity?.completedAt ?? difficulty.decidedAt)}
        </span>
      </Link>
    </li>
  )
}

export { RecentLearningList }
