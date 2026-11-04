import { useId } from 'react'
import type { Recommendation } from '../../api'
import { outlineButtonClass, primaryButtonClass } from '../styles'
import { RecommendationTile } from './RecommendationTile'
import type { NextLevel } from './reportHelpers'

/**
 * 추천 학습 목록(시안 T-REC-01). 제안을 카드로 나란히 두고 하나를 고른 뒤 추천 상세에서 결정한다.
 * 고르기만으로는 아무것도 남지 않는다. "직접 목표 입력"은 제안을 쓰지 않고 활동 만들기로 간다.
 */
function RecommendationBoard({
  recommendations,
  level,
  selectedId,
  onSelect,
  onOpen,
  onManual,
}: {
  recommendations: Recommendation[]
  level: NextLevel | null
  selectedId: string | null
  onSelect: (recommendationId: string) => void
  onOpen: () => void
  onManual: () => void
}) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4">
      <div>
        <h2 id={headingId} className="text-base font-semibold">
          다음 활동으로 무엇을 할까요?
        </h2>
        <p className="mt-1 text-sm text-[var(--instructor-text-muted)]">
          최근 리포트의 증거를 근거로 {recommendations.length}가지를 제안합니다.
        </p>
      </div>

      <div role="radiogroup" aria-labelledby={headingId} className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {recommendations.map((recommendation) => (
          <RecommendationTile
            key={recommendation.recommendationId}
            recommendation={recommendation}
            level={level}
            select={{
              name: 'recommendation',
              checked: recommendation.recommendationId === selectedId,
              onChange: () => onSelect(recommendation.recommendationId),
            }}
          />
        ))}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--instructor-border)] pt-4">
        <p className="text-xs text-[var(--instructor-text-subtle)]">
          추천은 참고용입니다. 강사가 목표를 직접 입력할 수도 있습니다.
        </p>
        <div className="flex gap-2">
          <button type="button" onClick={onManual} className={outlineButtonClass}>
            직접 목표 입력
          </button>
          <button type="button" onClick={onOpen} disabled={!selectedId} className={primaryButtonClass}>
            추천 상세 보기
          </button>
        </div>
      </div>
    </section>
  )
}

export { RecommendationBoard }
