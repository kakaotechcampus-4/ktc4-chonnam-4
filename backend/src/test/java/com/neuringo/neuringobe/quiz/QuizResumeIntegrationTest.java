package com.neuringo.neuringobe.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.quiz.domain.ExpressionMatchResult;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.dto.PutQuizAttemptRequest;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import com.neuringo.neuringobe.quiz.service.QuizAttemptService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@LocalProfileIntegrationTest
class QuizResumeIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TestFixtures fixtures;
    @Autowired JsonMapper json;
    @Autowired QuizAttemptService attempts;

    @ParameterizedTest
    @CsvSource({
        "FIRST_COMBINED, PAUSED", "FIRST_COMBINED, RECOVERY_NEEDED",
        "SELF_EMOTION_QUIZ, PAUSED", "SELF_EMOTION_QUIZ, RECOVERY_NEEDED",
        "OTHER_EMOTION_QUIZ, PAUSED", "OTHER_EMOTION_QUIZ, RECOVERY_NEEDED"
    })
    void aNewAnswerResumesAValidQuizWithoutResettingItsOriginalStartTime(
            ActivityType type, String state) throws Exception {
        var f = fixture(type, state);
        var started = activity(f).get("started_at");
        answer(f, "슬픔", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("FINALIZED"));
        assertThat(activity(f))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("started_at", started);
        assertThat(attemptCount(f)).isEqualTo(1);
        assertThat(resultCount(f)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"PAUSED, HINT", "RECOVERY_NEEDED, HINT", "PAUSED, FINAL", "RECOVERY_NEEDED, FINAL"})
    void aNewHintOrFinalAnswerResumesTheExistingDraft(String state, String operation)
            throws Exception {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        answer(f, "기쁨", null).andExpect(status().isCreated());
        if (operation.equals("FINAL")) hint(f, UUID.randomUUID()).andExpect(status().isCreated());
        setState(f, state);
        var before = attempt(f);
        if (operation.equals("HINT")) {
            hint(f, UUID.randomUUID())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.hintOrder").value(1));
            assertThat(attempt(f)).isEqualTo(before);
        } else {
            answer(f, "기쁨", "슬픔")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedAfterHint").value(true));
            assertThat(attempt(f))
                    .containsEntry("first_response", "기쁨")
                    .containsEntry("final_response", "슬픔");
        }
        assertThat(activity(f).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(attemptCount(f)).isEqualTo(1);
        assertThat(hintCount(f)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "PAUSED, DRAFT",
        "RECOVERY_NEEDED, DRAFT",
        "PAUSED, FINAL",
        "RECOVERY_NEEDED, FINAL",
        "PAUSED, HINT",
        "RECOVERY_NEEDED, HINT",
        "COMPLETED, FINAL",
        "COMPLETED, HINT"
    })
    void replayReturnsTheSavedResultWithoutResumingOrAddingData(String state, String kind)
            throws Exception {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        String first = kind.equals("FINAL") ? "슬픔" : "기쁨";
        var original = answer(f, first, null).andExpect(status().isCreated());
        UUID key = UUID.randomUUID();
        if (kind.equals("HINT")) original = hint(f, key).andExpect(status().isCreated());
        Object expected =
                JsonPath.read(original.andReturn().getResponse().getContentAsString(), "$.data");
        setState(f, state);
        var activityBefore = activity(f);
        var attemptBefore = attempt(f);
        int hintCount = hintCount(f);
        var replay =
                kind.equals("HINT")
                        ? hint(f, key).andExpect(status().isCreated())
                        : answer(f, first, null).andExpect(status().isOk());
        Object actual =
                JsonPath.read(replay.andReturn().getResponse().getContentAsString(), "$.data");
        assertThat(actual).isEqualTo(expected);
        assertThat(activity(f)).isEqualTo(activityBefore);
        assertThat(attempt(f)).isEqualTo(attemptBefore);
        assertThat(hintCount(f)).isEqualTo(hintCount);
        assertThat(resultCount(f)).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "ANSWER, MISSING",
        "HINT, MISSING",
        "ANSWER, OTHER_VERSION",
        "HINT, OTHER_VERSION",
        "ANSWER, RESULT",
        "HINT, RESULT",
        "ANSWER, COMPLETED",
        "HINT, COMPLETED"
    })
    void invalidPreparationOrACompletedActivityRejectsNewWrites(String operation, String invalid)
            throws Exception {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        if (operation.equals("HINT")) answer(f, "기쁨", null).andExpect(status().isCreated());
        setState(f, invalid.equals("COMPLETED") ? "COMPLETED" : "RECOVERY_NEEDED");
        switch (invalid) {
            case "MISSING" ->
                    jdbc.update(
                            "delete from activity_quiz where activity_id=? and question_order=3",
                            f.activity());
            case "OTHER_VERSION" ->
                    jdbc.update(
                            "update activity_quiz set item_version=2 where activity_id=? and question_order=3",
                            f.activity());
            case "RESULT" -> {
                jdbc.update(
                        "update activity set initial_support_level='S1' where activity_id=?",
                        f.activity());
                jdbc.update(
                        "insert into quiz_result(activity_id,valid_question_count,correct_question_count,overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,initial_difficulty_used,policy_version,snapshotted_at) values(?,3,2,0.666667,0,0,'L2','S1',false,true,'INITIAL_QUIZ_V2',now())",
                        f.activity());
            }
            default -> {}
        }
        var before = activity(f);
        int attemptCount = attemptCount(f);
        int hintCount = hintCount(f);
        var response =
                operation.equals("HINT") ? hint(f, UUID.randomUUID()) : answer(f, "슬픔", null);
        response.andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUIZ_ACTIVITY_NOT_READY"));
        assertThat(activity(f)).isEqualTo(before);
        assertThat(attemptCount(f)).isEqualTo(attemptCount);
        assertThat(hintCount(f)).isEqualTo(hintCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "RECOVERY_NEEDED"})
    void readsDoNotResumeOrFinalizeTheDraft(String state) throws Exception {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        answer(f, "기쁨", null).andExpect(status().isCreated());
        setState(f, state);
        var before = activity(f);
        var saved = attempt(f);
        mvc.perform(get("/api/v1/children/{id}/activities", f.child()).session(session(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nextAction").value("QUIZ"));
        mvc.perform(get("/api/v1/activities/{id}/quiz-items", f.activity()).session(session(f)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/activity-quiz-items/{id}/attempt", f.target()).session(session(f)))
                .andExpect(status().isOk());
        assertThat(activity(f)).isEqualTo(before);
        assertThat(attempt(f)).isEqualTo(saved);
        assertThat(hintCount(f)).isZero();
        assertThat(resultCount(f)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANSWER", "HINT"})
    void aFailedInsertRollsBackResumeAndAllowsTheSameRequestToRetry(String operation) {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        if (operation.equals("HINT")) attempts.put(f.target(), request(f, "기쁨", null), auth(f));
        setState(f, "PAUSED");
        var before = activity(f);
        UUID key = UUID.randomUUID();
        String constraint = "test_quiz_resume_" + UUID.randomUUID().toString().replace("-", "");
        String table = operation.equals("HINT") ? "quiz_hint" : "quiz_attempt";
        String column = operation.equals("HINT") ? "attempt_id" : "activity_quiz_id";
        UUID prohibited =
                operation.equals("HINT") ? (UUID) attempt(f).get("attempt_id") : f.target();
        jdbc.execute(
                "alter table "
                        + table
                        + " add constraint "
                        + constraint
                        + " check ("
                        + column
                        + " <> '"
                        + prohibited
                        + "'::uuid)");
        try {
            assertThatThrownBy(
                            () -> {
                                if (operation.equals("HINT"))
                                    attempts.issueHint(f.target(), key, auth(f));
                                else attempts.put(f.target(), request(f, "슬픔", null), auth(f));
                            })
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(activity(f)).isEqualTo(before);
            assertThat(hintCount(f)).isZero();
            assertThat(attemptCount(f)).isEqualTo(operation.equals("HINT") ? 1 : 0);
            assertThat(resultCount(f)).isZero();
        } finally {
            jdbc.execute("alter table " + table + " drop constraint " + constraint);
        }
        if (operation.equals("HINT"))
            assertThat(attempts.issueHint(f.target(), key, auth(f)).hintOrder()).isEqualTo(1);
        else
            assertThat(attempts.put(f.target(), request(f, "슬픔", null), auth(f)).created())
                    .isTrue();
        assertThat(activity(f).get("status")).isEqualTo("IN_PROGRESS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIRST", "FINAL"})
    void anInvalidAnswerDoesNotReleaseTheStoppedState(String stage) throws Exception {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        if (stage.equals("FINAL")) answer(f, "기쁨", null).andExpect(status().isCreated());
        setState(f, "PAUSED");
        var before = activity(f);
        var response = stage.equals("FINAL") ? answer(f, "기쁨", "슬픔") : answer(f, "없는 선택지", null);
        response.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUIZ_RESULT"));
        assertThat(activity(f)).isEqualTo(before);
        assertThat(attemptCount(f)).isEqualTo(stage.equals("FINAL") ? 1 : 0);
        assertThat(hintCount(f)).isZero();
        assertThat(resultCount(f)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIRST_COMBINED", "SELF_EMOTION_QUIZ", "OTHER_EMOTION_QUIZ"})
    void theLastAnswerResumesAndThenAppliesTheExistingCompletionPolicy(ActivityType type)
            throws Exception {
        var f = fixture(type, "IN_PROGRESS");
        for (UUID assignment : f.assignments()) {
            if (!assignment.equals(f.target()))
                jdbc.update(
                        "insert into quiz_attempt(attempt_id,activity_quiz_id,first_response,first_response_correct,final_response,item_correct,status,responded_at,finalized_at) values(?,?,'슬픔',true,'슬픔',true,'FINALIZED',now(),now())",
                        UUID.randomUUID(),
                        assignment);
        }
        setState(f, "RECOVERY_NEEDED");
        answer(f, "슬픔", null).andExpect(status().isCreated());
        assertThat(activity(f).get("status"))
                .isEqualTo(type == ActivityType.FIRST_COMBINED ? "IN_PROGRESS" : "COMPLETED");
        assertThat(resultCount(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity()))
                .isEqualTo(type == ActivityType.FIRST_COMBINED ? 0 : 1);
        var result =
                jdbc.queryForMap("select * from quiz_result where activity_id=?", f.activity());
        var completed = activity(f);
        answer(f, "슬픔", null).andExpect(status().isOk());
        assertThat(activity(f)).isEqualTo(completed);
        assertThat(jdbc.queryForMap("select * from quiz_result where activity_id=?", f.activity()))
                .isEqualTo(result);
    }

    @Test
    void anInactiveChildCannotResumeByAnsweringOrRequestingAHint() throws Exception {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        answer(f, "기쁨", null).andExpect(status().isCreated());
        setState(f, "PAUSED");
        jdbc.update("update child set status='PAUSED' where child_id=?", f.child());
        answer(f, "기쁨", "기쁨").andExpect(status().isNotFound());
        hint(f, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(activity(f).get("status")).isEqualTo("PAUSED");
        assertThat(
                        jdbc.queryForObject(
                                "select status from child where child_id=?",
                                String.class,
                                f.child()))
                .isEqualTo("PAUSED");
        assertThat(hintCount(f)).isZero();
    }

    @Test
    void concurrentRequestsForTheSameHintResumeAndIssueOnlyOneHint() throws Exception {
        var f = fixture(ActivityType.FIRST_COMBINED, "IN_PROGRESS");
        attempts.put(f.target(), request(f, "기쁨", null), auth(f));
        setState(f, "RECOVERY_NEEDED");
        UUID key = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!go.await(10, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "힌트 요청 대기 시간 초과");
                                                        return attempts.issueHint(
                                                                f.target(), key, auth(f));
                                                    }))
                            .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(tasks.getFirst().get(15, TimeUnit.SECONDS))
                    .isEqualTo(tasks.getLast().get(15, TimeUnit.SECONDS));
        }
        assertThat(activity(f).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(hintCount(f)).isEqualTo(1);
    }

    private ResultActions answer(Fixture f, String first, String last) throws Exception {
        var csrf = fixtures.fetchCsrf();
        return mvc.perform(
                put("/api/v1/activity-quiz-items/{id}/attempt", f.target())
                        .session(session(f))
                        .header(csrf.headerName(), csrf.token())
                        .cookie(csrf.cookies())
                        .contentType("application/json")
                        .content(json.writeValueAsString(request(f, first, last))));
    }

    private ResultActions hint(Fixture f, UUID key) throws Exception {
        var csrf = fixtures.fetchCsrf();
        return mvc.perform(
                post("/api/v1/activity-quiz-items/{id}/hints", f.target())
                        .session(session(f))
                        .header(csrf.headerName(), csrf.token())
                        .cookie(csrf.cookies())
                        .header("Idempotency-Key", key));
    }

    private PutQuizAttemptRequest request(Fixture f, String first, String last) {
        boolean self = f.type() == ActivityType.SELF_EMOTION_QUIZ;
        return new PutQuizAttemptRequest(
                first,
                self ? ExpressionMatchResult.MATCH : null,
                self ? "합성 카메라" : null,
                last,
                null);
    }

    private MockHttpSession session(Fixture f) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth(f));
        var session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private Authentication auth(Fixture f) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new ChildPrincipal(f.child()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
    }

    private void setState(Fixture f, String state) {
        jdbc.update("update activity set status=? where activity_id=?", state, f.activity());
    }

    private Map<String, Object> activity(Fixture f) {
        return jdbc.queryForMap("select * from activity where activity_id=?", f.activity());
    }

    private Map<String, Object> attempt(Fixture f) {
        return jdbc.queryForMap("select * from quiz_attempt where activity_quiz_id=?", f.target());
    }

    private int attemptCount(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from quiz_attempt t join activity_quiz q using(activity_quiz_id) where q.activity_id=?",
                Integer.class,
                f.activity());
    }

    private int hintCount(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from quiz_hint h join quiz_attempt t using(attempt_id) join activity_quiz q using(activity_quiz_id) where q.activity_id=?",
                Integer.class,
                f.activity());
    }

    private int resultCount(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from quiz_result where activity_id=?",
                Integer.class,
                f.activity());
    }

    private record Fixture(
            UUID child, UUID activity, UUID target, ActivityType type, List<UUID> assignments) {}

    private Fixture fixture(ActivityType type, String state) {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                activity = UUID.randomUUID();
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'합성 강사','INSTRUCTOR','ACTIVE',now())",
                user,
                user + "@example.com",
                "test-hash");
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'합성 학급','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'합성 아동','ACTIVE')",
                child,
                classroom);
        UUID goal = type.requiresGoal() ? UUID.randomUUID() : null;
        if (goal != null)
            jdbc.update(
                    "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'합성 목표',?,now())",
                    goal,
                    child,
                    user,
                    "a".repeat(64));
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,activity_type,status,assigned_at,started_at) values(?,?,?,?,?,now()-interval '1 day',now()-interval '1 day')",
                activity,
                child,
                goal,
                type.name(),
                state);
        var assigned = new ArrayList<UUID>();
        for (QuizType quizType : QuizAssignmentService.composition(type)) {
            UUID item = UUID.randomUUID(), question = UUID.randomUUID();
            jdbc.update(
                    "insert into quiz_item(item_id,quiz_type,emotion,question_text,image_url,choices_json,correct_answer,acceptable_answers_json,hints_json,status,item_version) values(?,?,'JOY','합성 문항','/synthetic-image','[\"기쁨\",\"슬픔\",\"화남\"]','슬픔','[\"슬픔\"]','[{\"type\":\"OBSERVABLE_FACE_CUE\",\"text\":\"표정을 살펴봐요\"},{\"type\":\"CHOICE_REDUCTION\",\"text\":\"다시 골라봐요\"}]','APPROVED',1)",
                    item,
                    quizType.name());
            assigned.add(question);
            jdbc.update(
                    "insert into activity_quiz(activity_quiz_id,activity_id,item_id,item_version,question_order) values(?,?,?,1,?)",
                    question,
                    activity,
                    item,
                    assigned.size());
        }
        return new Fixture(
                child,
                activity,
                assigned.get(type == ActivityType.FIRST_COMBINED ? 1 : 0),
                type,
                List.copyOf(assigned));
    }
}
