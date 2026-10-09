import { useState, type ReactNode } from 'react'
import { AlertDialog } from 'radix-ui'
import { dangerButtonClass, errorTextClass, inputClass, outlineButtonClass } from './styles'

/**
 * 영구 삭제 확인 창 (ADR 2026-10-04 D4). 무엇이 함께 지워지는지 description 으로 알린다.
 * confirmText 를 주면 그 이름을 똑같이 입력해야 삭제 버튼이 켜진다. 피해가 큰 학급 삭제에만 쓴다.
 * 삭제 중에는 닫히지 않고, 실패하면 창 안에 오류를 보인다.
 */
function DeleteConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmText,
  isPending,
  error,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: ReactNode
  confirmText?: string
  isPending: boolean
  error: string | null
  onConfirm: () => void
}) {
  const [typed, setTyped] = useState('')
  const isConfirmed = confirmText === undefined || typed === confirmText

  function handleOpenChange(next: boolean) {
    if (isPending) return
    if (!next) setTyped('')
    onOpenChange(next)
  }

  return (
    <AlertDialog.Root open={open} onOpenChange={handleOpenChange}>
      <AlertDialog.Portal>
        {/* Portal 은 body 아래에 렌더링되므로 강사 토큰(.instructor-scope)을 여기서 다시 적용한다. */}
        <div className="instructor-scope">
          <AlertDialog.Overlay className="fixed inset-0 z-50 bg-black/30" />
          <AlertDialog.Content className="fixed top-1/2 left-1/2 z-50 flex w-[min(28rem,calc(100%-2rem))] -translate-x-1/2 -translate-y-1/2 flex-col gap-4 rounded-[var(--instructor-radius-card)] bg-[var(--instructor-surface)] p-6 text-[var(--instructor-text)] shadow-xl outline-none">
            <AlertDialog.Title className="text-lg font-bold">{title}</AlertDialog.Title>
            <AlertDialog.Description asChild>
              <div className="flex flex-col gap-2 text-sm text-[var(--instructor-text-muted)]">{description}</div>
            </AlertDialog.Description>

            {confirmText !== undefined && (
              <label className="flex flex-col gap-2 text-sm">
                <span>
                  확인을 위해 <strong className="text-[var(--instructor-text)]">{confirmText}</strong> 을(를) 입력해 주세요.
                </span>
                <input
                  value={typed}
                  onChange={(e) => setTyped(e.target.value)}
                  disabled={isPending}
                  autoComplete="off"
                  className={inputClass}
                />
              </label>
            )}

            {error && (
              <p role="alert" className={errorTextClass}>
                {error}
              </p>
            )}

            <div className="flex justify-end gap-2">
              <AlertDialog.Cancel className={outlineButtonClass} disabled={isPending}>
                취소
              </AlertDialog.Cancel>
              {/* Action 은 누르면 창을 닫아 버려 삭제 결과를 기다릴 수 없다. 일반 버튼으로 두고 성공했을 때 화면이 닫는다. */}
              <button
                type="button"
                className={dangerButtonClass}
                disabled={!isConfirmed || isPending}
                onClick={onConfirm}
              >
                {isPending ? '삭제 중...' : '영구 삭제'}
              </button>
            </div>
          </AlertDialog.Content>
        </div>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}

export { DeleteConfirmDialog }
