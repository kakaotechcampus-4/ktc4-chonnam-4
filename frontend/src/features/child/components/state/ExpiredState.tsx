import { Clock } from "lucide-react"
import { StateScreen } from "./StateScreen"

/** 접근 코드 만료. 재발급이 필요하다는 쉬운 안내만 제공한다 (VS-003 참고). */
function ExpiredState() {
  return (
    <StateScreen
      icon={<Clock />}
      tone="warning"
      title="코드 사용 기간이 끝났어요"
      description="선생님께 새 코드를 받아서 다시 들어와 주세요."
    />
  )
}

export { ExpiredState }
