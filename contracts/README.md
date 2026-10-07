# FE ↔ BE API 명세

`api-v1.json` 한 파일을 백엔드와 프론트가 같이 검사한다.

| 쪽 | 테스트 | 무엇을 막나 |
|---|---|---|
| 백엔드 | `backend/src/test/java/com/neuringo/neuringobe/contract/ApiContractTest.java` | 실제 API 응답이 API 명세와 달라짐 |
| 프론트 | `frontend/src/test/contract/apiContract.test.ts` | 프론트 테스트가 쓰는 MSW 가짜 서버가 실제 API 와 달라짐 |

한쪽만 고치면 다른 쪽 테스트가 깨진다. API 명세를 바꿀 때는 이 파일과 양쪽 코드를 같은 PR 에서 고친다.

## 시나리오 형식

- `request`
  - `method`, `path`: 경로와 본문의 `{classId}`·`{otherClassId}`·`{instructorEmail}` 같은 자리표시자는 테스트가 채운다(`placeholders` 참고). 강사 이메일·비밀번호는 테스트가 실행마다 만든다.
  - `body`: JSON 으로 보낸다. `rawBody`: 문자열 그대로 보낸다(깨진 JSON 확인용).
  - `csrf`: 변경 요청은 기본으로 CSRF 토큰을 싣는다. `false` 면 싣지 않는다.
  - `headers`: 더 실을 헤더(예: `{ "Idempotency-Key": "{requestKey}" }`). 값의 자리표시자도 채운다.
  - `auth`: 없거나 `true` 면 로그인한 강사의 토큰(`Authorization: Bearer`)을 싣는다. `false` 면 싣지 않는다(가입·로그인 같은 공개 경로, 토큰 없는 요청). `"invalid"` 면 틀린 토큰을 싣는다.
- `response`
  - `status`: HTTP 상태.
  - `body`: 응답 본문의 모양.
    - 객체: 키 목록이 정확히 같아야 한다. 키가 늘거나 줄면 실패한다.
    - 배열: 예시가 비어 있으면 실제도 비어 있어야 한다. 예시에 항목이 있으면 실제는 1개 이상이고, 모든 항목이 첫 예시와 같은 모양이어야 한다.
    - 문자열 `"<uuid>"`: UUID 형식. `"<string>"`: 빈 문자열이 아닌 아무 문자열. `"<any>"`: 검사하지 않는다(Spring Security 403 은 서버마다 본문이 다르다).
    - 그 밖의 값: 그대로 같아야 한다. 문자열 안의 `{...}` 는 채운 값으로 바꾼 뒤 비교한다.

아동 이름은 공통 픽스처(김하늘 등) 가짜 이름만 쓴다. 강사 이메일은 예약 도메인(example.com)만 쓴다. 공개 레포다.
