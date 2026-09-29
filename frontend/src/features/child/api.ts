/**
 * 아동 흐름 API.
 * 아동용 백엔드 API(S1-JIN-01 코드 검증 등)가 아직 없어 mock 응답을 돌려준다.
 * 실제 API가 나오면 각 함수 안쪽만 fetch 호출로 바꾸고, 화면 코드는 그대로 둔다.
 */

export type ChildAccess = {
  childId: string
  childName: string
}

export type ChildApiErrorCode = "ACCESS_CODE_INVALID" | "ACCESS_CODE_EXPIRED"

export class ChildApiError extends Error {
  readonly status: number
  readonly code: ChildApiErrorCode

  constructor(status: number, code: ChildApiErrorCode, message: string) {
    super(message)
    this.name = "ChildApiError"
    this.status = status
    this.code = code
  }
}

const MOCK_DELAY_MS = 500

// mock 테스트용 코드: 1234 성공, 9999 만료, 0000 네트워크 오류, 그 외 틀린 코드
const MOCK_VALID_CODE = "1234"
const MOCK_EXPIRED_CODE = "9999"
const MOCK_NETWORK_ERROR_CODE = "0000"

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function verifyAccessCode(code: string): Promise<ChildAccess> {
  await wait(MOCK_DELAY_MS)

  if (code === MOCK_NETWORK_ERROR_CODE) {
    // fetch가 연결 실패 시 던지는 오류와 같은 형태로 흉내 낸다.
    throw new TypeError("Failed to fetch")
  }
  if (code === MOCK_EXPIRED_CODE) {
    throw new ChildApiError(410, "ACCESS_CODE_EXPIRED", "만료된 접근 코드입니다.")
  }
  if (code !== MOCK_VALID_CODE) {
    throw new ChildApiError(401, "ACCESS_CODE_INVALID", "유효하지 않은 접근 코드입니다.")
  }

  return { childId: "mock-child-1", childName: "서연" }
}
