# 느링고 API v1 명세

> 계약 갱신: 2026-10-22 · develop [`63e4314`](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/tree/63e43144c92449ec5670390a6b99d20d2b2580a5) (2026-10-16 병합 커밋)
> 이 폴더의 Markdown을 GitHub에서 API 계약 원본으로 관리합니다. `v1/` 폴더만 복사/업로드해도 목차·예제·검증 링크가 연결됩니다.

## 현재 제공 범위

57개 API ID 중 **구현28개 · 조건부 구현1개 · 설계26개 · 미제공2개**입니다. 현재 코드의 존재/형식과 배포 활성화·제품 인수 완료는 구분합니다.

| 상태 | 의미 | 예제 호출 |
|---|---|---|
| 구현 | 기준 커밋에 Controller/DTO가 존재 | endpoints.ts. 환경별 주소·쿠키/권한 확인 필요 |
| 조건부 구현 | 코드가 있지만 서버 기능 설정·필수 운영 연결 필요 | endpoints.ts의 음성 입력. allowConditional 기본 차단 |
| 설계 | 공개 Controller 없음. 경로/필드/상태는 제안 | draft-endpoints.ts, allowDrafts 기본 차단 |
| 미제공 | 과거 공개 문항 풀 API가 제거됨. ID만 추적용 유지 | 함수 없음. 호출하지 않음 |

전체 경로는 `/api/v1`입니다. 문서에 적힌 서버 origin/비밀값 예시는 실제 배포 주소가 아닙니다. 배포 담당자에게 환경별 값을 받아 사용합니다.

## 문서와 파일 규칙

- 저장소의 `docs/` 문서에 맞춰 디렉터리와 파일명은 영문 소문자 kebab-case를 사용합니다. 색인 파일은 저장소와 동일하게 `README.md`로 둡니다.
- 도메인별 디렉터리에 `create-activity.md`, `submit-voice-input.md`처럼 호출 동작을 나타내는 파일을 둡니다. API ID(`API-ACT-01` 등)는 문서 제목과 목차에 유지하며, 제거된 ID를 다른 기능에 재사용하지 않습니다.
- API별 문서는 `Endpoint → 용도·규칙 → 요청 → 성공 응답 → 오류·FE 처리 → TypeScript → cURL → 연결 Task·검증` 순서입니다.
- 경로에는 공백·한글·밑줄·정렬용 숫자 접두사를 사용하지 않습니다. 문서 제목과 설명은 한국어를 유지합니다.
- 공통 규칙은 `integration-guide.md`, 변경 이력은 `changelog.md`, 호출·검증 예제는 `examples/`에 둡니다. 저장소에 배치할 때는 기존 문서 위치 아래 `docs/api/v1/`을 권장합니다. 폴더 내부 상대 링크는 그대로 동작합니다.
- 이 README는 색인이고 개별 MD가 필드·권한·상태의 계약 원본입니다. `types.ts`·함수·fixtures·검사는 같은 PR에서 동기화하는 연동 예제입니다.
- Task는 ID로만 참조합니다. 이 폴더에 없는 개인 경로·Task 폴더·압축 파일은 문서 의존성이 아닙니다.

## FE 연동 시작

1. [공통 연동 규칙](./integration-guide.md)에서 origin·인증·CSRF·오류·재전송을 확인합니다.
2. [api-client.ts](./examples/api-client.ts), [types.ts](./examples/types.ts), [endpoints.ts](./examples/endpoints.ts), [draft-endpoints.ts](./examples/draft-endpoints.ts)를 같은 폴더로 복사합니다. 실제 FE 소스가 자동 변경된 것은 아닙니다.
3. 구현된 경로만 실제 서버에 연결합니다. 조건부 음성과 설계 API는 별도 차단을 유지합니다. 미제공 문항 풀은 호출하지 않습니다.
4. [변경 이력](./changelog.md)의 호환성 변경을 확인합니다. 특히 회원가입 동의·POST 탈퇴·inline goal 활동 생성·응답 중첩을 반영해야 합니다.

## 전체 API 목록

