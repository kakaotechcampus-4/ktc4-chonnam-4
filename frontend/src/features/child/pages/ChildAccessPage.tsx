import { useEffect, useState, type ComponentProps } from "react"
import { useNavigate } from "react-router-dom"
import { useMutation } from "@tanstack/react-query"
import { Delete } from "lucide-react"
import { cn } from "@/lib/utils"
import { ChildButton } from "../components/ChildButton"
import { Character } from "../components/Character"
import { SpeechBubble } from "../components/SpeechBubble"
import { ExpiredState, NetworkState, StateDialog } from "../components/state"
import { ChildApiError, verifyAccessCode } from "../api"
import { useChildSessionStore } from "../store/childSessionStore"

const CODE_LENGTH = 4
const KEYPAD_DIGITS = ["1", "2", "3", "4", "5", "6", "7", "8", "9"]

/**
 * C-ACC-01 코드 입력 화면 (VS-003).
 * 숫자 4자리 코드만 지원한다. QR 입장은 MVP 범위에서 제외됐다.
 */
function ChildAccessPage() {
  const navigate = useNavigate()
  const startSession = useChildSessionStore((state) => state.startSession)
  const [code, setCode] = useState("")

  const verifyMutation = useMutation({
    mutationFn: verifyAccessCode,
    onSuccess: (access) => {
      startSession(access)
      navigate("/child/activities", { replace: true })
    },
    onError: (error) => {
      // 틀린 코드만 비워 다시 누르게 한다. 네트워크 오류는 같은 코드로 재시도한다.
      if (error instanceof ChildApiError && error.code === "ACCESS_CODE_INVALID") {
        setCode("")
      }
    },
  })

  const isPending = verifyMutation.isPending
  const error = verifyMutation.error
  const isInvalid = error instanceof ChildApiError && error.code === "ACCESS_CODE_INVALID"
  const isExpired = error instanceof ChildApiError && error.code === "ACCESS_CODE_EXPIRED"
  const isNetworkError = error !== null && !(error instanceof ChildApiError)
  const canSubmit = code.length === CODE_LENGTH && !isPending

  const pressDigit = (digit: string) => {
    if (isPending) return
    verifyMutation.reset()
    setCode((prev) => (prev.length < CODE_LENGTH ? prev + digit : prev))
  }

  const pressBackspace = () => {
    if (isPending) return
    verifyMutation.reset()
    setCode((prev) => prev.slice(0, -1))
  }

  const submit = () => {
    if (canSubmit) verifyMutation.mutate(code)
  }

  const restart = () => {
    verifyMutation.reset()
    setCode("")
  }

  // PC에서는 실제 키보드의 숫자·Backspace·Enter도 받는다.
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      // 오류 모달이 떠 있는 동안에는 뒤 화면 입력을 받지 않는다.
      if (isExpired || isNetworkError) return
      if (/^[0-9]$/.test(event.key)) pressDigit(event.key)
      else if (event.key === "Backspace") pressBackspace()
      // 포커스된 키패드 버튼에서 Enter를 누르면 버튼 클릭이 따로 처리되므로 입장 요청은 보내지 않는다.
      else if (event.key === "Enter" && !(event.target instanceof HTMLButtonElement)) submit()
    }
    window.addEventListener("keydown", handleKeyDown)
    return () => window.removeEventListener("keydown", handleKeyDown)
  })

  return (
    <div className="child-scope flex min-h-svh items-center justify-center px-6 py-10">
      <div className="grid w-full max-w-4xl items-center gap-10 md:grid-cols-[1fr_1.3fr]">
        <section className="flex flex-col items-center gap-4 text-center">
          <Character name="turtle" size="lg" />
          <div className="flex flex-col gap-1">
            <p className="text-2xl font-bold text-[var(--child-text)]">
              입장 코드를 입력해줘!
            </p>
            <p className="text-lg text-[var(--child-text-muted)]">
              선생님이 알려준 숫자를 눌러봐
            </p>
          </div>

          <div className="flex gap-3" aria-label={`입력한 숫자 ${code.length}개`}>
            {Array.from({ length: CODE_LENGTH }, (_, index) => {
              const digit = code[index]
              const isCurrent = index === code.length
              return (
                <div
                  key={index}
                  className={cn(
                    "flex size-14 items-center justify-center rounded-2xl border-2 text-2xl font-bold",
                    digit
                      ? "border-[var(--child-primary)] bg-[var(--child-surface)] text-[var(--child-text)]"
                      : isCurrent
                        ? "border-[var(--child-primary)] border-dashed bg-[var(--child-surface)]"
                        : "border-[var(--child-border)] border-dashed bg-[var(--child-surface-muted)]"
                  )}
                >
                  {digit ?? ""}
                </div>
              )
            })}
          </div>

          {/* 안내가 나타나고 사라질 때 화면이 흔들리지 않도록 높이를 미리 잡아 둔다. */}
          <div className="flex min-h-20 items-center" aria-live="polite">
            {isInvalid ? (
              <SpeechBubble character="rabbit">
                코드가 맞지 않아요. 다시 눌러볼까?
              </SpeechBubble>
            ) : null}
          </div>
        </section>

        <section className="flex flex-col gap-4">
          <div className="grid grid-cols-3 gap-3">
            {KEYPAD_DIGITS.map((digit) => (
              <KeypadButton key={digit} onClick={() => pressDigit(digit)} disabled={isPending}>
                {digit}
              </KeypadButton>
            ))}
            <KeypadButton onClick={pressBackspace} disabled={isPending} aria-label="지우기">
              <Delete className="size-7" />
            </KeypadButton>
            <KeypadButton onClick={() => pressDigit("0")} disabled={isPending}>
              0
            </KeypadButton>
          </div>

          <ChildButton className="h-16 w-full text-xl" onClick={submit} disabled={!canSubmit}>
            {isPending ? "확인하고 있어요…" : "입장하기"}
          </ChildButton>
        </section>
      </div>

      <StateDialog open={isExpired}>
        <ExpiredState onRetry={restart} />
      </StateDialog>
      <StateDialog open={isNetworkError}>
        <NetworkState onRetry={() => verifyMutation.mutate(code)} />
      </StateDialog>
    </div>
  )
}

function KeypadButton({ className, ...props }: ComponentProps<"button">) {
  return (
    <button
      type="button"
      className={cn(
        "flex h-16 items-center justify-center rounded-2xl bg-[var(--child-surface)] text-2xl font-bold text-[var(--child-text)] shadow-sm transition-transform outline-none select-none active:scale-95 focus-visible:ring-3 focus-visible:ring-[var(--child-primary)]/50 disabled:opacity-50",
        className
      )}
      {...props}
    />
  )
}

export { ChildAccessPage }
