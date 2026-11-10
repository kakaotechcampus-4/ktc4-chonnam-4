import { useState, type ReactNode } from 'react'
import type { AgentRunOverview } from '../api'
import { clock, count, dayClock, duration, niceMax } from '../lib/format'
import { PATH_KEY, PATH_LABEL, PATHS } from '../lib/labels'
import { runsIn } from '../lib/period'
import { focusRing, pathColor } from '../lib/tones'
import { Legend, PathIcon } from './primitives'

type Bucket = AgentRunOverview['series'][number]

const TICKS = [1, 0.75, 0.5, 0.25, 0]

function bucketLabel(bucketMinutes: number) {
  return bucketMinutes >= 180 ? dayClock : clock
}

function bucketSpan(bucketMinutes: number) {
  return bucketMinutes >= 60 ? `${bucketMinutes / 60}시간` : `${bucketMinutes}분`
}

/**
 * 시간축 차트의 공통 틀(Grafana 패널 모양): 왼쪽 눈금, 가는 가로 격자, 아래 시각, 구간마다 마우스·키보드로 고르는 칸, 값 상자.
 * 칸은 그려진 표시보다 넓게 잡아 가는 막대·선도 쉽게 고를 수 있다. 값은 툴팁 말고도 화면 낭독용 표로 모두 읽힌다.
 */
function TimeFrame({
  series,
  bucketMinutes,
  max,
  format,
  height,
  mode,
  plot,
  tooltip,
  describe,
}: {
  series: Bucket[]
  bucketMinutes: number
  max: number
  format: (value: number) => string
  height: number
  mode: 'band' | 'line'
  plot: (active: number | null) => ReactNode
  tooltip: (bucket: Bucket) => ReactNode
  describe: (bucket: Bucket) => string
}) {
  const [active, setActive] = useState<number | null>(null)
  const label = bucketLabel(bucketMinutes)
  const every = Math.max(1, Math.ceil(series.length / 6))
  const shown = active === null ? null : series[active]

  return (
    <div className="flex gap-2">
      <div aria-hidden className="ops-num relative w-10 shrink-0 text-right text-[11px] text-[var(--ops-muted)]" style={{ height }}>
        {TICKS.map((tick) => (
          <span key={tick} className="absolute right-0 -translate-y-1/2" style={{ top: `${(1 - tick) * 100}%` }}>
            {format(max * tick)}
          </span>
        ))}
      </div>

      <div className="relative min-w-0 flex-1">
        <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0" style={{ height }}>
          {TICKS.map((tick) => (
            <div
              key={tick}
              className={`absolute inset-x-0 border-t ${tick === 0 ? 'border-[var(--ops-axis)]' : 'border-[var(--ops-grid)]'}`}
              style={{ top: `${(1 - tick) * 100}%` }}
            />
          ))}
        </div>

        <div className="pointer-events-none absolute inset-x-0 top-0" style={{ height }}>
          {plot(active)}
        </div>

        <div className="relative flex" style={{ height }} onMouseLeave={() => setActive(null)}>
          {series.map((bucket, index) => (
            <button
              key={bucket.at}
              type="button"
              aria-label={describe(bucket)}
              onMouseEnter={() => setActive(index)}
              onFocus={() => setActive(index)}
              onBlur={() => setActive(null)}
              className={`relative min-w-0 flex-1 ${focusRing} ${mode === 'band' && active === index ? 'bg-white/[0.05]' : ''}`}
            >
              {mode === 'line' && active === index && (
                <span aria-hidden className="absolute inset-y-0 left-1/2 border-l border-dashed border-[var(--ops-muted)]" />
              )}
            </button>
          ))}
        </div>

        <div aria-hidden className="ops-num mt-1.5 flex text-[11px] text-[var(--ops-muted)]">
          {series.map((bucket, index) => (
            <span key={bucket.at} className="min-w-0 flex-1 text-center whitespace-nowrap">
              {index % every === 0 ? label(bucket.at) : ''}
            </span>
          ))}
        </div>

        {shown && active !== null && (
          <div
            role="status"
            className="pointer-events-none absolute top-1 z-10 w-48 rounded-[4px] border border-[var(--ops-border)] bg-[var(--ops-panel-raised)] p-2.5 text-xs shadow-lg"
            style={
              active < series.length / 2
                ? { left: `calc(${((active + 1) / series.length) * 100}% + 6px)` }
                : { right: `calc(${((series.length - active) / series.length) * 100}% + 6px)` }
            }
          >
            <p className="ops-num font-semibold text-[var(--ops-text)]">
              {dayClock(shown.at)} · {bucketSpan(bucketMinutes)}
            </p>
            <div className="mt-1.5">{tooltip(shown)}</div>
          </div>
        )}
      </div>
    </div>
  )
}

