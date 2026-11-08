import { Link } from 'react-router-dom'
import type { ClassroomActivity } from '../api'
import { ACTIVITY_TYPE_LABELS } from '../lib/activityTypes'
import { formatDateTime } from '../lib/dateTime'
import { ActivityStatusBadge } from './ActivityStatusBadge'
import { tableClass, tableHeadRowClass, tableRowClass } from './styles'

/** 학급 상세 "배정 활동" 탭의 표(시안 T-CLS-05). 아동·목표·유형·상태·배정·완료 순이고, 목표를 누르면 활동 리포트로 간다. */
function ClassroomActivityTable({ classId, activities }: { classId: string; activities: ClassroomActivity[] }) {
  return (
    <div className={tableClass}>
      <table className="w-full">
        <thead>
          <tr className={tableHeadRowClass}>
            <th scope="col" className="px-2 py-2.5 font-normal">
              아동
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              학습 목표
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              유형
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              상태
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              배정
            </th>
            <th scope="col" className="px-2 py-2.5 font-normal">
              완료
            </th>
          </tr>
        </thead>
        <tbody>
          {activities.map((activity) => (
            <tr key={activity.activityId} className={tableRowClass}>
              <td className="px-2 py-3">
                <Link to={`/classrooms/${classId}/children/${activity.childId}`} className="hover:underline">
                  {activity.childName}
                </Link>
              </td>
              <td className="px-2 py-3">
                <Link to={`/activities/${activity.activityId}/report`} className="hover:underline">
                  {activity.goalTitle ?? ACTIVITY_TYPE_LABELS[activity.activityType]}
                </Link>
              </td>
              <td className="px-2 py-3 text-[var(--instructor-text-muted)]">
                {ACTIVITY_TYPE_LABELS[activity.activityType]}
              </td>
              <td className="px-2 py-3">
                <ActivityStatusBadge status={activity.status} />
              </td>
              <td className="px-2 py-3 text-[var(--instructor-text-muted)]">{formatDateTime(activity.assignedAt)}</td>
              <td className="px-2 py-3 text-[var(--instructor-text-muted)]">{formatDateTime(activity.completedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export { ClassroomActivityTable }
