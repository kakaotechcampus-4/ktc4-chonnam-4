import { http, HttpResponse } from "msw"
import type { LearningGoal, Scenario } from "@/features/instructor/api"
import { envelope } from "./handlers"

// 시나리오 화면 테스트용 응답. 가짜 서버의 기본 핸들러에는 시나리오 API 가 없어 테스트마다 server.use 로 붙인다.
// 이름은 지어낸 것이고 실제 아동 정보가 아니다.

export const GOAL: LearningGoal = {
  goalId: "30000000-0000-4000-8000-000000000001",
  childId: "10000000-0000-4000-8000-000000000001",
  instructorId: "00000000-0000-4000-8000-0000000000e1",
  parentGoalId: null,
  title: "친구가 속상할 때 위로하는 말을 한다",
  situationType: null,
  category: "EMPATHY_EXPRESSION",
  characters: [],
  requiredElements: [],
  forbiddenExpressions: ["바보"],
  contentHash: "hash",
  createdAt: "2026-11-01T00:00:00Z",
}

export function scenario(overrides: Partial<Scenario> = {}): Scenario {
  return {
    scenarioId: "80000000-0000-4000-8000-000000000001",
    version: 1,
    editRevision: 0,
    goalId: GOAL.goalId,
    scenarioSource: "REUSED",
    status: "APPROVED",
    scenarioLevel: "L2",
    title: "그림을 망친 친구 위로하기",
    summary: "쉬는 시간에 친구의 그림에 물이 쏟아졌다.",
    scenarioFacts: ["친구의 그림에 물이 쏟아졌다."],
    prohibitedInferences: ["친구가 일부러 망쳤다고 단정하지 않는다."],
    characters: [{ id: "LEARNER", role: "학습자" }, { id: "FRIEND", role: "같은 반 친구" }],
    microGoals: [
      { microGoalId: "81000000-0000-4000-8000-000000000001", description: "친구의 감정을 말한다.", requiredEvidence: ["속상하다고 말함"] },
    ],
    openingPrompts: { S0: "친구는 지금 어떤 기분일까?", S1: "친구가 울먹이고 있어. 어떤 기분일까?", S2: "속상할까, 신날까?" },
    ...overrides,
  }
}

export function goalHandler(goal: LearningGoal = GOAL) {
  return http.get("*/api/v1/learning-goals/:goalId", () => HttpResponse.json(envelope(goal)))
}
