import { useQuery } from "@tanstack/react-query"
import { getMyActivities, myActivitiesQueryKey } from "../api"
import { useChildSessionStore } from "../store/childSessionStore"

/** 지금 입장한 아동의 활동 목록. 인사·내 활동·활동 소개·퀴즈·역할극 화면이 같은 캐시를 나눠 쓴다. */
export function useMyActivities() {
  const childId = useChildSessionStore((state) => state.childId)

  return useQuery({
    queryKey: myActivitiesQueryKey(childId),
    // 가드(RequireChildSession)를 지난 화면에서만 쓰므로 childId 가 있다. 사용 종료 직후 한순간 비는 때는 부르지 않는다.
    queryFn: ({ signal }) => getMyActivities(childId ?? "", signal),
    enabled: childId !== null,
  })
}
