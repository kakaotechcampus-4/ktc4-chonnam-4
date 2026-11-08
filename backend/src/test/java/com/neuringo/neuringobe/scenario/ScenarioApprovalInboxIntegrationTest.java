package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인 대기함(GET /api/v1/scenario-approvals). 퀴즈로 난이도가 정해졌고 시나리오가 없는 내 학급의 최초 결합 활동을 모으고, 서버에 남은 검토가
 * 있으면 IN_REVIEW, 없으면 WAITING 으로 보인다.
 */
@LocalProfileIntegrationTest
@ExtendWith(ScenarioPoolTestIsolation.class)
class ScenarioApprovalInboxIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private TestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @MockitoBean private LlmProvider llm;

    @BeforeEach
    void generatedScenario() throws Exception {
        String content =
                new ClassPathResource("scenario/generation-valid-l2.json")
                        .getContentAsString(StandardCharsets.UTF_8);
        doAnswer(
                        call -> {
                            LlmRequest request = call.getArgument(0);
                            return new AiCallResult.Success<>(
                                    new LlmCompletion(content, "fake", "fixture", "stop", 10, 20),
                                    new AiCallMetadata(
                                            request.traceContext().requestId(),
                                            request.operation(),
                                            "fake",
                                            "fixture",
                                            request.promptVersion(),
                                            request.responseSchemaVersion(),
                                            request.policyVersion(),
                                            5,
                                            1,
                                            10,
                                            20,
                                            "stop"));
                        })
                .when(llm)
                .complete(any());
    }

    @Test
    void showsWaitingThenInReviewThenLeavesOnceConfirmed() throws Exception {
        var owner = fixtures.signUpInstructor();
        UUID classroom = fixtures.createClassroom(owner, "대기함 학급");
        UUID child = fixtures.createChild(owner, classroom, "대기함 아동");
        // 최초 결합 활동은 아동당 하나라 퀴즈 전 활동은 다른 아동에게 둔다.
        UUID beforeQuiz =
                firstActivity(owner, fixtures.createChild(owner, classroom, "퀴즈 전 아동"), false);
        UUID activity = firstActivity(owner, child, true);

        // 퀴즈를 마치지 않은 활동은 아직 기다리는 활동이 아니다.
        assertThat(row(owner, beforeQuiz)).isEmpty();
        assertThat(row(owner, activity).orElseThrow())
                .containsEntry("childName", "대기함 아동")
                .containsEntry("className", "대기함 학급")
                .containsEntry("goalTitle", "차례를 기다려요")
                .containsEntry("scenarioLevel", "L2")
                .containsEntry("state", "WAITING");

        var started =
                data(
                        send(
                                owner,
                                activity,
                                HttpMethod.POST,
                                "/scenario-review",
                                Map.of("action", "START_OR_RESUME")));
        assertThat(row(owner, activity).orElseThrow()).containsEntry("state", "IN_REVIEW");

        send(
                owner,
                activity,
                HttpMethod.DELETE,
                "/scenario-review",
                Map.of("reviewId", started.get("reviewId"), "revision", started.get("revision")));
        assertThat(row(owner, activity).orElseThrow()).containsEntry("state", "WAITING");

        var restarted =
                data(
                        send(
                                owner,
                                activity,
                                HttpMethod.POST,
                                "/scenario-review",
                                Map.of("action", "START_OR_RESUME")));
        @SuppressWarnings("unchecked")
        var scenario = (Map<String, Object>) restarted.get("scenario");
        send(
                owner,
                activity,
                HttpMethod.PUT,
                "/scenario",
                Map.of(
                        "reviewId", restarted.get("reviewId"),
                        "revision", restarted.get("revision"),
                        "scenarioId", scenario.get("scenarioId"),
                        "scenarioVersion", scenario.get("scenarioVersion")));
        assertThat(row(owner, activity)).isEmpty();
    }

    @Test
    void otherInstructorDoesNotSeeTheActivity() throws Exception {
        var owner = fixtures.signUpInstructor();
        var other = fixtures.signUpInstructor();
        UUID classroom = fixtures.createClassroom(owner, "대기함 학급");
        UUID activity =
                firstActivity(owner, fixtures.createChild(owner, classroom, "대기함 아동"), true);

        assertThat(row(owner, activity)).isPresent();
        assertThat(row(other, activity)).isEmpty();
    }

    /** 최초 결합 활동 하나. quizDone 이면 퀴즈로 난이도가 정해진 상태(quiz_result)까지 만든다. */
    private UUID firstActivity(TestFixtures.Instructor owner, UUID child, boolean quizDone) {
        UUID goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO learning_goal (goal_id,child_id,instructor_id,title,content_hash,created_at) VALUES (?,?,?,'차례를 기다려요',?,now())",
                goal,
                child,
                owner.userId(),
                goal.toString().replace("-", "").repeat(2));
        jdbc.update(
                "INSERT INTO activity (activity_id,child_id,goal_id,activity_type,initial_support_level,difficulty_fallback_applied,assigned_at) VALUES (?,?,?,'FIRST_COMBINED','S1',false,now())",
                activity,
                child,
                goal);
        if (quizDone) {
            jdbc.update(
                    "INSERT INTO quiz_result (activity_id,valid_question_count,correct_question_count,overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) VALUES (?,10,5,0.5,0,0,'L2','S1',false,true,'INITIAL_QUIZ_V2',now())",
                    activity);
        }
        return activity;
    }

    private Optional<Map<String, Object>> row(TestFixtures.Instructor instructor, UUID activityId)
            throws Exception {
        String body =
                mvc.perform(
                                get("/api/v1/scenario-approvals")
                                        .header(
                                                "Authorization",
                                                "Bearer " + instructor.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        List<Map<String, Object>> rows =
                JsonPath.read(body, "$.data[?(@.activityId == '" + activityId + "')]");
        return rows.stream().findFirst();
    }

    private ResultActions send(
            TestFixtures.Instructor owner,
            UUID activity,
            HttpMethod method,
            String path,
            Object body)
            throws Exception {
        var csrf = fixtures.fetchCsrf();
        return mvc.perform(
                        request(method, "/api/v1/activities/" + activity + path)
                                .header("Authorization", "Bearer " + owner.accessToken())
                                .cookie(csrf.cookies())
                                .header(csrf.headerName(), csrf.token())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResultActions result) throws Exception {
        return (Map<String, Object>)
                json.readValue(result.andReturn().getResponse().getContentAsString(), Map.class)
                        .get("data");
    }
}
