# API-QUIZ-01 · 검증 문항 풀 목록 조회

> 미제공 — 2026-10-22 / develop `63e4314`. API ID는 과거 계약 추적용으로 유지합니다.

## 1. Endpoint

| 항목 | 값 |
|---|---|
| 과거 Method | GET |
| 과거 Path | /api/v1/quiz-items |
| 현재 상태 | 해당 Controller/공개 API 없음 |
| FE 함수 | 제공하지 않음 |

## 2. 용도·중요 규칙

문항 풀 목록/상세의 공개 Controller는 현재 제거되어 있습니다. 과거 문서의 강사 전용 조회 API를 호출 가능한 것으로 사용하지 않습니다. 정답/미발급 힌트를 FE로 미리 공급하지 않습니다. 기존 API ID와 파일명은 재사용하거나 다른 API에 할당하지 않습니다.

## 3. 요청

현재 지원하는 요청 계약이 없습니다. 배정 문항은 [API-QUIZ-03](./list-assigned-quiz-items.md)을 사용합니다.

## 4. 성공 응답

현재 공개 성공 DTO가 없습니다. 예전 예시를 현재 서버 응답으로 사용하지 않습니다.

## 5. 오류·FE 처리

제거된 경로의 실제 HTTP 응답은 인증·라우팅 계층에 따라 달라질 수 있습니다. 특정404/405를 보장하는 제품 API로 취급하지 않습니다.

## 6. 복사 가능한 TypeScript

현행/설계 함수에서 제외했습니다. 이 API용 fetch/cURL을 호출하지 않습니다.

## 7. cURL 계약 확인

제공하지 않습니다. [API 목차](../README.md)에서 지원 경로를 선택하세요.

## 8. 연결 Task·미정·검증

- 관련 Task ID: `S5-BAE-01`
- 기준: [현재 공개 퀴즈 Controller](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/blob/63e43144c92449ec5670390a6b99d20d2b2580a5/backend/src/main/java/com/neuringo/neuringobe/quiz/controller/QuizController.java)
- [공통 규칙](../integration-guide.md) · [API 목차](../README.md)
