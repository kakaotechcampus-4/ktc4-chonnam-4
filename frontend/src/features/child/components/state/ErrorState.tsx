import { RefreshCw } from "lucide-react"
import { StateScreen } from "./StateScreen"
import { ChildButton } from "../ChildButton"

/** 무음/시간초과/업로드 실패 등 기술 오류. 학습 실패로 취급하지 않는다 (VS-006, VS-010 참고). */
function ErrorState({
  message = "잠깐 문제가 있었어요",
  onRetry,
}: {
  message?: string
  onRetry?: () => void
}) {
  return (
    <StateScreen
      icon={<RefreshCw />}
      tone="danger"
      title={message}
      description="다시 해도 괜찮아요."
      action={onRetry ? <ChildButton onClick={onRetry}>다시 하기</ChildButton> : undefined}
    />
  )
}

export { ErrorState }