### 인증·입장

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-IAM-01 · CSRF 토큰 조회](./auth/get-csrf.md) | GET | `/api/v1/csrf` | 구현 | getCsrf |
| [API-IAM-02 · 강사 회원가입](./auth/signup.md) | POST | `/api/v1/users` | 구현 | signup |
| [API-IAM-03 · 강사 로그인](./auth/login.md) | POST | `/api/v1/auth/sessions` | 구현 | login |
| [API-IAM-04 · 강사 현재 세션 조회](./auth/get-instructor-session.md) | GET | `/api/v1/auth/session` | 구현 | getInstructorSession |
| [API-IAM-05 · 강사 로그아웃](./auth/logout-instructor.md) | DELETE | `/api/v1/auth/session` | 구현 | logoutInstructor |
| [API-IAM-06 · 강사 비밀번호 확인·즉시 탈퇴](./auth/withdraw-instructor.md) | POST | `/api/v1/users/me/withdrawal` | 구현 | withdrawInstructor |
| [API-IAM-07 · 아동 코드 발급·재발급](./auth/issue-child-code.md) | POST | `/api/v1/children/{childId}/access-codes` | 구현 | issueChildCode |
| [API-IAM-08 · 아동 코드 입장](./auth/enter-child.md) | POST | `/api/v1/child-access-sessions` | 구현 | enterChild |
| [API-IAM-09 · 아동 사용 종료](./auth/exit-child.md) | DELETE | `/api/v1/child-access-sessions/current` | 구현 | exitChild |
| [API-IAM-10 · 아동 현재 세션 확인](./auth/get-child-session.md) | GET | `/api/v1/child-access-sessions/current` | 설계 | getChildSession |
### 학급·아동

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-CLS-01 · 학급 생성](./classrooms-children/create-classroom.md) | POST | `/api/v1/classrooms` | 구현 | createClassroom |
| [API-CLS-02 · 담당 학급 목록 조회](./classrooms-children/list-classrooms.md) | GET | `/api/v1/classrooms` | 구현 | listClassrooms |
| [API-CLS-03 · 학급 상세 조회](./classrooms-children/get-classroom.md) | GET | `/api/v1/classrooms/{classId}` | 구현 | getClassroom |
| [API-CLS-04 · 학급 이름 수정](./classrooms-children/update-classroom.md) | PATCH | `/api/v1/classrooms/{classId}` | 설계 | updateClassroom |
| [API-CLS-05 · 학급 즉시 영구 삭제](./classrooms-children/delete-classroom.md) | DELETE | `/api/v1/classrooms/{classId}` | 구현 | deleteClassroom |
| [API-CLS-06 · 아동 실명 등록](./classrooms-children/create-child.md) | POST | `/api/v1/classrooms/{classId}/children` | 구현 | createChild |
| [API-CLS-07 · 학급 아동 목록 조회](./classrooms-children/list-children.md) | GET | `/api/v1/classrooms/{classId}/children` | 구현 | listChildren |
| [API-CLS-08 · 아동 상세 조회](./classrooms-children/get-child.md) | GET | `/api/v1/children/{childId}` | 구현 | getChild |
| [API-CLS-09 · 아동 실명 수정](./classrooms-children/update-child.md) | PATCH | `/api/v1/children/{childId}` | 설계 | updateChild |
| [API-CLS-10 · 아동 즉시 영구 삭제](./classrooms-children/delete-child.md) | DELETE | `/api/v1/children/{childId}` | 구현 | deleteChild |
### 학습·목표

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-GOAL-01 · 아동 학습 목표 생성](./learning-goals/create-learning-goal.md) | POST | `/api/v1/children/{childId}/learning-goals` | 구현 | createLearningGoal |
| [API-GOAL-02 · 아동 목표 목록 조회](./learning-goals/list-learning-goals.md) | GET | `/api/v1/children/{childId}/learning-goals` | 구현 | listLearningGoals |
| [API-GOAL-03 · 학습 목표 상세 조회](./learning-goals/get-learning-goal.md) | GET | `/api/v1/learning-goals/{goalId}` | 구현 | getLearningGoal |
| [API-GOAL-04 · 학습 목표 부분 수정](./learning-goals/update-learning-goal.md) | PATCH | `/api/v1/learning-goals/{goalId}` | 설계 | updateLearningGoal |
| [API-GOAL-05 · 목표 계보 조회](./learning-goals/get-learning-goal-lineage.md) | GET | `/api/v1/learning-goals/{goalId}/lineage` | 설계 | getLearningGoalLineage |
### 활동

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-ACT-01 · 활동·목표 원자 생성 및 문항 배정](./activities/create-activity.md) | POST | `/api/v1/activities` | 구현 | createActivity |
| [API-ACT-02 · 아동 배정 활동 목록 조회](./activities/list-child-activities.md) | GET | `/api/v1/children/{childId}/activities` | 구현 | listChildActivities |
| [API-ACT-03 · 활동 상세 조회](./activities/get-activity.md) | GET | `/api/v1/activities/{activityId}` | 구현 | getActivity |
| [API-ACT-04 · 활동 완료 status 전용 조회](./activities/get-activity-status.md) | GET | `/api/v1/activities/{activityId}/status` | 설계 | getActivityStatus |
### 표정·퀴즈

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-QUIZ-01 · 검증 문항 풀 목록 조회](./quizzes/list-quiz-items.md) | GET | `/api/v1/quiz-items` | 미제공 | 없음 |
| [API-QUIZ-02 · 문항 풀 상세 조회](./quizzes/get-quiz-item.md) | GET | `/api/v1/quiz-items/{itemId}` | 미제공 | 없음 |
| [API-QUIZ-03 · 아동 배정 퀴즈 문항 조회](./quizzes/list-assigned-quiz-items.md) | GET | `/api/v1/activities/{activityId}/quiz-items` | 구현 | listAssignedQuizItems |
| [API-QUIZ-04 · 퀴즈 최초·최종 답안 제출](./quizzes/put-quiz-attempt.md) | PUT | `/api/v1/activity-quiz-items/{activityQuizId}/attempt` | 구현 | putQuizAttempt |
| [API-QUIZ-05 · 퀴즈 제출 답안 조회](./quizzes/get-quiz-attempt.md) | GET | `/api/v1/activity-quiz-items/{activityQuizId}/attempt` | 구현 | getQuizAttempt |
| [API-QUIZ-06 · 퀴즈 힌트 요청](./quizzes/issue-quiz-hint.md) | POST | `/api/v1/activity-quiz-items/{activityQuizId}/hints` | 구현 | issueQuizHint |
| [API-QUIZ-07 · 퀴즈 결과·최초 난이도 조회](./quizzes/get-quiz-result.md) | GET | `/api/v1/activities/{activityId}/quiz-result` | 구현 | getQuizResult |
### 시나리오

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-SCN-01 · 시나리오 재사용 우선 준비·생성](./scenarios/prepare-scenario.md) | POST | `/api/v1/scenarios` | 설계 | prepareScenario |
| [API-SCN-02 · 강사용 시나리오 목록 조회](./scenarios/list-scenarios.md) | GET | `/api/v1/scenarios` | 설계 | listScenarios |
| [API-SCN-03 · 시나리오 검토 상세 조회](./scenarios/get-scenario.md) | GET | `/api/v1/scenarios/{scenarioId}` | 설계 | getScenario |
| [API-SCN-04 · 시나리오 초안 수정·승인 검토](./scenarios/review-scenario.md) | PATCH | `/api/v1/scenarios/{scenarioId}` | 설계 | reviewScenario |
| [API-SCN-05 · 승인본 수정용 새 버전 생성](./scenarios/create-scenario-version.md) | POST | `/api/v1/scenarios/{scenarioId}/versions` | 설계 | createScenarioVersion |
| [API-SCN-06 · 미사용 시나리오 초안 폐기](./scenarios/delete-scenario.md) | DELETE | `/api/v1/scenarios/{scenarioId}` | 설계 | deleteScenario |
| [API-SCN-07 · 시나리오 세부 목표 조회](./scenarios/list-scenario-micro-goals.md) | GET | `/api/v1/scenarios/{scenarioId}/sub-goals` | 설계 | listScenarioMicroGoals |
### 역할극

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-RPL-01 · 역할극 시작·동일 시작 요청 복원](./roleplay/start-roleplay.md) | POST | `/api/v1/activities/{activityId}/roleplay-sessions` | 설계 | startRoleplay |
| [API-RPL-02 · 역할극 세션·체크포인트 조회](./roleplay/get-roleplay-session.md) | GET | `/api/v1/roleplay-sessions/{sessionId}` | 설계 | getRoleplaySession |
| [API-RPL-03 · 역할극 일시정지·재개](./roleplay/update-roleplay-session.md) | PATCH | `/api/v1/roleplay-sessions/{sessionId}` | 설계 | updateRoleplaySession |
| [API-RPL-04 · 역할극 음성 입력 제출](./roleplay/submit-voice-input.md) | POST | `/api/v1/roleplay-sessions/{sessionId}/voice-inputs` | 조건부 구현 | submitVoiceInput |
| [API-RPL-05 · 역할극 텍스트 입력 제출](./roleplay/submit-text-input.md) | POST | `/api/v1/roleplay-sessions/{sessionId}/text-inputs` | 설계 | submitTextInput |
| [API-RPL-06 · 역할극 처리 결과·검증 응답 조회](./roleplay/get-roleplay-turn.md) | GET | `/api/v1/turns/{turnId}` | 설계 | getRoleplayTurn |
| [API-RPL-07 · 역할극 실제 전달 대화 이력 조회](./roleplay/list-roleplay-turns.md) | GET | `/api/v1/roleplay-sessions/{sessionId}/turns` | 설계 | listRoleplayTurns |
| [API-RPL-08 · 강사용 목표별 관찰 결과 조회](./roleplay/list-roleplay-goal-statuses.md) | GET | `/api/v1/roleplay-sessions/{sessionId}/goal-statuses` | 설계 | listRoleplayGoalStatuses |
| [API-RPL-09 · 강사용 지원 수준 변화 조회](./roleplay/list-support-changes.md) | GET | `/api/v1/roleplay-sessions/{sessionId}/support-level-changes` | 설계 | listSupportChanges |
### 리포트·추천

