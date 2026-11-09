import { describe, expect, it } from 'vitest'
import { completionByType, countByStatus, recentEvents } from './activitySummary'

const at = (day: number) => `2026-11-0${day}T10:00:00+09:00`

describe('활동 요약', () => {
  it('상태별로 세고, 일시 중지와 복구 필요는 함께 "챙길 활동"으로 센다', () => {
    const counts = countByStatus([
      { status: 'NOT_STARTED', activityType: 'FIRST_COMBINED' },
      { status: 'IN_PROGRESS', activityType: 'FIRST_COMBINED' },
      { status: 'PAUSED', activityType: 'ROLEPLAY' },
      { status: 'RECOVERY_NEEDED', activityType: 'ROLEPLAY' },
      { status: 'COMPLETED', activityType: 'SELF_EMOTION_QUIZ' },
    ])
    expect(counts).toEqual({ total: 5, notStarted: 1, inProgress: 1, needsAttention: 2, completed: 1 })
  })

  it('유형별 완료 수는 배정한 유형만 정해진 순서로 보인다', () => {
    expect(
      completionByType([
        { status: 'COMPLETED', activityType: 'ROLEPLAY' },
        { status: 'IN_PROGRESS', activityType: 'ROLEPLAY' },
        { status: 'COMPLETED', activityType: 'FIRST_COMBINED' },
      ]),
    ).toEqual([
      { activityType: 'FIRST_COMBINED', completed: 1, total: 1 },
      { activityType: 'ROLEPLAY', completed: 1, total: 2 },
    ])
  })

  it('타임라인은 활동마다 가장 최근 일 하나를 최근 순으로 개수만큼 보인다', () => {
    const done = { id: 'done', assignedAt: at(1), startedAt: at(2), completedAt: at(5) }
    const started = { id: 'started', assignedAt: at(1), startedAt: at(3), completedAt: null }
    const assigned = { id: 'assigned', assignedAt: at(4), startedAt: null, completedAt: null }
    const events = recentEvents([started, done, assigned], 2)
    expect(events.map((e) => [e.activity.id, e.kind])).toEqual([
      ['done', 'COMPLETED'],
      ['assigned', 'ASSIGNED'],
    ])
  })
})
