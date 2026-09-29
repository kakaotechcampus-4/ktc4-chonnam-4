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

export type ChildActivityStatus = "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED"

export type ChildActivity = {
  activityId: string
  title: string
  description: string
  status: ChildActivityStatus
  quizCount: number
  roleplayCount: number
}

/** 세션이 다른 아동의 캐시를 섞어 쓰지 않도록 childId를 키에 포함한다. */
export function myActivitiesQueryKey(childId: string | null) {
  return ["child", childId, "activities"] as const
}

// Figma C-HOME-01 시안과 같은 3개 활동
const MOCK_ACTIVITIES: ChildActivity[] = [
  {
    activityId: "mock-activity-1",
    title: "친구 마음 알아보기",
    description: "넘어진 친구를 보고 어떤 마음일지 함께 생각해볼 거야",
    status: "IN_PROGRESS",
    quizCount: 3,
    roleplayCount: 1,
  },
  {
    activityId: "mock-activity-2",
    title: "교실에서 있었던 일",
    description: "교실에서 친구와 있었던 일을 떠올리며 마음을 표현해볼 거야",
    status: "NOT_STARTED",
    quizCount: 3,
    roleplayCount: 1,
  },
  {
    activityId: "mock-activity-3",
    title: "상대방 마음 맞히기",
    description: "친구의 표정을 보고 어떤 마음인지 맞혀볼 거야",
    status: "COMPLETED",
    quizCount: 3,
    roleplayCount: 1,
  },
]

/** 현재 아동 세션에 배정된 활동만 돌려준다. 실제 API는 세션 쿠키로 아동을 식별한다 (VS-003). */
export async function getMyActivities(): Promise<ChildActivity[]> {
  await wait(MOCK_DELAY_MS)
  return MOCK_ACTIVITIES
}