function TooltipRow({ label, value }: { label: ReactNode; value: string }) {
  return (
    <p className="flex items-center justify-between gap-2 py-0.5 text-[var(--ops-text-2)]">
      <span className="inline-flex items-center gap-1.5">{label}</span>
      <span className="ops-num text-[var(--ops-text)]">{value}</span>
    </p>
  )
}

/** 시간 구간별 실행 수를 흐름 종류로 쌓은 막대(이미지의 DAU 패널 모양). 축은 건수 하나만 쓴다. */
export function PathSeriesChart({ series, bucketMinutes }: { series: Bucket[]; bucketMinutes: number }) {
  const max = niceMax(Math.max(0, ...series.map(runsIn)))
  return (
    <div>
      <TimeFrame
        series={series}
        bucketMinutes={bucketMinutes}
        max={max}
        format={(value) => count(Math.round(value))}
        height={168}
        mode="band"
        describe={(bucket) => `${bucketLabel(bucketMinutes)(bucket.at)} 실행 ${count(runsIn(bucket))}건`}
        plot={() => (
          <div className="flex h-full">
            {series.map((bucket) => {
              const parts = PATHS.filter((path) => bucket[PATH_KEY[path]] > 0)
              return (
                <div key={bucket.at} className="flex min-w-0 flex-1 justify-center px-[1px]">
                  <div className="flex h-full w-full max-w-[28px] flex-col-reverse gap-[2px]">
                    {parts.map((path, position) => (
                      <div
                        key={path}
                        style={{ flex: `0 0 ${(bucket[PATH_KEY[path]] / max) * 100}%`, minHeight: 2, background: pathColor(path) }}
                        className={position === parts.length - 1 ? 'rounded-t-[2px]' : ''}
                      />
                    ))}
                  </div>
                </div>
              )
            })}
          </div>
        )}
        tooltip={(bucket) => (
          <>
            {PATHS.map((path) => (
              <TooltipRow
                key={path}
                label={
                  <>
                    <PathIcon path={path} size={12} />
                    {PATH_LABEL[path]}
                  </>
                }
                value={count(bucket[PATH_KEY[path]])}
              />
            ))}
            <div className="mt-1 border-t border-[var(--ops-border)] pt-1">
              <TooltipRow label="합계" value={count(runsIn(bucket))} />
            </div>
          </>
        )}
      />
      <Legend items={PATHS.map((path) => ({ key: path, label: PATH_LABEL[path], color: pathColor(path) }))} />
      <SeriesTable
        caption="시간 구간별 흐름 종류 건수"
        series={series}
        columns={PATHS.map((path) => ({ label: PATH_LABEL[path], value: (bucket) => count(bucket[PATH_KEY[path]]) }))}
      />
    </div>
  )
}

const LATENCY = [
  { key: 'p95Ms', label: 'p95', color: 'var(--ops-accent)', dashed: false },
  { key: 'p50Ms', label: '중앙값', color: 'var(--ops-path-normal)', dashed: false },
] as const

