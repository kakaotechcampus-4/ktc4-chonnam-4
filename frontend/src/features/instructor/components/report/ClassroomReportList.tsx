import { Link, useSearchParams } from 'react-router-dom'
import type { ActivityType, LearningReport, SupportLevel } from '../../api'
import { useClassroomReports } from '../../hooks/classroomQueries'
import { ACTIVITY_TYPE_LABELS } from '../../lib/activityTypes'
import { formatDate } from '../../lib/dateTime'
import { SUPPORT_LABELS } from '../../lib/reportLabels'
import { describeError } from '../../errorMessage'
import { Notice } from '../Feedback'
import { StatCard } from '../StatCard'
import { fieldLabelClass, selectClass, tableClass, tableHeadRowClass, tableRowClass } from '../styles'

const PERIODS = [
  { value: 'all', label: '전체', days: null },
  { value: '7', label: '최근 7일', days: 7 },
  { value: '30', label: '최근 30일', days: 30 },
] as const

// 완료 리포트는 역할극이 있는 활동에만 생긴다.
const REPORT_TYPES: ActivityType[] = ['FIRST_COMBINED', 'ROLEPLAY']

const SUPPORT_ORDER: SupportLevel[] = ['S0', 'S1', 'S2', 'S3']

/** 다음 시작 지원이 이번 시작 지원보다 적으면(도움이 줄면) true. 시안 "지원 수준 하향"이다. */
function supportReduced(report: LearningReport) {
  return (
    SUPPORT_ORDER.indexOf(report.difficultyDecision.nextSupportLevel) <
    SUPPORT_ORDER.indexOf(report.roleplay.startSupportLevel)
  )
}

/** 아동 보기(이름순). 같은 반 동명이인은 이름만으로 고를 수 없어 "김하늘 (2)"처럼 순번을 붙인다. 순번은 아동 ID 순이라 리포트가 늘어도 바뀌지 않는다. */
function childOptionsOf(reports: LearningReport[]) {
  const names = new Map<string, string>()
  for (const report of reports) if (!names.has(report.childId)) names.set(report.childId, report.childName)
  const seen = new Map<string, number>()
  const total = new Map<string, number>()
  for (const name of names.values()) total.set(name, (total.get(name) ?? 0) + 1)
  const sorted = [...names].sort(([aId, a], [bId, b]) => a.localeCompare(b, 'ko') || aId.localeCompare(bId))
  return sorted.map(([childId, name]) => {
    const order = (seen.get(name) ?? 0) + 1
    seen.set(name, order)
    return { childId, label: (total.get(name) ?? 0) > 1 ? `${name} (${order})` : name }
  })
}

function supportChange(report: LearningReport) {
  const start = report.roleplay.startSupportLevel
  const next = report.difficultyDecision.nextSupportLevel
  return start === next ? `${SUPPORT_LABELS[start]} 유지` : `${SUPPORT_LABELS[start]} → ${SUPPORT_LABELS[next]}`
}

/**
 * 학급 리포트 목록(시안 T-RPT-01). 학급 하나의 완료 리포트를 한 번에 받고, 아동·기간·유형은 화면에서 거른다.
 * 거르는 값은 주소(?childId=&period=&type=)에 남겨 새로고침하거나 리포트 상세에서 돌아와도 그대로다.
 * 시안의 "평균 턴 수"·"기술 오류 기록"은 서버가 주지 않아 넣지 않는다.
 */
function ClassroomReportList({ classId, now = new Date() }: { classId: string; now?: Date }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const reportsQuery = useClassroomReports(classId)
  const childId = searchParams.get('childId') ?? ''
  const period = PERIODS.find((p) => p.value === searchParams.get('period')) ?? PERIODS[0]
  const type = REPORT_TYPES.find((t) => t === searchParams.get('type')) ?? ''

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(searchParams)
    if (value === '' || value === 'all') next.delete(key)
    else next.set(key, value)
    setSearchParams(next, { replace: true })
  }

  if (reportsQuery.isLoading) return <p className="text-sm text-[var(--instructor-text-muted)]">불러오는 중...</p>
  if (reportsQuery.isError) {
    return <Notice tone="error">{describeError(reportsQuery.error, '리포트를 불러오지 못했습니다.')}</Notice>
  }

  const reports = reportsQuery.data ?? []
  const childOptions = childOptionsOf(reports)
  const since = period.days === null ? null : now.getTime() - period.days * 24 * 60 * 60 * 1000
  const shown = reports.filter(
    (r) =>
      (childId === '' || r.childId === childId) &&
      (type === '' || r.activityType === type) &&
      (since === null || Date.parse(r.completedAt) >= since),
  )
  const reducedChildren = new Set(shown.filter(supportReduced).map((r) => r.childId)).size

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap gap-3">
        <label className={fieldLabelClass}>
          아동
          <select value={childId} onChange={(e) => setFilter('childId', e.target.value)} className={selectClass}>
            <option value="">전체</option>
            {childOptions.map(({ childId: id, label }) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className={fieldLabelClass}>
          기간
          <select value={period.value} onChange={(e) => setFilter('period', e.target.value)} className={selectClass}>
            {PERIODS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
        </label>
        <label className={fieldLabelClass}>
          활동 유형
          <select value={type} onChange={(e) => setFilter('type', e.target.value)} className={selectClass}>
            <option value="">전체</option>
            {REPORT_TYPES.map((t) => (
              <option key={t} value={t}>
                {ACTIVITY_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <StatCard label="완료 활동" value={String(shown.length)} />
        <StatCard label="다음에 도움이 줄어든 아동" value={`${reducedChildren}명`} note="다음 역할극 시작 지원이 이번보다 적어요" />
      </div>

      {shown.length === 0 ? (
        <p className="text-sm text-[var(--instructor-text-muted)]">
          {reports.length === 0 ? '아직 완료한 리포트가 없습니다.' : '조건에 맞는 리포트가 없습니다.'}
        </p>
      ) : (
        <div className={tableClass}>
          <table className="w-full">
            <thead>
              <tr className={tableHeadRowClass}>
                <th scope="col" className="px-2 py-2.5 font-normal">
                  활동 · 아동
                </th>
                <th scope="col" className="px-2 py-2.5 font-normal">
                  유형
                </th>
                <th scope="col" className="px-2 py-2.5 font-normal">
                  난이도
                </th>
                <th scope="col" className="px-2 py-2.5 font-normal">
                  지원 변화
                </th>
                <th scope="col" className="px-2 py-2.5 font-normal">
                  완료일
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((report) => (
                <tr key={report.recordId} className={tableRowClass}>
                  <td className="px-2 py-3">
                    <Link to={`/activities/${report.activityId}/report`} className="hover:underline">
                      {report.goalTitle ?? ACTIVITY_TYPE_LABELS[report.activityType]}
                    </Link>
                    <span className="ml-2 text-xs text-[var(--instructor-text-subtle)]">{report.childName}</span>
                  </td>
                  <td className="px-2 py-3 text-[var(--instructor-text-muted)]">
                    {ACTIVITY_TYPE_LABELS[report.activityType]}
                  </td>
                  <td className="px-2 py-3">{report.roleplay.scenarioLevel}</td>
                  <td className="px-2 py-3 text-[var(--instructor-text-muted)]">{supportChange(report)}</td>
                  <td className="px-2 py-3 text-[var(--instructor-text-muted)]">{formatDate(report.completedAt, now)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

export { ClassroomReportList }
