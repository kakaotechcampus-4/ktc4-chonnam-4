# 테스트 가이드

## 누가 무엇을 쓰나

팀 합의(04 문서 담당자 해석 규칙)를 따른다.

| 누가 | 무엇 |
|---|---|
| **기능 담당자** | 자기 도메인의 단위 테스트, API·화면 테스트, 재현 데이터 |
| **CI/CD·품질 담당(정민서)** | 테스트 도구·설정·공통 픽스처, 종단(E2E), API 명세·권한·보안·개인정보 공통 검사, CI 게이트 |

기능 테스트를 쓸 때는 아래 기반을 그대로 가져다 쓰면 된다.

## 테스트 유형

공통 검사는 노션 03 인수 조건·06 DoD("정상·주요 예외·중복 요청 테스트", "권한 밖 접근 차단", "민감 데이터가 응답·로그에 남지 않음")를 기능 코드 바깥(HTTP·화면)에서 확인한다. 구현 안쪽(Mockito·컴포넌트 단위)은 기능 담당자 테스트가 본다.

| 유형 | 무엇을 막나 | 백엔드 | 프론트·E2E |
|---|---|---|---|
| API 명세 일치(FE ↔ BE) | 실제 API 와 프론트 테스트의 가짜 서버(MSW)가 서로 달라짐 | `contract/ApiContractTest` | `src/test/contract/apiContract.test.ts` — 둘 다 [`contracts/api-v1.json`](../contracts/README.md) 을 읽는다 |
| 경계값(매개변수화) | 이름 검증 경계(공백·제어 문자·짝 없는 서로게이트·보이지 않는 문자·100자·이모지·특수문자)가 학급·아동·강사 이름에서 달라짐 | `NameInputBoundaryIntegrationTest` | — |
| 인증(VS-001) | 가입·로그인·본인 조회, 토큰 없음·틀림·만료·로그아웃은 401, 비밀번호 해시 저장·8자 규칙, 이메일 정규화·중복 409, 로그인 실패는 모두 같은 응답·잠금 없음, 보호 화면은 로그인 화면으로 | `AuthIntegrationTest` | `instructor/auth/authFlow.test.tsx` · `e2e/instructor-auth.spec.ts` |
| HTTP 통합 | 학급·강사 격리(다른 강사의 학급은 없는 학급과 같은 404)·동명이인, 실패한 요청의 부수 효과, 본문으로 서버 값 바꿔치기 | `ClassroomChildIntegrationTest` · `ErrorResponseIntegrationTest` | — |
| 화면 통합 | 강사·아동 화면의 정상·실패 흐름(로딩·422·403·네트워크·중복 제출·사용 종료·입장 가드) | — | `ClassroomListPage.test.tsx` · `ClassroomCreatePage.test.tsx` · `ClassroomDetailPage.test.tsx` · `childFlow.test.tsx` · `router.test.tsx` · `useWarnBeforeUnload.test.ts` |
| 보안 | CSRF·CORS·보안 헤더·actuator 노출, 배포 프로필(dev·prod) 인증 정책, 이름 출력 XSS | `config/LocalSecurityPolicyTest` · `config/LocalSecurityMatrixTest` · `config/DefaultSecurityPolicyTest` | `src/security.test.tsx` |
| 개인정보 | 아동 이름·강사 이메일·비밀번호가 로그·오류 응답·AI 요청에 섞임, 스키마에 실명 외 인적 정보 컬럼, 비밀번호·토큰 원문 저장 | `LogPrivacyIntegrationTest` · `SchemaIntegrityIntegrationTest` · `AuthIntegrationTest` · `ai/infrastructure/AiProviderContractTest` | E2E 마커 스캔 |
| DB 스키마 | enum ↔ CHECK, FK, UNIQUE(이메일·토큰 해시), NOT NULL, 이름·이메일 길이 ↔ `@Size` | `SchemaConstraintIntegrationTest` · `SchemaIntegrityIntegrationTest` | — |
| 동시성 | 동시에 들어온 등록의 유실·학급 혼입, 같은 이메일 동시 가입 | `ConcurrentRegistrationIntegrationTest` | — |
| 결정 표 | AI 평가 결과 전달 게이트(안전·PASS·치명 실패 0건), 형식이 틀린 평가 결과는 게이트 전에 형식 오류 | `ai/application/EvaluationResultDeliveryGateTest` | — |
| AI 제공자 연동 규칙 | `ai-provider-contract.md` 의 실패 분류·재시도 가능 여부·자동 재시도 0회·추적 ID 미포함 | `ai/infrastructure/AiProviderContractTest`(MockServer) | — |
| 아키텍처 | 계층 방향, 엔티티 노출, 트랜잭션 위치, 도메인 순환, 로그 경로 | `ArchitectureTest` | — |
| 속성 기반 | 응답 해석이 어떤 본문·상태 코드에서도 깨지지 않음(무작위 입력 수백 개) | — | `api.property.test.ts`(fast-check) |
| 접근성 | WCAG 2.x A·AA(레이블·이름·역할·키보드·색 대비·누를 수 있는 크기) | — | `src/accessibility.test.tsx`(jsdom) · `e2e/accessibility.spec.ts`(실제 브라우저) · `e2e/child-flow.spec.ts` |
| 종단(E2E) | 실제 백엔드·DB·빌드로 핵심 흐름의 정상·실패 경로, PC·태블릿 | — | `e2e/*.spec.ts` |

