# 시나리오 생성 프롬프트 v1 (S4-LEE-01)

강사의 학습 목표·조건과 결정된 난이도로 역할극 시나리오 하나를 만드는 프롬프트와, 그 출력의 계약 검사·품질 평가를 정리한다. 기준은 [기능 요구사항 v3](https://app.notion.com/p/2d16a39bbc0682ce99b481e61f9fafc2) 11장(SCN-01·02·08·10)과 통합 워크플로우 7장이다.

## 범위

| 포함 (이 작업) | 포함하지 않음 (담당) |
| --- | --- |
| 생성 프롬프트 `prompts/scenario/v1/scenario-generation.md` | 시나리오 DB·승인 화면·CRUD (배재일) |
| 입력 `ScenarioGenerationInput`, 출력 `GeneratedScenario` | 생성 호출·재시도 한도·실패 시 기본 시나리오 제시 (진미나) |
| 출력 계약 검사 `ScenarioOutputParser` (필수값·개수·길이·금지 표현·위험 낱말·번호 패턴) | 안전 검사 실행 모듈 `SAFE`, 최종 위험 낱말 목록 (진미나·정민서) |
| 승인 시나리오 → 역할극 입력 변환 `ScenarioPromptFactory.toRoleplayScenario` | 재사용 조회(학급+목표+조건+난이도) (진미나) |
| 품질 평가 세트와 `./gradlew scenarioEval` | |

## 사용법

```java
ScenarioPromptFactory factory = new ScenarioPromptFactory(objectMapper, policyVersion);
PreparedPrompt<GeneratedScenario> prompt = factory.generation(trace, attempts, attempt, input);
AiCallResult<GeneratedScenario> result = structuredLlmExecutor.execute(prompt.request(), prompt.parser());
```

- `trace`에는 `classId`가 필요하다(`LlmRequest`의 기존 계약).
- 계약 검사에 걸리면 `INVALID_OUTPUT_FORMAT`이 돌아온다. 실패 이유는 `InvalidLlmOutputException` 메시지 앞부분의 코드(`ScenarioFailure`)다. 다시 만들 때 `Revision(null, List.of(), failureCodes)`로 넘긴다.
- 강사가 수정을 요청하면 `Revision(previousScenario, teacherRequests, List.of())`로 넘긴다.
- 승인된 시나리오는 `toRoleplayScenario(scenario, learningGoal, microGoalIds)`로 역할극 한 턴의 입력(`RoleplayTurnInput.Scenario`)이 된다. `MG-01` 같은 모델 키를 저장된 UUID로 바꾼다.

## 출력

```json
{
  "title": "넘어져서 우는 친구",
  "summary": "놀이터에서 친구가 미끄럼틀 앞에서 넘어져 울었다.",
  "scenario_level": "L1",
  "ai_role": "NARRATOR",
  "characters": [{"id": "LEARNER", "role": "학습자"}, {"id": "FRIEND", "role": "넘어진 친구"}],
  "scenario_facts": ["친구가 미끄럼틀로 뛰어갔다.", "친구가 넘어졌다.", "친구가 울었다."],
  "prohibited_inferences": ["친구가 일부러 넘어졌다고 단정하지 않는다."],
  "micro_goals": [
    {"id": "MG-01", "description": "친구가 넘어졌다는 사건을 파악한다.",
     "required_evidence": ["넘어짐 또는 다침을 말한다."], "achievement_example": "친구가 넘어졌어."}
  ],
  "starting_questions": {"S0": "...?", "S1": "...?", "S2": "...?"}
}
```

워크플로우 7.2의 예시에서 바뀐 점:

- `achievement_response_examples`(시나리오 전체에 하나)를 마이크로 목표마다 `achievement_example`로 옮겼다. 목표별로 무엇이 달성인지 강사가 보기 쉽고, 품질 평가에도 쓰기 좋다.
- `opening_prompt` 하나 대신 `starting_questions`(S0·S1·S2)를 만든다(SCN-10). 같은 시나리오를 초기 지원 수준이 다른 아동이 재사용하기 때문이다.
- `ai_role`을 더했다. 아래 "결정한 것" 참고.
- `initial_support_level`은 넣지 않는다. 시나리오가 아니라 아동별 활동·세션 속성이다(SCN-10).

## 결정한 것

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| 시작 질문 수준 | **S0·S1·S2 세 개.** S3는 만들지 않는다 | S3는 "정답을 알려 주고 질문하지 않는" 단계라 시작 질문이 될 수 없다. 워크플로우 6.1도 초기 지원 수준을 S0~S2로 정한다. 요구사항 SCN-10의 "S0~S3"과 다르므로 팀 확인이 필요하다 |
| AI 말투 | **3인칭 안내자(`ai_role: NARRATOR`)** 가 기본 | 워크플로우·요구사항 예시("친구가 넘어져서 울고 있어")와 같다. 캐릭터 1인칭으로 바꿀 수 있게 `ai_role`에 등장인물 id도 허용한다 |
| 난이도별 규모 | L1 사실 3~4·목표 3 / L2 사실 4~5·목표 3~4 / L3 사실 5~6·목표 4 | 요구사항 10장의 L1~L3 설명(단일 사건 → 원인 추론 → 복합 감정·해결 행동)에 맞췄다. MVP 테스트로 바꿀 수 있다(LVL-08) |
| 분류별 마지막 목표 | 공감 표현이면 친구에게 해 줄 말, 상황 대처면 해결 행동 | 학습 목표 분류(category)가 마지막 목표를 정한다 |

## 계약 검사

출력이 아래 중 하나라도 어기면 형식 실패다. 메시지는 `코드: 칸`이고 모델 출력은 넣지 않는다.

| 코드 | 검사 |
| --- | --- |
| `LEVEL_MISMATCH` | 요청한 난이도와 다름 |
| `CHARACTERS_INVALID` | 첫 등장인물이 `LEARNER`가 아님, 2명 미만, id 형식(영문 대문자·밑줄)·중복 |
| `AI_ROLE_INVALID` | `NARRATOR`도 아니고 등장인물 id도 아님(`LEARNER`는 안 됨) |
| `FACT_COUNT_OUT_OF_RANGE`, `DUPLICATE_FACT` | 난이도별 사실 개수, 같은 사실 반복 |
| `PROHIBITED_INFERENCE_COUNT_OUT_OF_RANGE` | 금지 추론 1~3개 |
| `MICRO_GOAL_COUNT_OUT_OF_RANGE`, `MICRO_GOAL_ID_INVALID`, `EVIDENCE_COUNT_OUT_OF_RANGE` | 난이도별 목표 개수, `MG-01`부터 순서대로, 필수 증거 1~3개 |
| `STARTING_QUESTIONS_INVALID`, `QUESTION_FORMAT_INVALID` | 시작 질문은 정확히 S0·S1·S2, 각각 물음표 하나로 끝남 |
| `TOO_LONG` | 제목 30자, 요약 120자, 문장 60자, 시작 질문 80자 (프롬프트는 이보다 짧게 요구한다) |
| `FORBIDDEN_EXPRESSION_USED` | 강사 금지 표현이 들어감(띄어쓰기를 무시하고 찾는다) |
| `UNSAFE_CONTENT` | 폭력·죽음·위험 행동·진단 낱말(`UNSAFE_TERMS`). 강사가 막지 않아도 늘 막는다 |
| `PERSONAL_INFORMATION` | 전화번호 형태, 6자리 이상 숫자 |

금지 표현·위험 낱말은 `prohibited_inferences`에서는 찾지 않는다. 금지 추론은 "누가 때렸다고 지어내지 않는다"처럼 하면 안 되는 일을 적는 칸이라, 그 낱말이 들어가는 게 정상이다. 번호 패턴은 모든 칸에서 찾는다.

`UNSAFE_TERMS`는 초안이다. 낱말 일부로 다른 말이 걸리지 않게 활용형으로 적었다(예: "장애물"은 걸리지 않게 "장애가·장애를·장애인"). 최종 목록은 안전 정책(SAFE) 담당과 정한다.

## 품질 평가

```bash
cd backend
./gradlew scenarioEval '--args=--run-id smoke'                               # 키 없이 실행기 확인
./gradlew scenarioEval '--args=--provider openai --repeats 3 --run-id scn-r3' # 실제 모델 (.env.local 의 AI_*)
```

- 평가 세트 `backend/src/test/resources/eval/scenario-generation-v1.json`: 강사 입력 9개(L1 5·L2 2·L3 2, 분류 3종, 최소 입력, 지시문 주입).
- 강사 입력마다 최대 3번 만든다. 계약 검사에 걸리면 실패 코드를 붙여 다시 만든다. 이 한도는 측정용 기본안이고, 운영 한도는 오케스트레이터가 정한다.
- 결과(`build/eval/<run-id>/`)
  - `summary.md`: 최종 통과율, 첫 시도 통과율, 계약 검사 실패 이유, 지연·토큰·비용, 내용 기준 일치
  - `scenarios.md`: 생성된 시나리오를 강사 검토 화면처럼 읽기 좋게 모은 것. **교육적 적절성은 사람이 이걸 보고 판단한다**
  - `format-failures.jsonl`: 실패한 출력 원문과 이유
- 내용 기준(`expect`)은 낱말 포함 여부로 보는 거친 기준이다. 상황·필수 요소가 반영됐는지, 지시문 주입을 따르지 않았는지, 분류별 마지막 목표가 맞는지를 본다.
- 시나리오 생성은 턴 예산이 없는 호출이다. `AI_TIMEOUT`이 그대로 제한 시간이고, 출력이 길어서 역할극 한 턴보다 오래 걸린다. 결과의 "제한 시간 제안"을 본다.

## 열린 질문

1. 시작 질문 S0~S2(이 문서) vs S0~S3(요구사항 SCN-10). 팀 확인
2. AI 말투: 안내자(기본) vs 캐릭터 1인칭. 역할극 회귀 세트 대본은 1인칭이라 서로 다르다. 역할극 응답 생성 프롬프트에도 같은 결정이 필요하다
3. `UNSAFE_TERMS` 최종 목록(SAFE 담당)
4. 시나리오 생성 호출의 제한 시간. `AI_TIMEOUT`(역할극 권장 8s)을 같이 쓰면 시나리오 생성이 잘린다. 호출별로 다른 제한 시간이 필요하다
