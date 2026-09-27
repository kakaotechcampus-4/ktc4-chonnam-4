import { WifiOff } from "lucide-react"
import { StateScreen } from "./StateScreen"
import { ChildButton } from "../ChildButton"

/** 네트워크 연결 끊김. 재전송 시 같은 요청을 다시 보낸다 (VS-011 멱등성 참고). */
function NetworkState({ onRetry }: { onRetry?: () => void }) {
  return (
    <StateScreen
      icon={<WifiOff />}
      tone="danger"
      title="연결이 잠깐 끊겼어요"
      description="인터넷 연결을 확인하고 다시 시도해 주세요."
      action={onRetry ? <ChildButton onClick={onRetry}>다시 시도하기</ChildButton> : undefined}
    />
  )
}

export { NetworkState }
