import type { ReactNode } from 'react'
import { cardClass } from './styles'
import { cn } from '@/lib/utils'

/** 지표 한 칸(시안 리포트의 "턴 수 9"·"완료 활동 14" 같은 카드). value 자리에 칩처럼 글자가 아닌 것도 둘 수 있다. */
function StatCard({ label, value, note }: { label: string; value: ReactNode; note?: string }) {
  return (
    <div className={cn(cardClass, 'flex flex-col gap-2 px-5 py-4')}>
      <p className="text-xs text-[var(--instructor-text-subtle)]">{label}</p>
      {typeof value === 'string' ? <p className="text-xl font-semibold">{value}</p> : value}
      {note && <p className="text-xs text-[var(--instructor-text-subtle)]">{note}</p>}
    </div>
  )
}

export { StatCard }
