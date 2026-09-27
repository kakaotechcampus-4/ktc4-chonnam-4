import { Loader2 } from "lucide-react"
import { StateScreen } from "./StateScreen"

/** AI 분석/응답 검증 등 처리 대기 상태. 03_MVP_기능_명세 VS-011 참고. */
function LoadingState({ message = "확인하는 중이에요" }: { message?: string }) {
  return (
    <StateScreen
      icon={<Loader2 className="animate-spin" />}
      tone="neutral"
      title={message}
    />
  )
}

export { LoadingState }
