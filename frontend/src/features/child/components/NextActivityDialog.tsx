import { Dialog as DialogPrimitive } from "radix-ui"
import { Smile, X } from "lucide-react"
import type { ChildActivity } from "../api"
import { ChildButton } from "./ChildButton"
import { Character } from "./Character"

/**
 * 활동을 끝내고 홈으로 돌아왔을 때 띄우는 다음 활동 제안 팝업 (사이트맵 "다음 활동 안내" 메모).
 * 오류 모달(StateDialog)과 달리 선택 제안이라 X·ESC·바깥 클릭으로 닫을 수 있고, 닫으면 홈에 그대로 머문다.
 */
function NextActivityDialog({
  activity,
  onStart,
  onClose,
}: {
  activity: ChildActivity | null
  onStart: (activity: ChildActivity) => void
  onClose: () => void
}) {
  return (
    <DialogPrimitive.Root open={activity !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogPrimitive.Portal>
        {/* Portal은 body 아래에 렌더링되므로 아동 토큰(.child-scope)을 여기서 다시 적용한다. */}
        <div className="child-scope">
          <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/30 backdrop-blur-xs data-[state=open]:animate-in data-[state=open]:fade-in-0" />
          <DialogPrimitive.Content className="fixed top-1/2 left-1/2 z-50 flex w-[min(28rem,calc(100%-2rem))] -translate-x-1/2 -translate-y-1/2 flex-col items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-surface)] px-8 py-10 text-center break-keep shadow-xl outline-none data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95">
            <DialogPrimitive.Close
              aria-label="닫기"
              className="absolute top-4 right-4 flex size-10 items-center justify-center rounded-full text-[var(--child-text-muted)] outline-none hover:bg-[var(--child-surface-muted)] focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50"
            >
              <X className="size-6" />
            </DialogPrimitive.Close>

            <Character name="turtle" size="md" />
            <DialogPrimitive.Title asChild>
              <p className="text-2xl font-bold text-[var(--child-text)]">다음 활동도 해볼까?</p>
            </DialogPrimitive.Title>

            {activity ? (
              <>
                <DialogPrimitive.Description asChild>
                  <div className="flex w-full items-center gap-4 rounded-[var(--child-radius-card)] bg-[var(--child-warning-bg)] p-4 text-left">
                    <div className="flex size-12 shrink-0 items-center justify-center rounded-full bg-[#F0CA50] text-[var(--child-text)]">
                      <Smile className="size-7" />
                    </div>
                    <div className="flex flex-col">
                      <span className="text-lg font-bold text-[var(--child-text)]">{activity.title}</span>
                      <span className="text-sm text-[var(--child-text-muted)]">
                        표정 퀴즈 {activity.quizCount}개 + 역할극 {activity.roleplayCount}개
                      </span>
                    </div>
                  </div>
                </DialogPrimitive.Description>
                <ChildButton className="h-16 w-full text-xl" onClick={() => onStart(activity)}>
                  다음 활동 하기
                </ChildButton>
              </>
            ) : null}
          </DialogPrimitive.Content>
        </div>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

export { NextActivityDialog }
