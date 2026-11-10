import { CircleCheck, CircleX, Clock3, Info, MessageCircleWarning, RotateCcw, TriangleAlert } from 'lucide-react'
import { useContext, useId, type ReactNode } from 'react'
import type { RunPath, StepStatus } from '../api'
import { codeLabel, PATH_LABEL, STATUS_LABEL } from '../lib/labels'
import { PeriodContext } from '../lib/period'
import { TONE_LABEL, type StepTone } from '../lib/steps'
import { pathColor, statusColor, toneColor } from '../lib/tones'

/**
 * 대시보드의 칸 하나(Grafana 패널 모양). 머리에 제목·설명 아이콘·조회 기간을 두고, 본문은 칸마다 다르다.
 * 설명은 아이콘에 마우스를 올리거나 초점을 주면 보이고, 화면 낭독기는 제목 다음에 바로 읽는다.
 */
export function Panel({
  title,
  hint,
  action,
  className = '',
  scroll = false,
  children,
}: {
  title: string
  hint?: string
  action?: ReactNode
  className?: string
  /** 본문을 일정 높이로 자르고 패널 안에서 스크롤한다(긴 표). */
  scroll?: boolean
  children: ReactNode
}) {
  const period = useContext(PeriodContext)
  const hintId = useId()
  return (
    <section
      aria-label={title}
      aria-describedby={hint ? hintId : undefined}
      className={`flex min-w-0 flex-col rounded-[4px] border border-[var(--ops-border)] bg-[var(--ops-panel)] ${className}`}
    >
      <header className="relative flex min-h-9 items-center justify-between gap-3 px-3 pt-2">
        <div className="flex min-w-0 items-center gap-2">
          <h2 className="truncate text-[13px] font-semibold text-[var(--ops-text)]">{title}</h2>
          {hint && (
            <span className="group inline-flex">
              <span
                tabIndex={0}
                aria-label="설명"
                className="inline-flex rounded-full text-[var(--ops-muted)] outline-none hover:text-[var(--ops-text-2)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--ops-focus)]"
              >
                <Info aria-hidden size={13} />
              </span>
              <span
                id={hintId}
                role="tooltip"
                className="pointer-events-none absolute top-9 left-3 z-20 hidden w-72 max-w-[calc(100%-1.5rem)] rounded-[4px] border border-[var(--ops-border)] bg-[var(--ops-panel-raised)] px-2.5 py-2 text-xs font-normal text-[var(--ops-text-2)] shadow-lg group-focus-within:block group-hover:block"
              >
                {hint}
              </span>
            </span>
          )}
          {period && (
            <span className="inline-flex shrink-0 items-center gap-1 text-xs text-[var(--ops-accent)]">
              <Clock3 aria-hidden size={12} />
              {period}
            </span>
          )}
        </div>
        {action}
      </header>
      <div className={`min-h-0 flex-1 px-3 pt-2 pb-3 ${scroll ? 'max-h-[420px] overflow-auto' : ''}`}>{children}</div>
    </section>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-[13px] text-[var(--ops-muted)]">{children}</p>
}

/** Grafana 범례처럼 짧은 선과 이름. 차트 아래에 둔다. */
export function Legend({ items }: { items: { key: string; label: string; color: string; dashed?: boolean }[] }) {
  return (
    <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-[var(--ops-text-2)]">
      {items.map((item) => (
        <li key={item.key} className="inline-flex items-center gap-1.5">
          <span
            aria-hidden
            className="inline-block w-3.5 rounded-full"
            style={
              item.dashed
                ? { borderTop: `2px dashed ${item.color}` }
                : { height: 3, background: item.color }
            }
          />
          {item.label}
        </li>
      ))}
    </ul>
  )
}

const PATH_ICON = {
  NORMAL: CircleCheck,
  RETRIED: RotateCcw,
  GUIDED: MessageCircleWarning,
  FAILED: TriangleAlert,
} as const

export function PathIcon({ path, size = 14 }: { path: RunPath; size?: number }) {
  const Icon = PATH_ICON[path]
  return <Icon aria-hidden size={size} strokeWidth={2.2} style={{ color: pathColor(path) }} className="shrink-0" />
}

/** 흐름 종류는 색만으로 전하지 않는다. 항상 아이콘과 이름을 같이 보인다. */
export function PathBadge({ path }: { path: RunPath }) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[var(--ops-text)]">
      <PathIcon path={path} />
      {PATH_LABEL[path]}
    </span>
  )
}

const STATUS_ICON = {
  PASSED: CircleCheck,
  REJECTED: RotateCcw,
  FAILED: CircleX,
} as const

export function StatusIcon({ status, size = 14 }: { status: StepStatus; size?: number }) {
  const Icon = STATUS_ICON[status]
  return (
    <Icon aria-hidden size={size} strokeWidth={2.2} style={{ color: statusColor(status) }} className="shrink-0" />
  )
}

export function StatusBadge({ status }: { status: StepStatus }) {
  return (
    <span className="inline-flex items-center gap-1 whitespace-nowrap text-[var(--ops-text-2)]">
      <StatusIcon status={status} />
      {STATUS_LABEL[status]}
    </span>
  )
}

const TONE_ICON = {
  passed: CircleCheck,
  retry: RotateCcw,
  guided: MessageCircleWarning,
  failed: CircleX,
} as const

export function ToneIcon({ tone, size = 14 }: { tone: StepTone; size?: number }) {
  const Icon = TONE_ICON[tone]
  return <Icon aria-hidden size={size} strokeWidth={2.2} style={{ color: toneColor(tone) }} className="shrink-0" />
}

/** 단계가 실행에서 한 역할. 통과가 아니면 글자도 그 색으로 써서 실패 흐름에서 어디서 멈췄는지 바로 보이게 한다. */
export function ToneBadge({ tone }: { tone: StepTone }) {
  return (
    <span
      className="inline-flex items-center gap-1 whitespace-nowrap font-medium"
      style={{ color: tone === 'passed' ? 'var(--ops-text-2)' : toneColor(tone) }}
    >
      <ToneIcon tone={tone} />
      {TONE_LABEL[tone]}
    </span>
  )
}

/** 판정·사유 코드에 한국어 뜻을 앞에 붙인다. 모르는 코드는 코드만 보인다. */
export function CodeWithLabel({ code }: { code: string }) {
  const label = codeLabel(code)
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {label && <span className="text-[var(--ops-text)]">{label}</span>}
      <Code>{code}</Code>
    </span>
  )
}

/** 판정·사유 코드. 로그와 코드에서 그대로 찾을 수 있게 영문 코드를 고정폭으로 보인다. */
export function Code({ children }: { children: ReactNode }) {
  return (
    <span className="ops-mono rounded-[3px] bg-[var(--ops-panel-raised)] px-1.5 py-0.5 text-[var(--ops-text-2)]">
      {children}
    </span>
  )
}