테스트 도구 버전은 [github-actions.md](github-actions.md#버전-고정--lts-기준) 에 적었다. npm 도구는 `package.json` 에 정확한 버전(`^` 없음)으로 고정한다.

## 실행

| 무엇 | Git Bash | PowerShell |
|---|---|---|
| 전부 (CI 와 같음) | `bash scripts/verify.sh` | — |
| 프론트 | `bash scripts/verify.sh frontend` | `cd frontend; npm ci; npm run lint; npm run build; npm run test:coverage` |
| 백엔드 | `bash scripts/verify.sh backend` | `cd backend; .\gradlew.bat build` |
| 워크플로·스크립트 | `bash scripts/verify.sh workflows` | — (Docker 로 도는 셸 스크립트) |
| 비밀 정보(gitleaks) | `bash scripts/verify.sh security` | — (Docker) |
| 종단(E2E) | `bash scripts/verify.sh e2e` | — (Docker·JDK·Node 를 셸 스크립트가 조율) |
| 백엔드 이미지 | `bash scripts/verify.sh docker` | `cd backend; docker build -t neuringo-backend .` (빌드만) |
| 프론트 테스트만 | — | `cd frontend; npm test` (감시 모드는 `npm run test:watch`) |

필요한 것
- **Node 24 LTS**(`frontend/.nvmrc`). 다른 버전이면 `verify.sh` 가 경고한다.
- **JDK 21**
- **Docker Desktop**: 백엔드 테스트 DB(Testcontainers)와 린트 도구가 Docker 로 돈다. 꺼져 있으면 `Could not find a valid Docker environment` 로 실패한다.
- **Playwright Chromium**(E2E 만): `cd frontend; npx playwright install chromium`. E2E 는 8080·5173 포트가 비어 있어야 한다.

## 프론트 테스트

Vitest 5 + jsdom + Testing Library + MSW 를 쓴다.

**위치와 공통 기반**
- 테스트는 대상 파일 옆에 `*.test.ts(x)` 로 둔다.
- 공통 기반은 `src/test/` 에 있다.
  - `setup.ts`: jest-dom 매처 등록, 매 테스트 뒤 DOM·sessionStorage(강사 토큰)·아동 세션 정리, MSW 수명주기
  - `msw/handlers.ts`: 백엔드 응답 모양(`{ data, meta }`, `{ error: { ..., fieldErrors: [{ field, message }] } }`)을 흉내 내는 기본 핸들러와 **공통 픽스처**. 테스트 강사 A(`instructors.a`)·B, 강사 A 의 학급 A1(햇살반)·B1(바람반), 강사 B 의 학급 C1(구름반), 아동 A1-1·A1-2 는 동명이인 "김하늘", B1-1 "이바다"
    - 강사 비밀번호·토큰은 실행마다 새로 만든다. 이메일은 예약 도메인(example.com)이다. 레포에 고정 자격증명을 두지 않는다.
    - 실제 서버와 같은 순서로 막는다: CSRF 없음 403 `CSRF_TOKEN_INVALID`(변경 요청) → 토큰 없음 401 `AUTHENTICATION_REQUIRED`·틀린 토큰 401 `INVALID_TOKEN` → UUID 가 아닌 ID 400 → 깨진 JSON 400 → 입력 검증 422(빈 값·제어 문자·보이지 않는 문자만·101자 이상, 그 필드의 `fieldErrors`) → 없는 학급·다른 강사의 학급 404.
    - 가입(409 중복)·로그인(401 `INVALID_CREDENTIALS`)·현재 세션·로그아웃도 흉내 낸다. 만든 학급·아동·계정은 다음 요청에 나온다. 테스트가 끝나면 `setup.ts` 가 픽스처만 남긴다(`resetMswData`).
    - 이 모양은 API 명세(`contracts/api-v1.json`)로 백엔드와 같이 검사한다. 핸들러를 고치면 `src/test/contract/apiContract.test.ts` 가 확인한다.
  - `render.tsx`: `renderRoutes(routes, 시작경로)`. 라우터와 React Query 를 붙여 렌더링하고 `user`(user-event)·`router` 를 돌려준다. React Query 재시도는 꺼져 있다. 실제 라우팅 표로 렌더링하려면 `renderRoutes(router.routes, "/child")` 처럼 `@/router` 의 `router.routes` 를 넘긴다.
    - `signIn()`: 강사 A 로 로그인한 상태로 만든다(토큰을 sessionStorage 에 넣는다). 강사 화면·강사 API 테스트는 렌더링 전에 부른다. 부르지 않으면 강사 화면은 로그인 화면으로 간다.
    - `enterAsChild()`: 아동이 입장한 상태로 만든다. 아동 활동 화면(`/child/activities` 등)은 입장해야 열린다.

**규칙**
- `describe`·`it`·`expect` 는 `import { ... } from "vitest"` 로 가져온다(전역이 아니다).
- 네트워크는 MSW 가 가로챈다.
  - **핸들러가 없는 요청은 테스트를 실패시킨다.** 새 API 를 부르면 `handlers.ts` 에 추가한다.
  - 테스트 하나에서만 다르게 응답하려면 `server.use(...)` 를 쓴다. 테스트가 끝나면 자동으로 원래대로 돌아간다.
  - 주소는 `*/api/v1/...` 처럼 origin 을 와일드카드로 둔다. API 주소가 환경변수로 바뀌어도 그대로 쓸 수 있다.
- 테스트 하나의 시간 제한은 20초다(`vite.config.ts` 의 `testTimeout`). 파일마다 첫 렌더가 병렬 실행 중에 5초를 넘길 때가 있다.
- 같은 순간 두 번 제출하는 경우는 `fireEvent.submit(form)` 을 연달아 부른다. `user.dblClick` 은 사이에 화면이 다시 그려져 버튼 비활성만 확인하게 된다.
- 테스트 안에서 `router.navigate(...)` 로 옮겨 가면 `act(() => router.navigate(...))` 로 감싼다. 가드가 다른 화면으로 다시 보내는 것까지 다 그린 뒤에 확인해야 한다.
- **속성 기반 테스트**(fast-check): 규칙을 "어떤 입력에서도 참"인 문장으로 쓰고 무작위 입력으로 확인한다. 실패하면 fast-check 가 가장 작은 반례와 `seed`·`path` 를 출력한다. `fc.assert(..., { seed, path })` 로 같은 입력을 다시 돌린다.
- **접근성**: `src/accessibility.test.tsx` 가 모든 화면에 axe-core(WCAG A·AA)를 돌린다. 새 화면을 라우터에 넣으면 그 파일의 `PAGES` 에도 넣는다. 위반이 나오면 규칙을 끄지 말고 화면을 고친다(색 대비는 실제 CSS 가 필요해 E2E 가 본다).

```tsx
import { http, HttpResponse } from "msw"
import { screen } from "@testing-library/react"
import { expect, it } from "vitest"
import { apiError } from "@/test/msw/handlers"
import { server } from "@/test/msw/server"
import { renderRoutes, signIn } from "@/test/render"
import { ClassroomListPage } from "./ClassroomListPage"

it("목록을 못 불러오면 오류 문구를 보여 준다", async () => {
  signIn()
  server.use(http.get("*/api/v1/classrooms", () => apiError(500, "X", "잠시 후 다시 시도해 주세요", "/api/v1/classrooms")))

  renderRoutes([{ path: "/classrooms", element: <ClassroomListPage /> }], "/classrooms")

  expect(await screen.findByText("잠시 후 다시 시도해 주세요")).toBeInTheDocument()
})
```

## 백엔드 테스트

JUnit 6 + AssertJ + MockMvcTester + Testcontainers(`postgres:18.6-alpine`)를 쓴다.

**공통 기반** (`backend/src/test/java/com/neuringo/neuringobe/`)

| 붙이는 것 | 언제 | 딸려 오는 것 |
|---|---|---|
| `@LocalProfileIntegrationTest` | API 를 HTTP 로 불러 성공을 확인할 때 (대부분 이것) | local 보안 정책(토큰 인증 + 쿠키 CSRF·CORS 5173), DB, `MockMvcTester`, `TestFixtures` |
| `@IntegrationTest` | 배포 프로필(dev·prod)에서 막히는지 확인할 때, DB 제약을 직접 볼 때 | 기본 보안 정책(같은 토큰 인증, CORS 없음, 세션 CSRF), DB, `MockMvcTester` |
| `@WebMvcTest(XxxController.class)` + `@MockitoBean` | Controller·오류 응답 형식만 따로 볼 때 (예: `common/GlobalExceptionHandlerTest`) | 그 Controller 와 MVC·보안 설정만, DB 없음 |
| 없음 | Spring 이 필요 없는 단위 테스트 (예: `child/service/ChildServiceTest` — Mockito) | — |

- `TestFixtures` 는 주입받아 쓴다(`@Autowired TestFixtures fixtures`).
  - **테스트 강사**: 처음 쓸 때 강사 한 명을 가입·로그인시키고(`instructor()`), 학급·아동 요청에 그 토큰을 싣는다. 이메일은 `instructor-<무작위>@example.com`(예약 도메인), 비밀번호도 부를 때마다 무작위라 레포·CI secret 에 고정 계정이 없다. 다른 강사가 필요하면 `signUpInstructor()` 로 한 명 더 만든다.
  - `createStandard()`: 학급 A1(햇살반)·B1(바람반), 아동 A1-1·A1-2 동명이인 "김하늘", B1-1 "이바다". 프론트 MSW 픽스처와 같고, 부를 때마다 새로 만든다. `createClassroom(owner, name)` 처럼 강사를 지정할 수도 있다.
  - `get(uri, ...)`: 테스트 강사의 토큰을 실어 GET 한다. `get(other, uri, ...)` 는 그 강사로 보낸다.
  - `postJson(uri, body)`: GET /api/v1/csrf 로 받은 쿠키와 헤더, 테스트 강사의 토큰을 실어 POST 한다(프론트와 같은 흐름).
  - `postJsonText(uri, json)`: 같은 방식으로 본문 문자열을 그대로 보낸다. null 값·키 없음·깨진 JSON 을 보낼 때 쓴다.
  - `postPublicJson(uri, body)`·`signUpRequest`·`loginRequest`: 토큰 없이 공개 경로(가입·로그인)에 보낸다.
  - `authorized(request)`: 헤더·쿠키를 직접 바꿔 보는 보안 검사에서 `mvc.post()...` 요청에 토큰만 싣는다.
- 입력값 여러 개를 같은 규칙으로 볼 때는 `@ParameterizedTest` + `@MethodSource` 를 쓰고, 공백·제어문자처럼 이름으로 보기 어려운 값은 `Named.named("탭", "\t")` 로 이름을 붙인다(보고서에 그대로 나온다).

```java
@LocalProfileIntegrationTest
class ChildApiTest {

    @Autowired private TestFixtures fixtures;

    @Test
    void listsChildrenOfClassroom() {
        TestFixtures.Standard data = fixtures.createStandard();

        assertThat(fixtures.get("/api/v1/classrooms/{classId}/children", data.b1()))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.data[*].displayName")
                .asArray()
                .containsExactly(TestFixtures.CHILD_B1_1);
    }
}
```

**규칙** (`@LocalProfileIntegrationTest`·`@IntegrationTest` 를 쓸 때)
- `csrf()` 후처리기를 쓰지 않는다. 공유 컨텍스트의 CSRF 저장소를 세션 저장소로 바꿔서, 같은 컨텍스트를 쓰는 다른 테스트의 쿠키 CSRF 흐름까지 깨진다. 변경 요청은 `fixtures.postJson` 으로 보낸다. (`@WebMvcTest` 처럼 따로 뜨는 컨텍스트에서는 써도 된다.)
- 통합 테스트에 `@Transactional` 을 걸지 않는다. 롤백하면서 flush 를 건너뛰어 FK·CHECK·길이 위반이 숨는다. 데이터는 새로 만들어(무작위 UUID) 테스트끼리 겹치지 않게 한다.
- Controller·Service·Repository 를 목(mock)으로 바꾸지 않고 HTTP 로 확인한다. 내부 구조가 바뀌어도(#17 Service 계층 도입) API 응답이 같으면 그대로 통과한다.
- 테스트 클래스에 `@SpringBootTest` 속성·`@MockitoBean` 을 덧붙이면 Spring 컨텍스트와 DB 컨테이너가 하나씩 더 뜬다. 위 애너테이션을 그대로 쓴다.
- AI 호출은 실제 제공자 대신 MockServer 로 대체한다(`backend/docs/ai-provider-contract.md`).
- Spotless 가 테스트 코드 포맷도 검사한다. 커밋 전에 `./gradlew spotlessApply`.

**공통 검사** (CI/CD·품질 담당). 실패하면 기능 코드가 팀 약속을 어긴 것이다.

| 테스트 | 약속 |
|---|---|
| `config/LocalSecurityPolicyTest` | local: 로그인했어도 CSRF 토큰 헤더 없는 변경 요청은 403 `CSRF_TOKEN_INVALID`, CSRF 쿠키는 HttpOnly, CORS 는 5173 만 |
| `config/LocalSecurityMatrixTest` | local 행렬(로그인한 강사로): 모든 변경 메서드 × 경로(로그아웃 포함)에서 CSRF 토큰 없음·틀림·쿠키 값 그대로·다른 세션 쿠키는 403 `CSRF_TOKEN_INVALID`(path 는 원래 경로)이고 저장 안 됨, 5173 이 아닌 Origin(https·127.0.0.1·다른 포트·null)은 막음, 다른 Origin 의 변경은 토큰이 맞아도 막음, 프론트가 싣는 헤더(Authorization 포함) 허용, 보안 헤더(nosniff·DENY·no-store), actuator 는 health·info 만 노출하고 지금은 로그인해야 봄 |
| `config/DefaultSecurityPolicyTest` | 배포 프로필(dev·prod): 공개 경로 밖 조회는 401 `AUTHENTICATION_REQUIRED`(health·info 포함), 막힌 변경은 저장 안 됨, 지어낸 CSRF 막음, 로그인한 강사의 CSRF 실패는 401 이 아니라 403 `CSRF_TOKEN_INVALID`, 가입 → 로그인 → 토큰으로 조회·생성이 됨(세션 CSRF), CORS preflight 막음, 막힌 응답에도 보안 헤더 |
| `ErrorDispatchIntegrationTest` | 실제 서버(포트)로 필터 단계 오류가 /error 재진입을 거쳐도 원래 상태로 나감: CSRF 실패는 403 `CSRF_TOKEN_INVALID`, 필터에서 던진 예외(토큰 조회 중 DB 장애)는 500 `INTERNAL_ERROR`, 둘 다 path 는 원래 경로이고 401 로 바뀌지 않음. MockMvc 는 /error 재진입을 흉내 내지 않아 따로 둔다 |
| `AuthIntegrationTest` | VS-001: 가입 → 로그인 → 본인 조회, 보호 요청은 토큰 없음 401 `AUTHENTICATION_REQUIRED`·틀림·형식 오류·만료·로그아웃 401 `INVALID_TOKEN`, 로그아웃은 그 세션만 끝냄, 막힌 변경은 저장 안 됨, 공개 경로는 틀린 토큰이 달려도 열림, 비밀번호는 BCrypt 해시만·토큰은 해시만 저장, 7자 422·8자는 조합 규칙 없이 가입, 72바이트 초과는 422, 이메일 대소문자·공백 정규화와 중복 409, 로그인 실패(틀린 비밀번호·없는 이메일·제어 문자)는 같은 401, 여러 번 틀려도 잠기지 않음 |
| `ClassroomChildIntegrationTest` | 학급 격리, 강사 격리(다른 강사의 학급은 목록에 없고 상세·아동 목록·아동 등록은 404, 없는 학급과 같은 오류 본문), 동명이인, 이름 한글 100자 저장·101자 422 |
| `NameInputBoundaryIntegrationTest` | 학급·아동·강사 이름 경계: 공백뿐·빈 값·null·키 없음·제어 문자(NUL 등)·짝 없는 서로게이트·보이지 않는 문자만(NBSP·폭 없는 공백·BOM)은 422(그 필드)이고 저장 안 됨, UTF-16 100 까지 저장(이모지 50개), 넘으면 422, 마크업·SQL 모양도 글자 그대로 |
| `ErrorResponseIntegrationTest` | 실패한 요청은 아무것도 저장하지 않음(404·400·415), 본문의 classId·status·instructorId 는 무시(강사는 로그인한 강사), 오류 본문(401·409·422 포함)에 예외·스택·SQL 없음, 응답마다 새 traceId |
| `contract/ApiContractTest` | 실제 API 응답이 API 명세(`contracts/api-v1.json`)와 같음 |
| `LogPrivacyIntegrationTest` | 아동 이름(성공·422·404·400)과 강사 이메일·비밀번호(가입·중복 409·로그인·401·422)가 서버 로그와 오류 응답에 남지 않음 |
| `SchemaConstraintIntegrationTest` | enum 값 ↔ status·role CHECK, FK(아동 → 학급, 학급 → 강사, 세션 → 강사), UNIQUE(이메일·토큰 해시) |
| `SchemaIntegrityIntegrationTest` | 아동 테이블은 ID·학급·실명·상태만(인적 정보 컬럼 없음), 학급·강사 계정·세션 테이블도 정한 컬럼만(토큰 원문 없음), 이름·이메일 VARCHAR ↔ `@Size(max)`, 필수 컬럼 NOT NULL, 학급의 강사는 강사 계정 UUID, 조회 인덱스, 마이그레이션 전부 성공 |
| `ConcurrentRegistrationIntegrationTest` | 동시에 들어온 등록(12건)이 빠짐·중복·학급 혼입 없이 한 번씩 저장, 동명이인 동시 등록, 같은 이메일 동시 가입은 한 명만(나머지 409) |
| `ai/application/EvaluationResultDeliveryGateTest` | 안전·PASS·치명 실패 0건일 때만 전달(8조합, PASS 아닌 판정 전부, 치명 실패 2건 이상 전부). 평가 결과 JSON 의 문자열 "true"·숫자 1·숫자 판정·소수 건수와 JSON null 은 형식 오류(INVALID_OUTPUT_FORMAT) |
| `ai/infrastructure/AiProviderContractTest` | AI 요청 본문에 추적 ID(아동·학급·세션 등) 없음, HTTP 상태별 실패 유형·재시도 가능 여부가 `ai-provider-contract.md` 의 표와 같음(4xx 는 `PROVIDER_REQUEST_REJECTED`, 해석할 수 없는 응답은 `PROVIDER_RESPONSE_ERROR`), 실패해도 한 번만 호출, 프롬프트·completion·제공자 오류 본문이 로그·실패 결과에 없음 |
| `ArchitectureTest` | `ai.application` 은 infrastructure·config·Spring AI·OpenAI SDK 에 의존하지 않음<br>controller → service → repository 방향만 허용. Controller 는 Repository 를 직접 쓰지 않음(#17)<br>Controller 는 엔티티를 쓰지 않고 dto 는 record, `@Transactional` 은 service 에만, common 은 도메인을 모름, 도메인 순환 없음, 필드 주입·System.out·java.util.logging 없음 |

## 종단 테스트 (E2E)

Playwright 로 실제 백엔드(local 프로필)·PostgreSQL·프론트 빌드를 붙여 브라우저에서 확인한다. 기능마다 쓰지 않고 여러 기능을 가로지르는 핵심 흐름만 둔다.

- 실행은 `bash scripts/verify.sh e2e`. `scripts/e2e.sh` 가 DB(55432) → 백엔드 bootJar(8080) → 프론트 빌드·미리보기(5173) → 테스트 → 정리를 한다.
  - 포트가 고정인 이유: 프론트가 API 주소를 `localhost:8080` 으로 하드코딩했고, local CORS 가 5173 만 연다.
- 테스트는 `frontend/e2e/*.spec.ts`, 설정은 `frontend/playwright.config.ts`. 타입검사는 `tsconfig.e2e.json` 으로 `npm run build` 에서 같이 돈다.
- **테스트 강사 계정**: E2E 가 가입 화면으로 직접 만든다(`e2e/support/instructor.ts`). 이메일은 `e2e-<마커>-<무작위>@example.com`, 비밀번호도 실행마다 무작위다. E2E 는 매번 빈 DB 로 돌아서 레포·CI secret 에 넣어 둘 계정이 없다. 강사 화면 테스트는 테스트마다 새 강사로 가입하므로 학급 목록에는 그 테스트의 학급만 있다.
- 지금 있는 흐름
  - `instructor-classroom.spec.ts`: 가입 → 학급 생성 → 상세 → 동명이인 "김하늘" 두 명 등록 → 새로고침 뒤에도 유지 → 목록에 한 번만
    - 버튼은 전부 `dblclick` 으로 누르고, 생성 요청(POST)이 누를 때마다 한 번만 나가는지 센다(중복 제출 방지, #17). 방지 코드를 빼면 같은 학급이 두 개 생겨 실패한다.
  - `instructor-failures.spec.ts`: 실패 경로 — 너무 긴 이름(실제 422), 서버 500(`page.route` 로 흉내), 서버 연결 실패(영어 대신 한국어 안내), 연결 끊김 뒤 재시도(한 번만 저장), 없는 학급(문구 한 번, 다시 시도 없이 5초 안에)·다른 강사의 학급(없는 학급과 같게)·UUID 가 아닌 주소
  - `instructor-auth.spec.ts`: 로그인 없이 강사 화면 → 로그인 화면 → 로그인하면 원래 화면, 틀린·폐기된 토큰은 첫 요청에서 로그인 화면(토큰 삭제), 로그아웃 뒤 뒤로 가기, 대소문자만 다른 이메일 중복 가입, 여러 번 틀린 뒤 로그인
  - `child-flow.spec.ts`: 아동 입장 → 내 활동 → 퀴즈 → 사용 종료, 입장 없이 활동 주소 열기·사용 종료 뒤 뒤로 가기는 코드 입력 화면(#22 의 5번), 활동 중 창 닫기 확인창(#18), 버튼 크기(WCAG 2.5.8 24px, 아동 주요 버튼 44px). **PC(chromium)와 태블릿(Galaxy Tab S4 설정) 두 프로젝트**에서 돈다
  - `accessibility.spec.ts`: 모든 화면에 axe-core(WCAG A·AA, 색 대비 포함). 강사 화면은 가입한 뒤, 아동 활동 화면은 입장한 뒤 화면 안의 링크로 옮겨 가서 본다(아동 상태는 새로고침에 남지 않는다)
    - 지금 있는 위반은 규칙을 끄지 않고 `KNOWN_VIOLATIONS` 에 화면별 곳 수로 적어 둔다. 아동 화면은 기본 보라색 `#7b68ee` 대비 부족(디자인 시스템 팔레트에 맞춰 유지하기로 함), 강사 화면은 보조 글자색 `#7c7870`·구분 글자 `#b3afa7` 대비 부족(시안 PNG 에서 추정한 색이라 확정되면 고친다). 거기 없는 규칙이 나오거나 수가 늘면 실패하고, 줄면 실행 보고서에 "알려진 위반이 줄었다" 알림이 뜬다 → 수를 줄인다
- 실패하면 로컬은 `frontend/playwright-report/index.html`, CI 는 Artifacts 의 `e2e-report` 에서 trace 를 연다(`npx playwright show-trace <trace.zip>`).
- CI 에서 실패했거나 재시도 끝에 통과한 테스트는 Issue **"E2E 경고 기록"** 에 댓글로 쌓인다(`scripts/e2e-record.sh`, PR·develop 모두). Issue 가 없으면 첫 경고 때 워크플로가 만든다.
  - 경고를 확인한 사람은 그 댓글을 인용(Quote reply)해서 원인을 적는다. 예: CI 서버 지연 / 실제 버그 / 테스트 코드 문제
  - 같은 테스트가 반복해서 올라오면 그 테스트를 고친다. 이 기록이 아래 "경고 → 차단 전환 조건" 의 근거가 된다.

**개인정보 마커 스캔**
- E2E 가 만드는 학급·아동 이름과 강사 이메일에는 실행마다 다른 마커(`e2e-` + 무작위 12자리)가 붙는다.
- 테스트가 끝나면 백엔드·DB 로그 전체에서 마커를 찾는다. **한 줄이라도 나오면 실패**한다. 아동 이름이나 강사 이메일이 로그로 새는 코드다.
- 로그에는 ID(`childId`·`userId` 등)만 남기고 이름·이메일·발화는 남기지 않는다.

**규칙**
- 이름은 공통 픽스처와 같은 것을 쓰고 뒤에 `process.env.E2E_MARKER` 를 붙인다.
- 입력칸은 `getByLabel` 로 찾는다. `<label>` 이 빠지면 스크린리더도 용도를 모르므로 E2E 도 같이 실패하게 둔다.
- 저장이 끝나기 전에 다음 입력을 하지 않는다. 목록에 결과가 뜬 걸 확인한 뒤 다음 동작을 한다(onSuccess 가 입력칸을 비우는 경쟁 조건).
- 고정 대기(`waitForTimeout`)를 쓰지 않는다. `expect(...).toHaveCount()` 처럼 스스로 다시 확인하는 단정을 쓴다.
- 실제 앱은 4xx 조회를 다시 시도하지 않고 바로 오류를 보여 준다(`main.tsx`). 5xx·네트워크 오류는 세 번까지 다시 시도한다(약 7초).
- 오프라인에서 누른 저장은 실패하지 않고 멈춰 있다가 다시 연결되면 한 번 나간다(React Query `networkMode: online`). 오류 문구를 기다리지 않는다.
- 같은 실행의 테스트는 표시(marker)를 같이 쓴다. "저장되지 않았다"는 그 테스트에서 가입한 강사의 학급 목록으로 본다.
- 아동 활동 화면은 `page.goto` 로 바로 열지 않는다(가드가 코드 입력 화면으로 보낸다). 입장한 뒤 화면 안의 링크로 옮겨 간다.

## DB 마이그레이션 규칙

`scripts/check-migrations.sh` 가 PR 에서 막는다.

- **이미 머지된 `V*.sql` 은 고치거나 지우지 않는다.** 바꿀 내용은 새 파일 `V<다음 번호>__<설명>.sql` 로 추가한다.
- 새 번호는 기존 최대 번호보다 커야 한다. 다른 사람과 동시에 같은 번호를 만들었다면 늦게 머지하는 쪽이 번호를 올린다.
- 파일 이름은 `V<번호>__<설명>.sql` 이다. 밑줄 두 개, 확장자는 소문자.
- 로컬 확인: `bash scripts/check-migrations.sh` (기준은 `origin/develop`, 커밋 안 한 변경도 본다)

## 하지 말 것

- **실제 아동 이름·음성·사진을 테스트 데이터로 쓰지 않는다.** 공개 레포이고, 로그와 아티팩트도 누구나 볼 수 있다.
- 로그에 아동 이름·발화를 남기지 않는다. E2E 마커 스캔이 막는다.
- AI 응답 **내용**을 단정하지 않는다. 모델을 바꾸면 제품은 멀쩡한데 테스트만 깨진다. 전달 게이트(안전·PASS·치명 0건) 같은 규칙만 확인한다.
- 실패하는 테스트를 `@Disabled`·`it.skip` 으로 끄고 머지하지 않는다. 꺼야 하면 이유와 되살릴 시점을 Issue 로 남긴다.

## CI 가 빨간색일 때

| 검사 | 막나 | 로컬 재현 | 흔한 원인 → 해결 |
|---|---|---|---|
| Frontend CI · Lint | 막음 | `npm run lint` | 규칙 위반 → 메시지의 파일·줄을 고친다 |
| Frontend CI · Build | 막음 | `npm run build` | 타입 오류(테스트 파일 포함) → `tsc` 메시지대로 고친다 |
| Frontend CI · Test | 막음 | `npm test` | `MSW 핸들러가 없는 요청` → `handlers.ts` 에 핸들러를 추가하거나 `server.use` 로 응답을 준다<br>강사 화면 대신 로그인 화면이 뜸 → 렌더링 전에 `signIn()` 을 부른다. 아동 활동 화면 대신 코드 입력 화면이 뜨면 `enterAsChild()`<br>앞 테스트의 상태가 남음 → zustand store 를 `afterEach` 에서 초기화한다<br>`apiContract.test.ts` → MSW 가짜 서버가 `contracts/api-v1.json` 과 다르다. API 명세가 바뀐 거면 명세 파일·백엔드도 같은 PR 에서 고친다<br>`accessibility.test.tsx` → 메시지의 규칙 id(예: `label`, `button-name`)대로 화면을 고친다<br>`api.property.test.ts` → 출력된 반례·`seed` 로 재현한다(위 "속성 기반 테스트") |
| Backend CI · Build and test | 막음 | `./gradlew build` | `spotlessJavaCheck` 실패 → `./gradlew spotlessApply`<br>401 `AUTHENTICATION_REQUIRED` → 학급·아동 API 는 로그인해야 한다. `fixtures.get`·`fixtures.postJson` 은 테스트 강사의 토큰을 싣는다. `mvc.get()` 을 직접 쓰면 `fixtures.authorized(...)` 로 감싼다<br>테스트 실패 → Summary 의 표, Artifacts 의 `backend-test-report`<br>`SchemaConstraintIntegrationTest` → enum 값을 추가했으면 새 V 파일로 CHECK 를 고친다<br>`ArchitectureTest` → `ai.application` 에서 SDK·infrastructure 참조를 빼고 `LlmProvider` 포트를 쓴다. Controller 가 Repository 를 부르면 Service 메서드로 옮긴다(다른 도메인이면 그 도메인 Service 를 부른다)<br>`ApiContractTest` → 응답 모양이 `contracts/api-v1.json` 과 다르다. 의도한 변경이면 명세 파일과 프론트(MSW·api.ts)를 같은 PR 에서 고친다<br>`SchemaIntegrityIntegrationTest` → 아동·학급 테이블에 컬럼을 늘렸으면 개인정보 검토 후 목록을 고친다. 이름 길이를 바꿨으면 `@Size` 와 V 파일을 같이 바꾼다<br>`LogPrivacyIntegrationTest` → 로그 문장에서 이름을 빼고 ID 를 쓴다 |
| Backend CI · Migration guard | 막음 | `bash scripts/check-migrations.sh` | 기존 V 파일 수정·삭제 → 되돌리고 새 V 파일로<br>번호 역행·중복 → 번호를 올린다 |
| Compose startup check | 막음 | `docker compose up --wait` (backend/) | 이미지 태그·볼륨 경로·healthcheck 문제 → `docker compose logs` |
| E2E · E2E | PR 경고 · develop 막음 | `bash scripts/verify.sh e2e` | 경고가 뜨면 Issue "E2E 경고 기록" 의 해당 댓글에 원인을 적는다<br>요소를 못 찾음 → 화면 문구·`<label>` 이 바뀌었는지 본다<br>POST 횟수가 다름·같은 이름이 두 개 → 요청 중에 버튼이 다시 눌렸다. 제출 잠금(`isSubmittingRef`)·`disabled` 가 빠졌는지 본다<br>`accessibility.spec.ts` → 메시지의 규칙 id(예: `color-contrast`)대로 색·크기를 고친다<br>`tablet` 프로젝트만 실패 → 좁은 화면에서 요소가 가려지거나 잘렸다<br>`8080 포트를 이미 쓰고 있다` → 로컬에서 띄운 백엔드를 끈다 |
| E2E · PII marker scan | 막음 | `bash scripts/verify.sh e2e` | 로그에 아동 이름이나 강사 이메일이 찍힘 → 출력된 파일·줄의 로그 문장에서 이름·이메일을 빼고 ID 를 쓴다 |
| Security · gitleaks | 막음(PR) | `bash scripts/verify.sh security` | 키·토큰이 커밋됨 → **키부터 폐기(rotate)** 하고 담임 매니저에게 알린다. 커밋에서 지워도 이력에 남는다<br>오탐 → 그 줄 끝에 `gitleaks:allow` 주석 |
| Security · dependency-review | 경고 | — | 새 npm 의존성의 알려진 취약점 → 고친 버전으로 올리거나 PR 에 이유를 적는다 |
| Docker build | 막음 | `bash scripts/verify.sh docker` | 빌드 실패 → `./gradlew bootJar` 가 로컬에서 되는지 먼저 본다. 새 파일이 이미지에 필요하면 `backend/.dockerignore` 허용 목록에 넣는다<br>`GET /api/v1/csrf` 가 200 이 안 됨(앱이 요청을 못 받음) → 출력된 backend 로그 끝부분을 본다 |
| Workflow lint · actionlint·shellcheck | 막음 | `bash scripts/verify.sh workflows` | YAML·표현식 오타, `run:` 셸 오류 → 메시지대로 |
| Workflow lint · zizmor | 경고 | `bash scripts/verify.sh workflows` | 권한 과다·`persist-credentials`·SHA 미고정 → 워크플로 공통 규칙대로 고친다 |
| CodeQL | GitHub 기본 | — | PR 의 CodeQL 코멘트 표에서 규칙·파일:줄을 보고 고친다. 오탐이면 Security → Code scanning 에서 이유를 적고 Dismiss 한다 |

그래도 모르겠으면 실행 링크를 CI/CD 담당(정민서)에게 보내 주세요.

## 경고 → 차단 전환 조건

- **zizmor**: 팀 워크플로 지적 0건이 2주 유지되면 차단으로 바꾼다. 필수 체크 지정(DEC-021)과 같이 정한다.
- **E2E(PR)**: Issue "E2E 경고 기록" 에 2주 동안 환경 탓(CI 서버 지연 등) 기록이 없으면 PR 에서도 막는 것을 제안한다(DEC-021 과 같이 정한다).
- **dependency-review**: 경고를 쌓아 두지 않고 처리하는 흐름이 잡히면 high 이상부터 막는 것을 제안한다.
- 커버리지(JaCoCo·Vitest)는 참고용이다. 통과 기준으로 쓰지 않는다. 우리 기준은 "인수조건 1줄 = 테스트 1개" 다.
