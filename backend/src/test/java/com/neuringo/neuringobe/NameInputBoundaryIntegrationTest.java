package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 사람이 입력하는 이름의 경계값(VS-002 "학급 생성·아동 실명 등록", VS-001 가입의 강사 이름). 요청 검증은 {@code @NotBlank @Size(max =
 * 100) @NameText} 이고, 학급·아동·강사 세 API 에 같은 입력을 넣어 본다.
 *
 * <ul>
 *   <li>공백뿐인 이름(스페이스·탭·줄바꿈·전각 공백)과 빈 문자열·null·키 없음은 422 이고, 오류는 그 필드를 가리키며, 아무것도 저장되지 않는다.
 *   <li>제어 문자(U+0000 NUL·벨·DEL 등)가 든 이름과, 보이지 않는 문자(NBSP·U+2007·폭 없는 공백·BOM)만 있는 이름도 422 이고 저장되지
 *       않는다. NUL 은 PostgreSQL 이 저장하지 못해 500 이 났고, 보이지 않는 문자만 있는 이름은 빈 이름처럼 저장됐다(#22 의 1번).
 *   <li>길이는 UTF-16 기준 100 까지 받는다. 이모지처럼 두 칸을 쓰는 글자는 50개까지다. 넘으면 422 이고 저장되지 않는다.
 *   <li>마크업·따옴표·SQL 처럼 보이는 문자열도 이름 그대로 저장되고 그대로 돌아온다(변형·실행 없음).
 * </ul>
 *
 * <p>한글 100자·101자는 {@link ClassroomChildIntegrationTest} 에 있다. 짝 없는 서로게이트(예: {@code "ab\uD800"})는
 * 아직 저장되면서 "?" 로 바뀌어 여기서 단정하지 않는다(#22 의 1번에 남은 경우).
 */
@LocalProfileIntegrationTest
class NameInputBoundaryIntegrationTest {

    private static final List<Named<String>> BLANK_NAMES =
            List.of(
                    named("빈 문자열", ""),
                    named("스페이스", " "),
                    named("탭", "\t"),
                    named("줄바꿈", "\n"),
                    named("공백 섞음", " \t\n "),
                    named("전각 공백 U+3000", "　"));

    // 보이지 않는 문자·제어 문자는 소스에 그대로 두면 읽을 수 없고, 포매터가 유니코드 이스케이프를 실제 문자로 바꾼다. 코드 포인트로 만든다.
    private static final String NUL = ch(0x0000);
    private static final String BEL = ch(0x0007);
    private static final String DEL = ch(0x007F);
    private static final String NBSP = ch(0x00A0);
    private static final String FIGURE_SPACE = ch(0x2007);
    private static final String ZERO_WIDTH_SPACE = ch(0x200B);
    private static final String BOM = ch(0xFEFF);

    private static final List<Named<String>> NAMES_WITH_CONTROL_CHARACTERS =
            List.of(
                    named("가운데 NUL U+0000", "김" + NUL + "하늘"),
                    named("NUL 만", NUL),
                    named("끝에 벨 U+0007", "김하늘" + BEL),
                    named("가운데 DEL U+007F", "김" + DEL + "하늘"));

    private static final List<Named<String>> INVISIBLE_ONLY_NAMES =
            List.of(
                    named("NBSP U+00A0", NBSP),
                    named("숫자 폭 공백 U+2007", FIGURE_SPACE),
                    named("폭 없는 공백 U+200B", ZERO_WIDTH_SPACE),
                    named("BOM U+FEFF", BOM),
                    named("보이지 않는 문자 섞음", NBSP + ZERO_WIDTH_SPACE + BOM));

    private static final List<Named<String>> NAMES_WITHIN_LIMIT =
            List.of(
                    named("한 글자", "가"),
                    named("영문 100자", "a".repeat(100)),
                    named("이모지 50개(UTF-16 100)", "😀".repeat(50)));

    private static final List<Named<String>> NAMES_OVER_LIMIT =
            List.of(
                    named("영문 101자", "a".repeat(101)),
                    named("이모지 51개(UTF-16 102)", "😀".repeat(51)));

    private static final List<Named<String>> NAMES_WITH_SPECIAL_CHARACTERS =
            List.of(
                    named("마크업", "<script>alert(1)</script>"),
                    named("SQL 모양", "' OR '1'='1"),
                    named("이모지 섞음", "김하늘 😀"),
                    named("따옴표·역슬래시", "O'Brien \"따옴표\" \\역슬래시"));

    @Autowired private TestFixtures fixtures;

    /** 이름을 받는 세 API. 같은 규칙을 따라야 한다. */
    enum NameField {
        CLASSROOM_NAME("name"),
        CHILD_DISPLAY_NAME("displayName"),
        INSTRUCTOR_NAME("name");

        private final String field;

        NameField(String field) {
            this.field = field;
        }
    }

    static Stream<Arguments> blankNames() {
        return everyField(BLANK_NAMES);
    }

    static Stream<Arguments> namesWithControlCharacters() {
        return everyField(NAMES_WITH_CONTROL_CHARACTERS);
    }

    static Stream<Arguments> invisibleOnlyNames() {
        return everyField(INVISIBLE_ONLY_NAMES);
    }

    static Stream<Arguments> namesWithinLimit() {
        return everyField(NAMES_WITHIN_LIMIT);
    }

    static Stream<Arguments> namesOverLimit() {
        return everyField(NAMES_OVER_LIMIT);
    }

    static Stream<Arguments> namesWithSpecialCharacters() {
        return everyField(NAMES_WITH_SPECIAL_CHARACTERS);
    }

    static Stream<Arguments> missingNameBodies() {
        return Stream.of(NameField.values())
                .flatMap(
                        target ->
                                Stream.of(
                                        Arguments.of(target, named("키 없음", false)),
                                        Arguments.of(target, named("null", true))));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("blankNames")
    void rejectsBlankNamesAndStoresNothing(NameField target, String name) {
        assertRejectedAndNotStored(target, name);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("namesWithControlCharacters")
    void rejectsNamesWithControlCharactersAndStoresNothing(NameField target, String name) {
        assertRejectedAndNotStored(target, name);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("invisibleOnlyNames")
    void rejectsInvisibleOnlyNamesAndStoresNothing(NameField target, String name) {
        assertRejectedAndNotStored(target, name);
    }

    @ParameterizedTest(name = "{0} 이름 {1}")
    @MethodSource("missingNameBodies")
    void rejectsMissingOrNullNames(NameField target, boolean explicitNull) {
        Endpoint endpoint = endpointFor(target);
        Map<String, Object> body = new HashMap<>();
        if (explicitNull) {
            body.put(target.field, null);
        }

        assertRejectedOn(endpoint.post(body), target);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("namesWithinLimit")
    void storesNamesUpToTheLimitUnchanged(NameField target, String name) {
        Endpoint endpoint = endpointFor(target);

        MvcTestResult result = endpoint.post(Map.of(target.field, name));

        assertThat(result).hasStatusOk();
        assertThat(endpoint.storedNames()).containsExactly(name);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("namesOverLimit")
    void rejectsNamesOverTheLimit(NameField target, String name) {
        assertRejectedAndNotStored(target, name);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("namesWithSpecialCharacters")
    void storesSpecialCharactersAsPlainText(NameField target, String name) {
        Endpoint endpoint = endpointFor(target);

        MvcTestResult result = endpoint.post(Map.of(target.field, name));

        assertThat(result).hasStatusOk();
        assertThat(endpoint.storedNames()).containsExactly(name);
    }

    private void assertRejectedAndNotStored(NameField target, String name) {
        Endpoint endpoint = endpointFor(target);

        MvcTestResult result = endpoint.post(Map.of(target.field, name));

        assertRejectedOn(result, target);
        endpoint.assertStoredNoName(name);
    }

    private static void assertRejectedOn(MvcTestResult result, NameField target) {
        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[*].field")
                .asArray()
                .isNotEmpty()
                .containsOnly(target.field);
    }

    private Endpoint endpointFor(NameField target) {
        return switch (target) {
            case CLASSROOM_NAME -> new ClassroomEndpoint();
            case CHILD_DISPLAY_NAME ->
                    new ChildEndpoint(fixtures.createClassroom(TestFixtures.CLASSROOM_A1));
            case INSTRUCTOR_NAME -> new InstructorEndpoint();
        };
    }

    private static String ch(int codePoint) {
        return Character.toString(codePoint);
    }

    private static Stream<Arguments> everyField(List<Named<String>> names) {
        return Stream.of(NameField.values())
                .flatMap(target -> names.stream().map(name -> Arguments.of(target, name)));
    }

    /** 이름을 보내고, 저장된 이름을 다시 읽는다. */
    private interface Endpoint {

        /** 이름 필드가 든 본문을 보낸다. 이름 말고 꼭 필요한 값(가입의 이메일·비밀번호)은 엔드포인트가 채운다. */
        MvcTestResult post(Map<String, Object> nameFields);

        List<String> storedNames();

        void assertStoredNoName(String name);
    }

    /** 학급: 새로 만든 학급 하나만 보려고 응답의 classId 로 다시 조회한다. 목록은 다른 테스트 데이터와 섞여 있다. */
    private final class ClassroomEndpoint implements Endpoint {

        private UUID created;

        @Override
        public MvcTestResult post(Map<String, Object> nameFields) {
            MvcTestResult result = fixtures.postJson("/api/v1/classrooms", nameFields);
            if (result.getResponse().getStatus() == 200) {
                created =
                        UUID.fromString(JsonPath.read(TestFixtures.body(result), "$.data.classId"));
            }
            return result;
        }

        @Override
        public List<String> storedNames() {
            String json = TestFixtures.body(fixtures.get("/api/v1/classrooms/{classId}", created));
            return List.of(JsonPath.<String>read(json, "$.data.name"));
        }

        @Override
        public void assertStoredNoName(String name) {
            String json = TestFixtures.body(fixtures.get("/api/v1/classrooms"));
            assertThat(JsonPath.<List<String>>read(json, "$.data[*].name")).doesNotContain(name);
        }
    }

    /** 아동: 테스트마다 새 학급을 만들어 그 학급의 아동 목록을 본다. */
    private final class ChildEndpoint implements Endpoint {

        private final UUID classId;

        private ChildEndpoint(UUID classId) {
            this.classId = classId;
        }

        @Override
        public MvcTestResult post(Map<String, Object> nameFields) {
            return fixtures.postJson("/api/v1/classrooms/" + classId + "/children", nameFields);
        }

        @Override
        public List<String> storedNames() {
            String json =
                    TestFixtures.body(
                            fixtures.get("/api/v1/classrooms/{classId}/children", classId));
            return JsonPath.read(json, "$.data[*].displayName");
        }

        @Override
        public void assertStoredNoName(String name) {
            assertThat(storedNames()).as("거절된 이름이 저장되면 안 된다").isEmpty();
        }
    }

    /** 강사: 테스트마다 새 이메일로 가입하고, 저장된 이름은 그 계정으로 로그인해 본인 정보에서 읽는다. */
    private final class InstructorEndpoint implements Endpoint {

        private final TestFixtures.Credentials credentials = TestFixtures.newCredentials();

        @Override
        public MvcTestResult post(Map<String, Object> nameFields) {
            Map<String, Object> body = new HashMap<>(nameFields);
            body.put("email", credentials.email());
            body.put("password", credentials.password());
            return fixtures.postPublicJson("/api/v1/users", body);
        }

        @Override
        public List<String> storedNames() {
            String token = fixtures.login(credentials.email(), credentials.password());
            TestFixtures.Instructor me =
                    new TestFixtures.Instructor(
                            null, credentials.email(), credentials.password(), token);
            String json = TestFixtures.body(fixtures.get(me, "/api/v1/auth/session"));
            return List.of(JsonPath.<String>read(json, "$.data.user.name"));
        }

        @Override
        public void assertStoredNoName(String name) {
            assertThat(fixtures.loginRequest(credentials.email(), credentials.password()))
                    .as("거절된 가입으로 계정이 생기면 안 된다")
                    .hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }
}
