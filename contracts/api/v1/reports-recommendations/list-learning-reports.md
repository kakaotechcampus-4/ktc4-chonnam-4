# API-REC-02 · 아동 완료 리포트 목록 조회

> 구현 — 2026-10-31 / `feature/roleplay-report-integration` 작업 브랜치. develop 미반영이며 운영 배포를 의미하지 않습니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| Method | GET |
| Path | `/api/v1/learning-records` |
| 서버 주소 | 환경별 origin. /api/v1 중복 금지 |
| 인증 | 담당 강사 Bearer |
| 성공 | 200 |
| FE 함수 | listLearningReports |
| 구현 | 민서(작업 브랜치), 계약 초안 배재일 |

## 2. 용도·중요 규칙

담당 아동의 완료 리포트를 최근 완료 순으로 돌려준다. 학급 개요의 "최근 학습 요약"과 추천 화면이 아동마다 첫 쪽(size 1~5)을 읽는다.

- 완료본(COMPLETED)만 담는다. 빈 배열도 정상이다.
- `page`는 0부터, `size`는 1~100(기본 20)이다. 범위를 벗어나면 400 `INVALID_REQUEST`다. 응답은 배열이고 전체 개수는 주지 않는다.

## 3. 요청

### Query parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| childId | UUID string | 예 | 담당 아동 |
| page | number | 아니오 | 0부터, 기본 0 |
| size | number | 아니오 | 1~100, 기본 20 |

### Request Body

없음. 빈 JSON 객체를 추가하지 않습니다.

## 4. 성공 응답

HTTP 200.

~~~json
{
  "data": [
    {
      "recordId": "86000000-0000-4000-8000-000000000001",
      "activityId": "40000000-0000-4000-8000-000000000001",
      "childId": "10000000-0000-4000-8000-000000000001",
      "completedAt": "2026-10-31T14:20:00+09:00",
      "summary": "감정 이름은 혼자 말했고, 이유는 두 선택지를 듣고 말했어요.",
      "quiz": {
        "validQuestionCount": 3,
        "correctQuestionCount": 2,
        "overallAccuracy": 0.6666666666666666,
        "resolvedAfterHintCount": 1
      },
      "roleplay": {
        "scenarioLevel": "L2",
        "startSupportLevel": "S1",
        "goals": [
          {
            "microGoalId": "87000000-0000-4000-8000-000000000001",
            "title": "감정 이름 말하기",
            "startSupport": "S1",
            "finalSupport": "S1",
            "result": "REACHED",
            "status": "REACHED_AT_START",
            "criteria": [
              {
                "description": "속상한 감정",
                "status": "EXPLICIT"
              }
            ],
            "observations": [
              "상황 단서를 들은 뒤 속상한 감정을 분명히 말함"
            ]
          },
          {
            "microGoalId": "87000000-0000-4000-8000-000000000002",
            "title": "감정의 이유 말하기",
            "startSupport": "S1",
            "finalSupport": "S2",
            "result": "REACHED",
            "status": "EXTRA_SUPPORT",
            "criteria": [
              {
                "description": "넘어져서 아픈 이유",
                "status": "EXPLICIT"
              }
            ],
            "observations": [
              "상황 단서를 들은 뒤 넘어져서 아픈 이유를 간접적으로 말함",
              "두 선택지를 들은 뒤 넘어져서 아픈 이유를 분명히 말함"
            ]
          }
        ],
        "observations": [
          "상황 단서를 들은 뒤 속상한 감정을 분명히 말함",
          "상황 단서를 들은 뒤 넘어져서 아픈 이유를 간접적으로 말함",
          "두 선택지를 들은 뒤 넘어져서 아픈 이유를 분명히 말함"
        ],
        "effectiveSupport": [
          "두 선택지"
        ],
        "technicalExclusions": []
      },
      "difficultyDecision": {
        "decisionType": "FOLLOW_UP",
        "scenarioLevel": "L2",
        "previousLevel": "L2",
        "nextSupportLevel": "S1",
        "direction": "KEEP",
        "reason": "SCORE_IN_KEEP_RANGE",
        "scoreRate": {
          "numerator": 9,
          "denominator": 12,
          "value": 0.75
        },
        "consecutiveUpgradeCandidates": 0,
        "policyVersion": "FOLLOW_UP_ROLEPLAY_V1"
      },
      "metrics": {
        "roleplayScore": {
          "numerator": 9,
          "denominator": 12,
          "value": 0.75
        },
        "goalAchievement": {
          "numerator": 2,
          "denominator": 2,
          "value": 1.0
        },
        "independentAchievement": {
          "numerator": 1,
          "denominator": 2,
          "value": 0.5
        },
        "quizAccuracy": {
          "numerator": 2,
          "denominator": 3,
          "value": 0.6666666666666666
        },
        "quizHintRecovery": {
          "numerator": 1,
          "denominator": 1,
          "value": 1.0
        },
        "validGoalCount": 2,
        "excludedGoalCount": 0
      },
      "narrative": {
        "observations": [
          {
            "text": "감정 이름은 처음 단서만으로 말했어요.",
            "evidenceIds": [
              "E3"
            ]
          }
        ],
        "effectiveSupports": [
          {
            "text": "두 선택지를 들은 뒤 이유를 분명히 말했어요.",
            "evidenceIds": [
              "E7"
            ]
          }
        ],
        "difficultPoints": [
          {
            "text": "이유는 처음에 일부만 말했어요.",
            "evidenceIds": [
              "E6"
            ]
          }
        ]
      }
    }
  ],
  "meta": {
    "traceId": "90000000-0000-4000-8000-000000000001"
  }
}
~~~

