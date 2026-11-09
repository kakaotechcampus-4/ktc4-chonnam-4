package com.neuringo.neuringobe.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildEntryAttemptProperties;
import com.neuringo.neuringobe.child.service.AccessCodeCryptography;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class ChildAccessIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ChildRepository children;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private UserAccountRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessCodeCryptography cryptography;
    @Autowired private ChildAccessCodeService accessCodes;
    @Autowired private ChildEntryAttemptProperties attemptLimit;

    private UUID childId;

    @BeforeEach
    void setUp() {
        childId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        TestInstructors.save(users, "teacher-1");
        classrooms.save(
                new Classroom(
                        classroomId,
                        TestInstructors.id("teacher-1"),
                        "테스트반",
                        ClassroomStatus.ACTIVE));
        children.save(new Child(childId, classroomId, "테스트아동", ChildStatus.ACTIVE));
    }

    @Test
    void replayReturnsOriginalCodeWithoutReactivatingItAfterReissue() throws Exception {
        UUID firstKey = UUID.randomUUID();
        String first = issue(firstKey);
        String replay = issue(firstKey);
        assertThat(replay).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from child_access_code where child_id = ?",
                                Integer.class,
                                childId))
                .isEqualTo(1);

        String second = issue(UUID.randomUUID());
        assertThat(second).isNotEqualTo(first);
        assertThat(issue(firstKey)).isEqualTo(first);

        mvc.perform(
                        post("/api/v1/child-access-sessions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accessCode\":\"" + first + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_ACCESS_CODE"));

        MvcResult entered =
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accessCode\":\"" + second + "\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.childId").value(childId.toString()))
                        .andExpect(jsonPath("$.data.displayName").value("테스트아동"))
                        .andReturn();

        MockHttpSession session = (MockHttpSession) entered.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(-1);
        mvc.perform(get("/api/v1/classrooms").session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/child-access-sessions/current").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void rejectsWrongInstructorAndMissingCsrf() throws Exception {
        mvc.perform(
                        post("/api/v1/children/{childId}/access-codes", childId)
                                .with(TestInstructors.instructor("teacher-2"))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        mvc.perform(
                        post("/api/v1/children/{childId}/access-codes", childId)
                                .with(TestInstructors.instructor("teacher-1"))
                                .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_TOKEN_INVALID"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void retriesCollisionInternallyAndRejectsKeyReuseForAnotherChild() throws Exception {
        UUID firstKey = UUID.randomUUID();
        String first = issue(firstKey);
        UUID anotherChildId = UUID.randomUUID();
        UUID classroomId = children.findById(childId).orElseThrow().getClassId();
        children.save(new Child(anotherChildId, classroomId, "다른 아동", ChildStatus.ACTIVE));

        mvc.perform(
                        post("/api/v1/children/{childId}/access-codes", anotherChildId)
                                .with(TestInstructors.instructor("teacher-1"))
                                .with(csrf())
                                .header("Idempotency-Key", firstKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        UUID collidingKey;
        do {
            collidingKey = UUID.randomUUID();
        } while (!cryptography.deriveCode(anotherChildId, collidingKey, 0).equals(first));

        String second = issueFor(anotherChildId, collidingKey);
        assertThat(second).isNotEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from child_access_code where active = true",
                                Integer.class))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsExpiredCodeAndAllowsNewIssue() throws Exception {
        String expired = issue(UUID.randomUUID());
        jdbc.update(
                "update child_access_code set expires_at = now() - interval '1 second' "
                        + "where child_id = ? and active = true",
                childId);
        mvc.perform(
                        post("/api/v1/child-access-sessions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accessCode\":\"" + expired + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCESS_CODE_EXPIRED"));
        assertThat(issue(UUID.randomUUID())).matches("[0-9]{4}");
    }

    @Test
    void concurrentIssuersCannotGiveSameActiveCodeToDifferentChildren() throws Exception {
        UUID anotherChildId = UUID.randomUUID();
        UUID classroomId = children.findById(childId).orElseThrow().getClassId();
        children.save(new Child(anotherChildId, classroomId, "경합 아동", ChildStatus.ACTIVE));
        UUID firstKey = UUID.randomUUID();
        UUID secondKey;
        String firstCandidate = cryptography.deriveCode(childId, firstKey, 0);
        do {
            secondKey = UUID.randomUUID();
        } while (!cryptography.deriveCode(anotherChildId, secondKey, 0).equals(firstCandidate));
        UUID selectedSecondKey = secondKey;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> first =
                    pool.submit(
                            () ->
                                    accessCodes
                                            .issue(
                                                    childId,
                                                    firstKey,
                                                    TestInstructors.id("teacher-1"))
                                            .response()
                                            .accessCode());
            Future<String> second =
                    pool.submit(
                            () ->
                                    accessCodes
                                            .issue(
                                                    anotherChildId,
                                                    selectedSecondKey,
                                                    TestInstructors.id("teacher-1"))
                                            .response()
                                            .accessCode());
            assertThat(first.get(15, TimeUnit.SECONDS))
                    .isNotEqualTo(second.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void blocksOnlyTheClientThatKeepsTypingWrongCodes() throws Exception {
        String valid = issue(UUID.randomUUID());
        String wrong = unusedCode();
        String guesser = "198.51.100.10";
        for (int i = 0; i < attemptLimit.maxFailures(); i++) {
            enterFrom(guesser, wrong)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_ACCESS_CODE"));
        }

        MvcResult blocked =
                enterFrom(guesser, wrong)
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.error.code").value("ACCESS_CODE_ATTEMPTS_EXCEEDED"))
                        .andExpect(jsonPath("$.error.path").value("/api/v1/child-access-sessions"))
                        .andReturn();
        long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter)
                .isBetween(
                        attemptLimit.blockFor().toSeconds() - 30,
                        attemptLimit.blockFor().toSeconds());

        // 막힌 IP 는 맞는 코드도 받지 않는다(맞을 때까지 계속 찍어 보는 것을 막는다). 다른 IP 는 그대로 들어온다.
        enterFrom(guesser, valid).andExpect(status().isTooManyRequests());
        enterFrom("198.51.100.11", valid)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.childId").value(childId.toString()));
    }

    @Test
    void doesNotCountCorrectEntriesOrMalformedCodesFromOneSchoolAddress() throws Exception {
        String valid = issue(UUID.randomUUID());
        String school = "198.51.100.20";
        for (int i = 0; i < attemptLimit.maxFailures() + 5; i++) {
            enterFrom(school, valid).andExpect(status().isCreated());
            enterFrom(school, "12a4").andExpect(status().isUnprocessableContent());
        }

        enterFrom(school, unusedCode())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_ACCESS_CODE"));
    }

    private ResultActions enterFrom(String client, String code) throws Exception {
        return mvc.perform(
                post("/api/v1/child-access-sessions")
                        .with(csrf())
                        .with(
                                request -> {
                                    request.setRemoteAddr(client);
                                    return request;
                                })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessCode\":\"" + code + "\"}"));
    }

    /** 지금 어느 아동에게도 발급되어 있지 않은 4자리 코드. 다른 테스트가 만든 코드와도 겹치지 않는다. */
    private String unusedCode() {
        for (int n = 0; n < 10_000; n++) {
            String code = String.format("%04d", n);
            Integer active =
                    jdbc.queryForObject(
                            "select count(*) from child_access_code "
                                    + "where code_digest = ? and active = true",
                            Integer.class,
                            cryptography.digest(code));
            if (active != null && active == 0) {
                return code;
            }
        }
        throw new IllegalStateException("비어 있는 입장 코드가 없습니다.");
    }

    private String issue(UUID key) throws Exception {
        return issueFor(childId, key);
    }

    private String issueFor(UUID targetChildId, UUID key) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/children/{childId}/access-codes", targetChildId)
                                        .with(TestInstructors.instructor("teacher-1"))
                                        .with(csrf())
                                        .header("Idempotency-Key", key))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.accessCode").isString())
                        .andExpect(jsonPath("$.data.expiresAt").isString())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(body, "$.data.accessCode");
    }
}