/** 한 구간의 점 위치(%). 실행이 없던 구간은 null 이라 선을 끊는다. */
function points(series: Bucket[], key: 'p50Ms' | 'p95Ms', max: number) {
  return series.map((bucket, index) =>
    runsIn(bucket) === 0 ? null : { x: ((index + 0.5) / series.length) * 100, y: 100 - (bucket[key] / max) * 100 },
  )
}

function segments(list: ({ x: number; y: number } | null)[]) {
  const runs: { x: number; y: number }[][] = []
  let current: { x: number; y: number }[] = []
  for (const point of list) {
    if (point) current.push(point)
    else if (current.length) {
      runs.push(current)
      current = []
    }
  }
  if (current.length) runs.push(current)
  return runs
}

/** 구간별 걸린 시간의 중앙값·p95 선(이미지의 지연 시간 패널 모양). 실행이 없던 구간에서는 선이 끊긴다. */
export function LatencyChart({ series, bucketMinutes }: { series: Bucket[]; bucketMinutes: number }) {
  const max = niceMax(Math.max(0, ...series.map((bucket) => bucket.p95Ms)))
  const lines = LATENCY.map((line) => ({ ...line, points: points(series, line.key, max) }))
  return (
    <div>
      <TimeFrame
        series={series}
        bucketMinutes={bucketMinutes}
        max={max}
        format={duration}
        height={168}
        mode="line"
        describe={(bucket) =>
          runsIn(bucket) === 0
            ? `${bucketLabel(bucketMinutes)(bucket.at)} 실행 없음`
            : `${bucketLabel(bucketMinutes)(bucket.at)} 중앙값 ${duration(bucket.p50Ms)}, p95 ${duration(bucket.p95Ms)}`
        }
        plot={(active) => (
          <>
            <svg aria-hidden viewBox="0 0 100 100" preserveAspectRatio="none" className="absolute inset-0 h-full w-full overflow-visible">
              {lines.map((line) =>
                segments(line.points).map((run, index) => (
                  <g key={`${line.key}-${index}`}>
                    {line.key === 'p95Ms' && run.length > 1 && (
                      <path
                        d={`M${run[0].x},100 ${run.map((point) => `L${point.x},${point.y}`).join(' ')} L${run[run.length - 1].x},100Z`}
                        fill={line.color}
                        opacity={0.1}
                      />
                    )}
                    <polyline
                      points={run.map((point) => `${point.x},${point.y}`).join(' ')}
                      fill="none"
                      stroke={line.color}
                      strokeWidth={2}
                      strokeLinejoin="round"
                      vectorEffect="non-scaling-stroke"
                    />
                  </g>
                )),
              )}
            </svg>
            {lines.map((line) =>
              line.points.map((point, index) => {
                const lonely = point && !line.points[index - 1] && !line.points[index + 1]
                if (!point || (!lonely && index !== active)) return null
                return (
                  <span
                    key={`${line.key}-${index}`}
                    className="absolute size-2 -translate-x-1/2 -translate-y-1/2 rounded-full ring-2 ring-[var(--ops-panel)]"
                    style={{ left: `${point.x}%`, top: `${point.y}%`, background: line.color }}
                  />
                )
              }),
            )}
          </>
        )}
        tooltip={(bucket) =>
          runsIn(bucket) === 0 ? (
            <p className="text-[var(--ops-muted)]">이 구간에는 실행이 없습니다.</p>
          ) : (
            <>
              {LATENCY.map((line) => (
                <TooltipRow
                  key={line.key}
                  label={
                    <>
                      <span aria-hidden className="inline-block h-[3px] w-3 rounded-full" style={{ background: line.color }} />
                      {line.label}
                    </>
                  }
                  value={duration(bucket[line.key])}
                />
              ))}
              <TooltipRow label="실행" value={`${count(runsIn(bucket))}건`} />
            </>
          )
        }
      />
      <Legend items={LATENCY.map((line) => ({ key: line.key, label: line.label, color: line.color }))} />
      <SeriesTable
        caption="시간 구간별 걸린 시간"
        series={series.filter((bucket) => runsIn(bucket) > 0)}
        columns={LATENCY.map((line) => ({ label: line.label, value: (bucket) => duration(bucket[line.key]) }))}
      />
    </div>
  )
}

