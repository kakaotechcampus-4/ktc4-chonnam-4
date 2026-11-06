import { useId, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Presentation } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Character } from './features/child/components/Character'

/**
 * 첫 화면. 강사·아동 진입점을 카드 두 개로 나란히 둔다.
 * Figma 시안이 없어 디자인 시스템(섹션 290:5785) 값으로 구성했다.
 * - 바탕은 Violet 50(#F5F3FF): 강사(흰 바탕)·아동(크림 바탕) 어느 쪽에도 치우치지 않는 색
 * - "느링고" 글자는 Violet 700(#5744C4): 아동 보라(#7B68EE)는 밝은 바탕에서 대비가 4.5:1에 못 미친다
 * - 포커스 링은 Border/Focus(#A99BF5)
 * 링크 이름은 "강사 화면 진입"·"아동 화면 진입" 그대로다(라우터 테스트·e2e가 이 이름으로 찾는다). 설명은 보조 설명으로 붙인다.
 */
function App() {
  return (
    // child-scope 는 아동 글꼴·색 토큰을 쓰려고 붙인다. child-scope 의 크림 바탕이 클래스보다 앞서서 바탕은 style 로 정한다.
    <div
      className="child-scope flex min-h-svh flex-col items-center justify-center gap-8 px-6 py-10"
      style={{ background: '#f5f3ff' }}
    >
      <header className="flex flex-col items-center gap-3 text-center">
        {/* 로고 자리의 장식 그림이라 화면 낭독기에는 읽히지 않게 한다. */}
        <span aria-hidden>
          <Character name="turtle" mood="wave" size="md" className="pointer-events-none" />
        </span>
        <h1 className="font-child-display text-5xl font-extrabold text-[#5744c4]">느링고</h1>
        <p className="text-lg break-keep text-[var(--child-text-muted)]">표정 퀴즈와 역할극으로 마음을 배워요</p>
      </header>

      <nav aria-label="화면 고르기" className="grid w-full max-w-xl grid-cols-1 gap-6 sm:grid-cols-2">
        <EntryCard
          to="/classrooms"
          title="강사 화면 진입"
          description="학급·아동 관리, 활동 배정과 결과 확인"
          visual={
            <span className="flex size-24 items-center justify-center rounded-full bg-[#ede9fe] text-[#6654d9]">
              <Presentation className="size-12" strokeWidth={1.75} aria-hidden />
            </span>
          }
          className="bg-white"
        />
        <EntryCard
          to="/child"
          title="아동 화면 진입"
          description="선생님께 받은 입장 코드로 시작해요"
          visual={
            <span className="flex h-24 items-end gap-1" aria-hidden>
              <Character name="turtle" size="md" className="h-20" />
              <Character name="rabbit" size="md" />
            </span>
          }
          className="bg-[var(--child-bg)]"
        />
      </nav>
    </div>
  )
}

function EntryCard({
  to,
  title,
  description,
  visual,
  className,
}: {
  to: string
  title: string
  description: string
  visual: ReactNode
  className?: string
}) {
  const titleId = useId()
  const descriptionId = useId()

  return (
    <Link
      to={to}
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      className={cn(
        'group flex aspect-square flex-col items-center justify-center gap-4 rounded-[var(--child-radius-card)] border-2 border-[#dedce4] p-6 text-center shadow-sm outline-none transition duration-300',
        'hover:-translate-y-1 hover:border-[#a99bf5] hover:shadow-md focus-visible:ring-4 focus-visible:ring-[#a99bf5] active:scale-[0.98]',
        'max-sm:aspect-auto',
        className
      )}
    >
      {visual}
      <span className="flex flex-col gap-2">
        <span id={titleId} className="font-child-display text-2xl font-extrabold text-[var(--child-text)]">
          {title}
        </span>
        <span id={descriptionId} className="text-base text-balance break-keep text-[var(--child-text-muted)]">
          {description}
        </span>
      </span>
      <ArrowRight
        className="size-6 text-[#5744c4] transition-transform duration-300 group-hover:translate-x-1"
        aria-hidden
      />
    </Link>
  )
}

export default App
