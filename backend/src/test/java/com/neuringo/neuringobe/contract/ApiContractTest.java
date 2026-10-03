package com.neuringo.neuringobe.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FE ↔ BE API 명세(contracts/api-v1.json)를 실제 API 로 확인한다. 프론트 apiContract.test.ts 는 같은 파일로 프론트 테스트의
 * MSW 가짜 서버를 확인한다.
 *
 * <ul>
 *   <li>응답 모양: 키 목록이 정확히 같고, 값 형식(UUID·문자열)과 고정 값(오류 코드·상태·경로·메시지)이 같다.
 *   <li>프론트 api.ts 는 이 모양(성공은 data·meta, 실패는 error 의 code·message)을 믿고 화면을 그린다. 백엔드만 바뀌면 여기서, MSW 만
 *       바뀌면 프론트에서 깨진다.
 * </ul>
 *
 * <p>규칙은 contracts/README.md. API 명세를 바꿀 때는 파일과 양쪽 코드를 같은 PR 에서 고친다.
 */
@LocalProfileIntegrationTest
class ApiContractTest {

    private static final Path CONTRACT = Path.of("..", "contracts", "api-v1.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    @Autowired private MockMvcTester mvc;

    @Autowired private TestFixtures fixtures;

    static Stream<Arguments> scenarios() {
        JsonNode scenarios = readContract().get("scenarios");
        return scenarios.values().stream()
                .map(scenario -> Arguments.of(scenario.get("name").asString(), scenario));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void backendAnswersAsTheSharedContractSays(String name, JsonNode scenario) {
        TestFixtures.Instructor instructor = fixtures.instructor();
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        UUID childId = fixtures.createChild(classId, TestFixtures.NAMESAKE);
        TestFixtures.Credentials fresh = TestFixtures.newCredentials();
        Map<String, String> values = new HashMap<>();
        values.put("classId", classId.toString());
        values.put("unknownClassId", UUID.randomUUID().toString());
        values.put("malformedId", "not-a-uuid");
        values.put("instructorId", instructor.userId().toString());
        values.put("instructorEmail", instructor.email());
        values.put("instructorPassword", instructor.password());
        values.put("newEmail", fresh.email());
        values.put("newPassword", fresh.password());
        values.put("childId", childId.toString());
        values.put("requestKey", UUID.randomUUID().toString());
        // 다른 강사는 가입·로그인이 느려서(BCrypt) 쓰는 시나리오에서만 만든다.
        String text = scenario.toString();
        if (text.contains("{otherClassId}") || text.contains("{otherChildId}")) {
            TestFixtures.Instructor other = fixtures.signUpInstructor();
            UUID otherClassId = fixtures.createClassroom(other, TestFixtures.CLASSROOM_B1);
            values.put("otherClassId", otherClassId.toString());
            values.put(
                    "otherChildId",
                    fixtures.createChild(other, otherClassId, TestFixtures.CHILD_B1_1).toString());
        }

        MvcTestResult result = send(scenario.get("request"), values, instructor);

        JsonNode expected = scenario.get("response");
        assertThat(result.getResponse().getStatus())
                .as("%s — HTTP 상태", name)
                .isEqualTo(expected.get("status").intValue());
        JsonNode expectedBody = expected.get("body");
        if (expectedBody.isString() && "<any>".equals(expectedBody.asString())) {
            return;
        }
        List<String> mismatches = new ArrayList<>();
        match(expectedBody, JSON.readTree(TestFixtures.body(result)), "$", values, mismatches);
        assertThat(mismatches).as("%s — contracts/api-v1.json 과 다른 곳", name).isEmpty();
    }

    private MvcTestResult send(
            JsonNode request, Map<String, String> values, TestFixtures.Instructor instructor) {
        String path = fill(request.get("path").asString(), values);
        MockMvcTester.MockMvcRequestBuilder builder;
        if ("GET".equals(request.get("method").asString())) {
            builder = mvc.get().uri(path);
        } else {
            // 본문 안의 {자리표시자} 도 채운다(가입 이메일 등). JSON 의 괄호 뒤에는 따옴표가 와서 자리표시자로 읽히지 않는다.
            String content =
                    request.has("rawBody")
                            ? request.get("rawBody").asString()
                            : fill(JSON.writeValueAsString(request.get("body")), values);
            builder = mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(content);
            boolean withCsrf = !request.has("csrf") || request.get("csrf").asBoolean();
            if (withCsrf) {
                TestFixtures.CsrfCredentials csrf = fixtures.fetchCsrf();
                builder.header(csrf.headerName(), csrf.token()).cookie(csrf.cookies());
            }
        }
        JsonNode headers = request.get("headers");
        if (headers != null) {
            for (String name : headers.propertyNames()) {
                builder.header(name, fill(headers.get(name).asString(), values));
            }
        }
        return withAuth(builder, request.get("auth"), instructor).exchange();
    }

    /** auth 가 없거나 true 면 로그인한 강사의 토큰, false 면 없음, "invalid" 면 틀린 토큰을 싣는다(contracts/README.md). */
    private static MockMvcTester.MockMvcRequestBuilder withAuth(
            MockMvcTester.MockMvcRequestBuilder builder,
            JsonNode auth,
            TestFixtures.Instructor instructor) {
        if (auth == null || (auth.isBoolean() && auth.asBoolean())) {
            return TestFixtures.bearer(builder, instructor.accessToken());
        }
        if (auth.isBoolean()) {
            return builder;
        }
        if ("invalid".equals(auth.asString())) {
            return TestFixtures.bearer(builder, "invalid-" + UUID.randomUUID());
        }
        throw new IllegalArgumentException("contracts/api-v1.json 의 auth 값을 모른다: " + auth);
    }

    /** contracts/README.md 의 비교 규칙. 틀린 곳을 전부 모아 한 번에 보여 준다. */
    private static void match(
            JsonNode expected,
            JsonNode actual,
            String at,
            Map<String, String> values,
            List<String> mismatches) {
        if (expected.isObject()) {
            if (!actual.isObject()) {
                mismatches.add(at + ": 객체여야 하는데 " + actual);
                return;
            }
            Set<String> expectedKeys = new TreeSet<>(expected.propertyNames());
            Set<String> actualKeys = new TreeSet<>(actual.propertyNames());
            if (!expectedKeys.equals(actualKeys)) {
                mismatches.add(at + ": 키가 " + expectedKeys + " 여야 하는데 " + actualKeys);
            }
            for (String key : expectedKeys) {
                if (actual.has(key)) {
                    match(expected.get(key), actual.get(key), at + "." + key, values, mismatches);
                }
            }
        } else if (expected.isArray()) {
            if (!actual.isArray()) {
                mismatches.add(at + ": 배열이어야 하는데 " + actual);
            } else if (expected.isEmpty()) {
                if (!actual.isEmpty()) {
                    mismatches.add(at + ": 비어 있어야 하는데 " + actual);
                }
            } else if (actual.isEmpty()) {
                mismatches.add(at + ": 항목이 하나 이상 있어야 한다");
            } else {
                for (int i = 0; i < actual.size(); i++) {
                    match(expected.get(0), actual.get(i), at + "[" + i + "]", values, mismatches);
                }
            }
        } else if (expected.isString()) {
            matchString(expected.asString(), actual, at, values, mismatches);
        } else if (!expected.equals(actual)) {
            mismatches.add(at + ": " + expected + " 여야 하는데 " + actual);
        }
    }

    private static void matchString(
            String rule,
            JsonNode actual,
            String at,
            Map<String, String> values,
            List<String> mismatches) {
        boolean ok =
                switch (rule) {
                    case "<any>" -> true;
                    case "<string>" -> actual.isString() && !actual.asString().isEmpty();
                    case "<uuid>" ->
                            actual.isString() && UUID_FORMAT.matcher(actual.asString()).matches();
                    default -> actual.isString() && fill(rule, values).equals(actual.asString());
                };
        if (!ok) {
            mismatches.add(at + ": " + fill(rule, values) + " 여야 하는데 " + actual);
        }
    }

    private static String fill(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException(
                        "contracts/api-v1.json 에 모르는 자리표시자: " + matcher.group());
            }
            matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
        }
        return matcher.appendTail(filled).toString();
    }

    private static JsonNode readContract() {
        try {
            return JSON.readTree(Files.readString(CONTRACT));
        } catch (IOException e) {
            throw new UncheckedIOException("contracts/api-v1.json 을 읽지 못했다: " + CONTRACT, e);
        }
    }
}
