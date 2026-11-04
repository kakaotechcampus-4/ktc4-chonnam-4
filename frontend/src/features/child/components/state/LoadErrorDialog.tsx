import { LogIn } from "lucide-react"
import { ChildApiError, NETWORK_ERROR } from "../../apiClient"
import { useLeaveChildSession } from "../../hooks/useLeaveChildSession"
import { ChildButton } from "../ChildButton"
import { ErrorState } from "./ErrorState"
import { NetworkState } from "./NetworkState"
import { StateDialog } from "./StateDialog"
import { StateScreen } from "./StateScreen"

/**
 * 화면을 채울 자료를 불러오지 못했을 때의 모달. 오류 종류에 따라 안내를 나눈다.
 * - 401: 서버에 아동 세션이 없다(서버 재시작·세션 만료). 다시 해도 같으니 코드를 다시 넣게 한다 (contracts 공통 규칙 "아동401은 재입장 안내").
 * - 서버에 닿지 못함: 연결 끊김 안내
 * - 그 밖: 다시 하기
 */
function LoadErrorDialog({
  open,
  error,
  message,
  onRetry,
}: {
  open: boolean
  error: unknown
  message: string
  onRetry: () => void
}) {
  const leaveSession = useLeaveChildSession()
  const isSessionLost = error instanceof ChildApiError && error.status === 401
  const isNetworkError = error instanceof ChildApiError && error.code === NETWORK_ERROR

  return (
    <StateDialog open={open}>
      {isSessionLost ? (
        <StateScreen
          icon={<LogIn />}
          tone="warning"
          title="다시 들어와 줘"
          description="선생님께 받은 코드를 한 번 더 입력해 주세요."
          action={<ChildButton onClick={leaveSession}>코드 입력하기</ChildButton>}
        />
      ) : isNetworkError ? (
        <NetworkState onRetry={onRetry} />
      ) : (
        <ErrorState message={message} onRetry={onRetry} />
      )}
    </StateDialog>
  )
}

export { LoadErrorDialog }
