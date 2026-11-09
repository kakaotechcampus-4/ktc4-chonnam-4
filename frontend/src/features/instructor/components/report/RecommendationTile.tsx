import { useId } from 'react'
import { Link } from 'react-router-dom'
import type { Recommendation } from '../../api'
import { focusRingClass } from '../styles'
import { DecisionChip, EvidenceMeter, RecommendationTypeChip } from './ReportChips'
import { recommendationTitle, type NextLevel } from './reportHelpers'
import { cn } from '@/lib/utils'

const tileClass =
  'relative flex flex-col gap-3 rounded-[var(--instructor-radius-card)] border border-[var(--instructor-border)] bg-[var(--instructor-surface)] p-4'

/**
 * 추천 카드 한 장(시안 T-REC-01). 유형 칩·권장 난이도·제목·이유·근거 수준 순서다.
 * select 를 주면 고르는 카드(라디오)이고, to 를 주면 누르면 그 주소로 가는 카드다. 둘 다 결정은 남기지 않는다.
 */
function RecommendationTile({
  recommendation,
  level,
  select,
  to,
}: {
  recommendation: Recommendation
  level: NextLevel | null
  select?: { name: string; checked: boolean; onChange: () => void }
  to?: string
}) {
  const titleId = useId()
  const title = recommendationTitle(recommendation)

  const body = (
    <>
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <RecommendationTypeChip type={recommendation.type} />
          <DecisionChip decision={recommendation.decision} />
        </div>
        {level && (
          <span className="text-xs whitespace-nowrap text-[var(--instructor-text-subtle)]">
            권장 {level.scenarioLevel} / {level.supportLevel}
          </span>
        )}
      </div>
      <h3 id={titleId} className="text-[15px] leading-6 font-semibold break-keep">
        {to ? (
          // 카드 전체를 누를 수 있게 링크의 누르는 영역을 카드 크기로 넓힌다.
          <Link to={to} className={cn('after:absolute after:inset-0 hover:underline', focusRingClass)}>
            {title}
          </Link>
        ) : (
          title
        )}
      </h3>
      <p className="text-xs leading-5 text-[var(--instructor-text-muted)] break-keep">{recommendation.reason}</p>
      <div className="mt-auto pt-1">
        <EvidenceMeter sufficiency={recommendation.evidenceSufficiency} />
      </div>
    </>
  )

  if (select) {
    return (
      <label
        className={cn(
          tileClass,
          'cursor-pointer hover:border-[var(--instructor-border-strong)]',
          'has-checked:border-[var(--instructor-primary)] has-checked:shadow-[0_0_0_1px_var(--instructor-primary)]',
          'has-focus-visible:outline-3 has-focus-visible:outline-offset-2 has-focus-visible:outline-[var(--instructor-focus)]',
        )}
      >
        <input
          type="radio"
          name={select.name}
          checked={select.checked}
          onChange={select.onChange}
          aria-labelledby={titleId}
          className="sr-only"
        />
        {body}
      </label>
    )
  }
  return (
    <article aria-labelledby={titleId} className={cn(tileClass, to && 'hover:border-[var(--instructor-border-strong)]')}>
      {body}
    </article>
  )
}

export { RecommendationTile }
