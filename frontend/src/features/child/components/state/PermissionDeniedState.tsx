import { CameraOff, MicOff } from "lucide-react"
import { StateScreen } from "./StateScreen"
import { ChildButton } from "../ChildButton"

/** 카메라·마이크 권한 거부. 권한 거부 상태에서도 활동을 이어갈 수 있어야 한다 (VS-006, VS-010 참고). */
function PermissionDeniedState({
  device,
  onRetry,
}: {
  device: "camera" | "microphone"
  onRetry?: () => void
}) {
  const isCamera = device === "camera"
  return (
    <StateScreen
      icon={isCamera ? <CameraOff /> : <MicOff />}
      tone="warning"
      title={isCamera ? "카메라를 사용할 수 없어요" : "마이크를 사용할 수 없어요"}
      description="브라우저 설정에서 권한을 허용하면 다시 시도할 수 있어요."
      action={onRetry ? <ChildButton onClick={onRetry}>다시 확인하기</ChildButton> : undefined}
    />
  )
}

export { PermissionDeniedState }
