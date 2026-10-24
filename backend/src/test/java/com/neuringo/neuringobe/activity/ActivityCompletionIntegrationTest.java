package com.neuringo.neuringobe.activity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 화면은 status 전용 조회로 활동 상태를 읽는다. 본인 아동과 담당 강사만 보고, 그 밖에는 활동이 없는 것과 같은 오류로 숨긴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class ActivityCompletionIntegrationTest {
    private static final String TEACHER = "teacher-completion";
    private static final String OTHER_TEACHER = "teacher-completion-other";

    @Autowired private MockMvc mvc;
    @Autowired private ClassroomRepository classrooms;
    @Autowired private UserAccountRepository users;
    @Autowired private ChildRepository children;
    @Autowired private QuizItemRepository items;
    @Autowired private ActivityQuizRepository assignments;
    @Autowired private ActivityRepository activities;

    private UUID classroomId;
    private UUID childId;
    private MockHttpSession childSession;

    @BeforeEach
    void setUp() throws Exception {
        TestInstructors.save(users, TEACHER);
        TestInstructors.save(users, OTHER_TEACHER);
        classroomId = UUID.randomUUID();
        classrooms.save(
                new Classroom(
                        classroomId, TestInstructors.id(TEACHER), "완료반", ClassroomStatus.ACTIVE));
        childId = newChild();
        childSession = enter(childId);
    }

    @Test
    void statusIsVisibleOnlyToTheChildAndTheirInstructor() throws Exception {
        UUID activityId = createActivity(ActivityType.OTHER_EMOTION_QUIZ);

        expectStatus(activityId, childSession, ActivityStatus.NOT_STARTED);
        mvc.perform(
                        get("/api/v1/activities/{id}/status", activityId)
                                .with(TestInstructors.instructor(TEACHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        MockHttpSession otherChild = enter(newChild());
        mvc.perform(get("/api/v1/activities/{id}/status", activityId).session(otherChild))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        mvc.perform(
                        get("/api/v1/activities/{id}/status", activityId)
                                .with(TestInstructors.instructor(OTHER_TEACHER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
        mvc.perform(get("/api/v1/activities/{id}/status", UUID.randomUUID()).session(childSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_NOT_FOUND"));
    }

    private void expectStatus(UUID activityId, MockHttpSession session, ActivityStatus expected)
            throws Exception {
        mvc.perform(get("/api/v1/activities/{id}/status", activityId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expected.name()));
    }

    private UUID newChild() {
        UUID id = UUID.randomUUID();
        children.save(new Child(id, classroomId, "완료 아동", ChildStatus.ACTIVE));
        return id;
    }

    private MockHttpSession enter(UUID child) throws Exception {
        String codeBody =
                mvc.perform(
                                post("/api/v1/children/{childId}/access-codes", child)
                                        .with(TestInstructors.instructor(TEACHER))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID()))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String code = JsonPath.read(codeBody, "$.data.accessCode");
        return (MockHttpSession)
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accessCode\":\"" + code + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    // 배정 API 는 문항을 무작위로 붙인다. 완료 흐름만 보려고 빈 활동을 저장한 뒤 정답이 정해진 문항을 직접 붙인다.
    private UUID createActivity(ActivityType type) {
        UUID goalId = type.requiresGoal() ? goal() : null;
        return activities
                .save(
                        new Activity(
                                UUID.randomUUID(),
                                childId,
                                type,
                                goalId,
                                UUID.randomUUID(),
                                Instant.now()))
                .getActivityId();
    }

    private UUID goal() {
        try {
            String body =
                    mvc.perform(
                                    post("/api/v1/children/{childId}/learning-goals", childId)
                                            .with(TestInstructors.instructor(TEACHER))
                                            .with(csrf())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"title\":\"감정 표현하기\"}"))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            return UUID.fromString(JsonPath.read(body, "$.data.goalId"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
