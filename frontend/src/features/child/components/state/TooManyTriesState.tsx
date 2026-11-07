import { useState } from "react"
import { Hourglass } from "lucide-react"
import { StateScreen } from "./StateScreen"
import { ChildButton } from "../ChildButton"
import { useCountdown } from "../../hooks/useCountdown"

/** 서버가 기다릴 시간을 알려 주지 않으면 이만큼 기다린다. */
const DEFAULT_WAIT_SECONDS = 60

/**
 * 요청이 너무 많아 잠시 막힌 상태(429). 입장 코드를 여러 번 넣었거나, 같은 반 친구들이 한꺼번에 들어올 때 생긴다.
 * 아이 잘못으로 보이지 않게 쉬어 가자고 안내하고, 기다리는 동안에는 다시 보내지 못하게 버튼을 막는다.
 * 화면에 뜰 때마다 처음부터 센다(모달이 닫히면 내용이 사라진다).
 */
function TooManyTriesState({
  waitSeconds = DEFAULT_WAIT_SECONDS,
  onRetry,
}: {
  waitSeconds?: number
  onRetry?: () => void
}) {
  const [canRetry, setCanRetry] = useState(waitSeconds <= 0)
  const remainingMs = useCountdown(Math.max(0, waitSeconds) * 1000, () => setCanRetry(true))

  return (
    <StateScreen
      icon={<Hourglass />}
      tone="warning"
      title="조금 쉬었다가 다시 해 보자"
      description={
        canRetry ? "이제 다시 해도 괜찮아요." : `${formatWait(remainingMs)} 뒤에 다시 할 수 있어요.`
      }
      action={
        onRetry ? (
          <ChildButton onClick={onRetry} disabled={!canRetry}>
            다시 하기
          </ChildButton>
        ) : undefined
      }
    />
  )
}

/** 1분 안쪽은 초로, 그보다 길면 분으로 올려 보여 준다. */
function formatWait(remainingMs: number) {
  const seconds = Math.max(1, Math.ceil(remainingMs / 1000))
  return seconds < 60 ? `${seconds}초` : `${Math.ceil(seconds / 60)}분`
}

export { TooManyTriesState }
