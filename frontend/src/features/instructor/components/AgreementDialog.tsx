import { Dialog } from 'radix-ui'
import type { AgreementDocument } from '../content/agreements'
import { outlineButtonClass } from './styles'

/**
 * 가입 화면의 약관 본문 보기 창. 본문이 길어 창 안에서만 스크롤하고, 닫기 버튼으로 가입 화면에 돌아간다.
 * 모양은 DeleteConfirmDialog 와 같은 디자인 시스템 05 Dialog 토큰을 쓴다.
 */
function AgreementDialog({
  document,
  onOpenChange,
}: {
  document: AgreementDocument | null
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog.Root open={document !== null} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        {/* Portal 은 body 아래에 렌더링되므로 강사 토큰(.instructor-scope)을 여기서 다시 적용한다. */}
        <div className="instructor-scope">
          <Dialog.Overlay className="fixed inset-0 z-50 bg-black/30" />
          {document && (
            <Dialog.Content className="fixed top-1/2 left-1/2 z-50 flex max-h-[min(40rem,calc(100%-2rem))] w-[min(36rem,calc(100%-2rem))] -translate-x-1/2 -translate-y-1/2 flex-col gap-3 rounded-[var(--instructor-radius-dialog)] border border-[var(--instructor-border)] bg-[var(--instructor-surface)] p-5 text-[var(--instructor-text)] shadow-[var(--instructor-shadow-2)] outline-none">
              <Dialog.Title className="text-xl font-semibold">{document.title}</Dialog.Title>
              <Dialog.Description className="text-xs text-[var(--instructor-text-muted)]">
                버전 {document.version} · 시연용 초안
              </Dialog.Description>
              <div className="flex min-h-0 flex-col gap-4 overflow-y-auto pr-1 text-sm leading-6">
                {document.sections.map((section) => (
                  <section key={section.heading} className="flex flex-col gap-1">
                    <h3 className="font-semibold">{section.heading}</h3>
                    {section.body.map((paragraph) => (
                      <p key={paragraph} className="text-[var(--instructor-text-muted)]">
                        {paragraph}
                      </p>
                    ))}
                  </section>
                ))}
              </div>
              <div className="flex justify-end pt-2">
                <Dialog.Close className={outlineButtonClass}>닫기</Dialog.Close>
              </div>
            </Dialog.Content>
          )}
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

export { AgreementDialog }