### response schema

`data`는 API-REC-01의 `LearningReportData` 배열이다.

meta.traceId는 필수 문자열입니다. optional(누락)과 null은 다르며 빈 배열도 정상입니다. 중첩 DTO는 아래 TypeScript 정의를 함께 확인합니다.

## 5. 오류·FE 처리

| HTTP | code | 상황·FE 행동 |
|---|---|---|
| 400 | INVALID_REQUEST | UUID·쿼리·헤더 형식 |
| 401 | AUTHENTICATION_REQUIRED | 토큰 없음 |
| 401 | INVALID_TOKEN | 강사 토큰 만료·위조. 입력·토큰 폐기 |
| 403 | ACCESS_DENIED | 강사가 아닌 역할 |
| 404 | CHILD_NOT_FOUND | 없는 아동·남의 아동 |
| 500 | INTERNAL_ERROR | 내부 오류. 원문·예외 전문 비노출 |

남의 아동·기록·추천은 없는 자원과 같은 404로 숨깁니다. 공통 오류 envelope은 [공통 연동 규칙](../integration-guide.md)을 따릅니다.

## 6. 복사 가능한 TypeScript

공통 [api-client.ts](../examples/api-client.ts), [types.ts](../examples/types.ts), [endpoints.ts](../examples/endpoints.ts)를 같은 폴더 구조로 복사합니다. 응답 타입은 types.ts의 리포트·추천 절에 있습니다.

### 호출 함수

~~~ts
import type { ApiClient, CallOptions } from "./api-client.ts";
import { queryString } from "./api-client.ts";
import type * as T from "./types.ts";

export interface ListLearningReportsArgs {
  query: {
    childId: T.UUID;
    page?: number;
    size?: number;
  };
}

export function listLearningReports(
  api: ApiClient,
  args: ListLearningReportsArgs,
  options: CallOptions = {}
): Promise<T.ApiResponse<T.LearningReportData[]>> {
  return api.request<T.LearningReportData[]>("/api/v1/learning-records" + queryString(args.query), {
    method: "GET",
    auth: "instructor",
    expectedStatuses: [200],
    signal: options.signal
  });
}
~~~

## 7. cURL 계약 확인

~~~bash
curl -i -X GET "$NEURINGO_API_ORIGIN/api/v1/learning-records" \
  --cookie ./neuringo.cookies.txt --cookie-jar ./neuringo.cookies.txt \
  -H 'Accept: application/json' \
  -H "Authorization: Bearer $NEURINGO_ACCESS_TOKEN"
~~~

## 8. 연결 Task·검증

- 관련 Task ID: `S7-BAE-01`
- 서버: `backend/src/main/java/com/neuringo/neuringobe/report/controller/LearningReportController.java`. 설계 글은 `backend/docs/roleplay-report-integration.md`입니다.
- 검증: `LearningReportApiIntegrationTest`(완료 기록·소유권·결정·삭제), `OwnershipPatrolIntegrationTest`, `contracts/api-v1.json` 시나리오(백엔드 ApiContractTest·프론트 MSW 계약 테스트).
- [API 목차](../README.md) · [공통 규칙](../integration-guide.md) · [변경 이력](../changelog.md)
