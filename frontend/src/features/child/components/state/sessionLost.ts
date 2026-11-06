import { ChildApiError } from "../../apiClient"

/** 서버에 아동 세션이 없다(401). 다시 보내도 같으므로 다시 하기 대신 코드를 다시 넣게 한다. */
export function isSessionLost(error: unknown) {
  return error instanceof ChildApiError && error.status === 401
}