| API 문서 | Method | 경로 | 상태 | FE 함수 |
|---|---|---|---|---|
| [API-REC-01 · 완료 리포트 단건 조회](./reports-recommendations/get-learning-report.md) | GET | `/api/v1/activities/{activityId}/learning-record` | 설계 | getLearningReport |
| [API-REC-02 · 아동 완료 리포트 목록 조회](./reports-recommendations/list-learning-reports.md) | GET | `/api/v1/learning-records` | 설계 | listLearningReports |
| [API-REC-03 · 학습 기록 추천 목록 조회](./reports-recommendations/list-recommendations.md) | GET | `/api/v1/learning-records/{recordId}/recommendations` | 설계 | listRecommendations |
| [API-REC-04 · 추천 상세 조회](./reports-recommendations/get-recommendation.md) | GET | `/api/v1/recommendations/{recommendationId}` | 설계 | getRecommendation |
| [API-REC-05 · 추천 채택·수정 채택·보류·거절](./reports-recommendations/decide-recommendation.md) | PATCH | `/api/v1/recommendations/{recommendationId}` | 설계 | decideRecommendation |

## 변경과 검증 절차

1. 동작 변경 PR에서 영향 API ID와 호환성 영향을 적고, 해당 MD의 Method/Path·인증·필수 필드·null·응답·상태·오류·복구를 먼저 갱신합니다.
2. types/호출 함수/fixture/모의 검사를 같은 변경에 맞춥니다. 새로운 경로는 다음 미사용 번호, 제거는 기존 ID/파일을 남기고 미제공으로 표시합니다.
3. Node24에서 아래 검사를 실행합니다. 실행 위치가 저장소 루트이고 이 폴더가 v1일 때의 명령입니다. v1 안에서는 `node --experimental-strip-types examples/verify-examples.mjs`를 사용합니다.

~~~bash
node --experimental-strip-types v1/examples/verify-examples.mjs
~~~

검사는 JSON·57개 ID/문서·함수 누락·relative 링크·인증/CSRF/멱등 전송·조건부/설계 차단·200/201/202/204·현재 계약의 회귀를 네트워크 없이 확인합니다. 실제 DB/외부 AI/브라우저 쿠키·CORS·기능 인수를 대신하지 않습니다. 복사한 FE 프로젝트에서는 해당 설정으로 TypeScript 검사와 빌드도 실행합니다.

[fixtures.json](./examples/fixtures.json)은 합성 예제이며 DB seed가 아닙니다. 본문/실명/토큰/비밀번호/원본 음성은 Git·Issue·로그에 올리지 않습니다.