/** 큰 숫자 패널 아래의 작은 추세선. 값은 옆 숫자가 말하므로 낭독기에는 숨긴다. */
export function Sparkline({ values, color }: { values: number[]; color: string }) {
  const max = Math.max(1, ...values)
  const step = values.length > 1 ? 100 / (values.length - 1) : 100
  const line = values.map((value, index) => `${index * step},${100 - (value / max) * 92}`).join(' ')
  return (
    <svg aria-hidden viewBox="0 0 100 100" preserveAspectRatio="none" className="h-10 w-full">
      <path d={`M0,100 L${line.replaceAll(' ', ' L')} L100,100Z`} fill={color} opacity={0.14} />
      <polyline points={line} fill="none" stroke={color} strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
    </svg>
  )
}

function SeriesTable({
  caption,
  series,
  columns,
}: {
  caption: string
  series: Bucket[]
  columns: { label: string; value: (bucket: Bucket) => string }[]
}) {
  return (
    <table className="sr-only">
      <caption>{caption}</caption>
      <thead>
        <tr>
          <th scope="col">구간 시작</th>
          {columns.map((column) => (
            <th key={column.label} scope="col">
              {column.label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {series
          .filter((bucket) => runsIn(bucket) > 0)
          .map((bucket) => (
            <tr key={bucket.at}>
              <th scope="row">{dayClock(bucket.at)}</th>
              {columns.map((column) => (
                <td key={column.label}>{column.value(bucket)}</td>
              ))}
            </tr>
          ))}
      </tbody>
    </table>
  )
}

export type Segment = { key: string; label: string; value: number; color: string }

/** 글자 없는 가는 비율 막대. 옆 칸에 숫자가 따로 있을 때만 쓴다. */
export function SegmentBar({ segments: parts, className = '' }: { segments: Segment[]; className?: string }) {
  const sum = parts.reduce((all, segment) => all + segment.value, 0)
  return (
    <span
      role="img"
      aria-label={parts.map((segment) => `${segment.label} ${segment.value}`).join(', ')}
      className={`flex h-2 gap-[2px] overflow-hidden rounded-[2px] ${sum === 0 ? 'bg-[var(--ops-track)]' : ''} ${className}`}
    >
      {parts
        .filter((segment) => segment.value > 0)
        .map((segment) => (
          <span
            key={segment.key}
            title={`${segment.label} ${count(segment.value)}`}
            style={{ flex: `${segment.value} 0 0`, minWidth: 3, background: segment.color }}
          />
        ))}
    </span>
  )
}

export type RankedRow = { key: string; label: ReactNode; value: number; valueText: string }

/** 큰 것부터 줄 세운 가로 막대(Grafana 바 게이지 모양). 항목이 한 종류라 색은 강조색 하나만 쓴다. */
export function RankedBars({ rows }: { rows: RankedRow[] }) {
  const max = Math.max(1, ...rows.map((row) => row.value))
  return (
    <ul className="flex flex-col gap-2.5">
      {rows.map((row) => (
        <li key={row.key} className="grid grid-cols-[minmax(0,1fr)_auto] items-baseline gap-x-3 gap-y-1">
          <span className="min-w-0 text-[13px] text-[var(--ops-text-2)]">{row.label}</span>
          <span className="ops-num text-[13px] text-[var(--ops-text)]">{row.valueText}</span>
          <span aria-hidden className="col-span-2 h-1.5 rounded-[2px] bg-[var(--ops-track)]">
            <span
              className="block h-full rounded-[2px] bg-[var(--ops-accent-bar)]"
              style={{ width: `${Math.max(1.5, (row.value / max) * 100)}%` }}
            />
          </span>
        </li>
      ))}
    </ul>
  )
}
