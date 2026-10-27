# API v1 변경 이력

## 2026-10-27 — 활동 완료를 서버가 처리, 상태 조회 구현 (PR #76)

| API | 변경 | 호환성 영향 |
|---|---|---|
| ACT-04 | 설계 → 구현, 함수를 endpoints.ts로 이동. 없는 활동·다른 아동·담당 아닌 강사는 모두 404 ACTIVITY_NOT_FOUND. 강사 조회는 민감 조회 기록 | allowDrafts 없이 호출 가능. 응답 `{ status }`는 설계와 같음 |
| QUIZ-04 | 독립 퀴즈 마지막 문항 FINALIZED 때 활동 COMPLETED, 스탬프·학습 기록 한 번 | 응답 모양 그대로. FE가 따로 보내는 완료 요청 없음 |
| ACT-02/03 | completedAt·rewardIssuedAt이 독립 퀴즈 완료 때 채워짐 | 필드 모양 그대로 |

## 2026-10-22 — develop 63e4314 반영

기준 커밋: [`63e43144c92449ec5670390a6b99d20d2b2580a5`](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/tree/63e43144c92449ec5670390a6b99d20d2b2580a5). 기존 2026-10-06/c644dc2 명세에서 현재 코드의 HTTP 계약으로 갱신했습니다. 이 변경은 로컬 v1 문서/연동 예제만 수정하며 GitHub 푸시·서버·DB·제품 정책 승인을 수행하지 않습니다.

| API | 변경 | 호환성 영향 |
|---|---|---|
| IAM-02 | termsAgreed/privacyAgreed 필수true, 서버 동의 증빙 | 예전 가입 body는422 |
| IAM-02/03/04 공통 | UserRole=INSTRUCTOR, AccountStatus=ACTIVE | 레거시 운영자/계정상태를 현행 DTO로 쓰지 않음 |
| IAM-06 | DELETE /users/me → POST /users/me/withdrawal + password,204 | 경로/Method/body 변경.403 PASSWORD_MISMATCH 추가 |
| CLS-05/08/10 | 삭제/단건조회 Controller 반영, 구현 함수로 이동 | draft차단 해제,204/404와 삭제범위 확인 |
| GOAL-01/02/03 | category 최대30자 선택 요청/nullable응답 | 타입·해시/표시 계약 확인 |
| ACT-01 | childId/goalId → childId/activityType/goal, 원자 저장 | 필수멱등키·신규201/재전송200·유형/선행/목표 오류 반영 |
| ACT-02/03 | activityType, nullable goalId, 상세 중첩 응답 | data.activity 접근, 독립퀴즈 목표조회 분기 |
| QUIZ-01/02 | 문항풀 조회 API 제거 | 기존ID/파일을 미제공으로 남기고 함수/예시는 제거 |
| QUIZ-03 | 최초결합3/독립퀴즈10, 부족 시 배정거부 | 고정3문항 가정 제거 |
| QUIZ-07 | FIRST_COMBINED 결과만 최초난이도 실제사용 | 독립퀴즈 계산값으로 역할극 난이도를 바꾸지 않음 |
| RPL-04 | 조건부구현, audio+키,200결과/202처리중 | inputId/turnId 비동기 접수 DTO 제거, 실제 messages/action 등 반영 |
| RPL-06 | 현재 Controller없는 설계로 유지 | 음성202 후 자동조회 경로로 연결하지 않음 |

## 2026-10-22 — 저장소 문서 명명 방식 반영

- 기존 한글·번호 접두사 디렉터리를 `auth`, `classrooms-children`, `learning-goals`, `activities`, `quizzes`, `scenarios`, `roleplay`, `reports-recommendations`로 변경했습니다.
- `API-ACT-01.md` 형식 대신 `create-activity.md`처럼 동작을 설명하는 파일명을 사용합니다. API ID와 계약 내용은 유지합니다.
- 공통 문서는 `integration-guide.md`·`changelog.md`, 예제 폴더는 `examples/`로 통일했습니다. 목차·상대 링크·실행 명령·문서 검증도 변경된 경로에 맞췄습니다.

## 문서 운영 규칙

- 기존 57개 API ID는 유지하고 경로는 영문 소문자 kebab-case로 정리합니다. 미제공 ID를 재사용하지 않습니다.
- 현재 구현28/조건부1/설계26/미제공2를 구분하고, 조건부 활성화와 설계 개발을 별도 옵션으로 차단합니다.
- README·개별MD·types/함수/fixtures/모의 검사를 함께 갱신합니다. Swagger를 별도 수동 원본으로 병행하지 않고 Markdown의 변경을 코드 계약과 같은 PR에서 검토합니다.
- 이 폴더 밖의 로컬/첨부문서/ZIP 경로를 필수 링크로 사용하지 않습니다. 소스 근거는 검토 커밋 고정 GitHub 링크, 문서 링크는 폴더 내 상대 링크입니다.
- 원래 제품 정책과 다른 구현(탈퇴 비밀번호 등)은 현재 HTTP 동작으로 명시하되 기능 정책까지 자동 승인된 것으로 해석하지 않습니다.

## 이번 갱신의 확인 결과

- 문서·전송 모의 검사114개 통과: 57개ID/상태·JSON·상대링크·영문 kebab-case 경로·현재/설계/조건부 차단·멱등성·모든 허용 성공상태·제거API 호출 제외.
- 문서 내 TypeScript 블록165개와 공통 TypeScript 파일4개를 strict/exactOptionalPropertyTypes/noEmit으로 검사했습니다.
- 현행/조건부29개 경로를 기준 커밋의 Controller와 대조했고 미제공2개 경로는 없는 것을 확인했습니다. ACT-03은 현재 강사만 허용하는 보안필터를 반영했습니다.
- 실제 서버·DB·AI·브라우저·배포 인수는 실행하지 않았습니다. 위 결과는 문서/연동 예제의 검증입니다.
