package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 아동 이름과 강사 이메일·비밀번호가 서버 로그와 오류 응답에 남지 않는다(VS-002 "아동 실명 외 인적 정보는 수집하지 않고 로그·AI 요청에도 포함하지 않는다", 06
 * DoD "민감 데이터가 불필요하게 응답·일반 로그에 남지 않는다").
 *
 * <p>테스트마다 다른 표시(marker)를 이름·이메일·비밀번호에 붙여 요청을 보내고, 그동안 나온 로그 전체에서 찾는다. E2E 마커 스캔(scripts/e2e.sh
 * scan)의 백엔드 단위판이라 PR 마다 빠르게 돈다. local 프로필(com.neuringo DEBUG, org.hibernate.SQL DEBUG)이라 로그가 가장 많은
 * 설정에서 본다.
 *
 * <p>성공뿐 아니라 검증 실패(422)·없는 학급(404)·깨진 JSON(400)·로그인 실패(401)·이메일 중복(409)처럼 예외가 나는 길도 본다. 예외 메시지에 요청
 * 값이 섞이기 쉬운 곳이다.
 */
@LocalProfileIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LogPrivacyIntegrationTest {

    @Autowired private TestFixtures fixtures;

    static Stream<Arguments> requestsCarryingAName() {
        return Stream.of(
                scenario("학급 생성", (t, name) -> t.fixtures.createClassroom(name)),
                scenario(
                        "아동 등록과 목록 조회",
                        (t, name) -> {
                            UUID classId = t.fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
                            t.fixtures.createChild(classId, name);
                            t.fixtures.get("/api/v1/classrooms/{classId}/children", classId);
                        }),
                scenario(
                        "너무 긴 이름(422)",
                        (t, name) -> {
                            UUID classId = t.fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
                            t.fixtures.postJson(
                                    "/api/v1/classrooms/" + classId + "/children",
                                    Map.of("displayName", name + "가".repeat(100)));
                        }),
                scenario(
                        "없는 학급에 등록(404)",
                        (t, name) ->
                                t.fixtures.postJson(
                                        "/api/v1/classrooms/" + UUID.randomUUID() + "/children",
                                        Map.of("displayName", name))),
                scenario(
                        "다른 강사의 학급에 등록(404)",
                        (t, name) -> {
                            TestFixtures.Instructor other = t.fixtures.signUpInstructor();
                            UUID theirs =
                                    t.fixtures.createClassroom(other, TestFixtures.CLASSROOM_A1);
                            t.fixtures.postJson(
                                    "/api/v1/classrooms/" + theirs + "/children",
                                    Map.of("displayName", name));
                        }),
                scenario(
                        "깨진 JSON(400)",
                        (t, name) ->
                                t.fixtures.postJsonText(
                                        "/api/v1/classrooms", "{\"name\":\"" + name + "\",")),
                scenario(
                        "문자열이 아닌 이름(400)",
                        (t, name) ->
                                t.fixtures.postJsonText(
                                        "/api/v1/classrooms",
                                        "{\"name\":{\"value\":\"" + name + "\"}}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestsCarryingAName")
    void keepsChildNamesOutOfServerLogs(
            String label,
            BiConsumer<LogPrivacyIntegrationTest, String> request,
            CapturedOutput output) {
        String marker = marker();

        request.accept(this, TestFixtures.NAMESAKE + " " + marker);

        assertThat(output.getAll()).as(label).doesNotContain(marker);
    }

    /** marker 를 이메일 이름 부분과 비밀번호에 넣어 보낸다. 돌려준 응답 본문에도 marker 가 없어야 한다. */
    static Stream<Arguments> requestsCarryingCredentials() {
        return Stream.of(
                account(
                        "가입",
                        (t, marker) ->
                                t.fixtures.signUpRequest(
                                        email(marker),
                                        password(marker),
                                        TestFixtures.INSTRUCTOR_NAME)),
                account(
                        "같은 이메일로 다시 가입(409)",
                        (t, marker) -> {
                            t.fixtures.signUpRequest(
                                    email(marker), password(marker), TestFixtures.INSTRUCTOR_NAME);
                            return t.fixtures.signUpRequest(
                                    email(marker), password(marker), TestFixtures.INSTRUCTOR_NAME);
                        }),
                account(
                        "로그인",
                        (t, marker) -> {
                            t.fixtures.signUpRequest(
                                    email(marker), password(marker), TestFixtures.INSTRUCTOR_NAME);
                            return t.fixtures.loginRequest(email(marker), password(marker));
                        }),
                account(
                        "틀린 비밀번호(401)",
                        (t, marker) -> {
                            t.fixtures.signUpRequest(
                                    email(marker), password(marker), TestFixtures.INSTRUCTOR_NAME);
                            return t.fixtures.loginRequest(email(marker), "wrong-" + marker);
                        }),
                account(
                        "없는 이메일로 로그인(401)",
                        (t, marker) -> t.fixtures.loginRequest(email(marker), password(marker))),
                account(
                        "제어 문자가 든 이메일로 로그인(401)",
                        (t, marker) ->
                                t.fixtures.loginRequest(
                                        marker + "\u0000@example.com", password(marker))),
                account(
                        "이메일 형식 오류(422)",
                        (t, marker) ->
                                t.fixtures.signUpRequest(
                                        marker + "-not-an-email",
                                        password(marker),
                                        TestFixtures.INSTRUCTOR_NAME)),
                account(
                        "너무 긴 비밀번호(422)",
                        (t, marker) ->
                                t.fixtures.signUpRequest(
                                        email(marker),
                                        password(marker) + "가".repeat(30),
                                        TestFixtures.INSTRUCTOR_NAME)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestsCarryingCredentials")
    void keepsInstructorEmailAndPasswordOutOfLogsAndResponses(
            String label,
            BiFunction<LogPrivacyIntegrationTest, String, MvcTestResult> request,
            CapturedOutput output) {
        String marker = marker();

        MvcTestResult result = request.apply(this, marker);

        assertThat(output.getAll()).as("%s — 로그", label).doesNotContain(marker);
        if (result.getResponse().getStatus() >= 400) {
            assertThat(TestFixtures.body(result)).as("%s — 오류 응답", label).doesNotContain(marker);
        }
    }

    @Test
    void validationErrorsDoNotEchoTheRejectedName() {
        String marker = marker();
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);

        MvcTestResult result =
                fixtures.postJson(
                        "/api/v1/classrooms/" + classId + "/children",
                        Map.of("displayName", marker + "가".repeat(100)));

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(TestFixtures.body(result)).doesNotContain(marker);
    }

    private static String marker() {
        return "pii-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static String email(String marker) {
        return marker + "@example.com";
    }

    private static String password(String marker) {
        return "pw-" + marker;
    }

    private static Arguments scenario(
            String label, BiConsumer<LogPrivacyIntegrationTest, String> request) {
        return Arguments.of(label, request);
    }

    private static Arguments account(
            String label, BiFunction<LogPrivacyIntegrationTest, String, MvcTestResult> request) {
        return Arguments.of(label, request);
    }
}
