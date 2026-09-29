import * as React from "react"
import { Dialog as DialogPrimitive } from "radix-ui"
import { StateDialogContext } from "./stateDialogContext"

/**
 * 오류·권한·만료·네트워크 상태 카드를 화면 위에 띄우는 모달 틀.
 * 아동이 실수로 안내를 닫고 멈춘 화면에 남지 않도록 X 버튼·ESC·바깥 클릭으로는 닫지 않고,
 * 카드 안의 버튼(다시 하기 등)으로만 벗어나게 한다. 로딩은 모달로 띄우지 않는다.
 */
function StateDialog({ open, children }: { open: boolean; children: React.ReactNode }) {
  const preventClose = (event: Event) => event.preventDefault()

  return (
    <DialogPrimitive.Root open={open}>
      <DialogPrimitive.Portal>
        {/* Portal은 body 아래에 렌더링되므로 아동 토큰(.child-scope)을 여기서 다시 적용한다. */}
        <div className="child-scope">
          <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/30 backdrop-blur-xs data-[state=open]:animate-in data-[state=open]:fade-in-0" />
          <DialogPrimitive.Content
            onEscapeKeyDown={preventClose}
            onPointerDownOutside={preventClose}
            onInteractOutside={preventClose}
            className="fixed top-1/2 left-1/2 z-50 w-[min(28rem,calc(100%-2rem))] -translate-x-1/2 -translate-y-1/2 outline-none data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95"
          >
            <StateDialogContext.Provider value={true}>{children}</StateDialogContext.Provider>
          </DialogPrimitive.Content>
        </div>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

export { StateDialog }
