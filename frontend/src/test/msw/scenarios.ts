import type { ReviewScenario, ScenarioReview } from "@/features/instructor/api"

// 시나리오 화면 테스트용 응답. 가짜 서버의 기본 핸들러에는 시나리오 API 가 없어 테스트마다 server.use 로 붙인다.
// 이름은 지어낸 것이고 실제 아동 정보가 아니다.

export const ACTIVITY_ID = "40000000-0000-4000-8000-000000000001"
export const REVIEW_ID = "70000000-0000-4000-8000-000000000001"

const GOAL_1 = "81000000-0000-4000-8000-000000000001"
const GOAL_2 = "81000000-0000-4000-8000-000000000002"

export function reviewScenario(overrides: Partial<ReviewScenario> = {}): ReviewScenario {
  return {
    scenarioId: "80000000-0000-4000-8000-000000000001",
    scenarioVersion: 1,
    scenarioLevel: "L2",
    title: "그림을 망친 친구 위로하기",
    summary: "쉬는 시간에 친구의 그림에 물이 쏟아졌다.",
    characters: [
      { id: "LEARNER", role: "학습자" },
      { id: "FRIEND", role: "같은 반 친구" },
    ],
    scenarioFacts: ["친구의 그림에 물이 쏟아졌다."],
    microGoals: [
      {
        microGoalId: GOAL_1,
        description: "친구의 감정을 말한다.",
        requiredEvidence: ["속상하다고 말함"],
        achievementExample: "속상하겠다.",
      },
      {
        microGoalId: GOAL_2,
        description: "위로하는 말을 한다.",
        requiredEvidence: ["괜찮냐고 물어봄"],
        achievementExample: "괜찮아?",
      },
    ],
    startingQuestions: {
      S0: "친구는 지금 어떤 기분일까?",
      S1: "친구가 울먹이고 있어. 어떤 기분일까?",
      S2: "속상할까, 신날까?",
    },
    startingQuestionGoalIds: { S0: GOAL_1, S1: GOAL_1, S2: GOAL_2 },
    ...overrides,
  }
}

export function scenarioReview(overrides: Partial<ScenarioReview> = {}): ScenarioReview {
  return {
    reviewId: REVIEW_ID,
    activityId: ACTIVITY_ID,
    revision: 1,
    conditions: {
      title: "친구가 속상할 때 위로하는 말을 한다",
      category: "EMPATHY_EXPRESSION",
      situationType: null,
      characters: [],
      requiredElements: [],
      forbiddenExpressions: ["바보"],
      scenarioLevel: "L2",
    },
    scenario: reviewScenario(),
    ...overrides,
  }
}
