package com.neuringo.neuringobe.goal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties =
                "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long")
class GoalQuizSupplyIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserAccountRepository users;

    @BeforeEach
    void setUp() {
        TestInstructors.save(users, "teacher-supply");
        TestInstructors.save(users, "other-teacher");
    }

    @Test
    void loggedInInstructorCanCreateGoalAndItemThenAssignActivity() throws Exception {
        String classroomBody =
                mvc.perform(
                                post("/api/v1/classrooms")
                                        .with(TestInstructors.instructor("teacher-supply"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"공급 테스트반\"}"))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.data.instructorId")
                                        .value(TestInstructors.id("teacher-supply").toString()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID classId = UUID.fromString(JsonPath.read(classroomBody, "$.data.classId"));
        mvc.perform(get("/api/v1/classrooms").with(TestInstructors.instructor("other-teacher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(
                        get("/api/v1/classrooms/{classId}", classId)
                                .with(TestInstructors.instructor("other-teacher")))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/classrooms/{classId}/children", classId)
                                .with(TestInstructors.instructor("other-teacher"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"권한 없는 등록\"}"))
                .andExpect(status().isNotFound());
        String childBody =
                mvc.perform(
                                post("/api/v1/classrooms/{classId}/children", classId)
                                        .with(TestInstructors.instructor("teacher-supply"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"displayName\":\"테스트 아동\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID childId = UUID.fromString(JsonPath.read(childBody, "$.data.childId"));
        String goalBody =
                mvc.perform(
                                post("/api/v1/children/{childId}/learning-goals", childId)
                                        .with(TestInstructors.instructor("teacher-supply"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"title\":\"친구 감정 이해하기\",\"requiredElements\":[\"감정 묻기\"]}"))
                        .andExpect(status().isCreated())
                        .andExpect(
                                jsonPath("$.data.instructorId")
                                        .value(TestInstructors.id("teacher-supply").toString()))
                        .andExpect(jsonPath("$.data.contentHash").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID goalId = UUID.fromString(JsonPath.read(goalBody, "$.data.goalId"));
        String otherChildBody =
                mvc.perform(
                                post("/api/v1/classrooms/{classId}/children", classId)
                                        .with(TestInstructors.instructor("teacher-supply"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"displayName\":\"다른 아동\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID otherChildId = UUID.fromString(JsonPath.read(otherChildBody, "$.data.childId"));
        mvc.perform(
                        post("/api/v1/activities")
                                .with(TestInstructors.instructor("teacher-supply"))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"childId\":\""
                                                + otherChildId
                                                + "\",\"goalId\":\""
                                                + goalId
                                                + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GOAL_NOT_FOUND"));
        mvc.perform(
                        get("/api/v1/learning-goals/{goalId}", goalId)
                                .with(TestInstructors.instructor("other-teacher")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GOAL_NOT_FOUND"));

        String activityBody =
                mvc.perform(
                                post("/api/v1/activities")
                                        .with(TestInstructors.instructor("teacher-supply"))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"childId\":\""
                                                        + childId
                                                        + "\",\"goalId\":\""
                                                        + goalId
                                                        + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID activityId = UUID.fromString(JsonPath.read(activityBody, "$.data.activityId"));
        // 강사는 문항을 고르지 않는다. 서버가 마이그레이션으로 넣은 승인 문항 풀에서 유형 순서대로 1개씩 붙인다(ADR 2026-10-03 D4).
        mvc.perform(
                        get("/api/v1/activities/{activityId}/quiz-items", activityId)
                                .with(TestInstructors.instructor("teacher-supply")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].quizType").value("SELF_EMOTION_SITUATION"))
                .andExpect(jsonPath("$.data[1].quizType").value("OTHER_EMOTION_SITUATION"))
                .andExpect(jsonPath("$.data[2].quizType").value("OTHER_EMOTION_IMAGE"));
    }

    @Test
    void rejectsMissingChild() throws Exception {
        UUID classId = UUID.randomUUID();
        // The API must not accept an arbitrary goal UUID just because the instructor owns the
        // child.
        // A missing child is hidden before the goal lookup.
        mvc.perform(
                        post("/api/v1/activities")
                                .with(TestInstructors.instructor("teacher-supply"))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"childId\":\""
                                                + classId
                                                + "\",\"goalId\":\""
                                                + UUID.randomUUID()
                                                + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void instructorCannotCreateOrPickQuizItems() throws Exception {
        // 기획상 문항은 팀이 미리 넣고(시드 마이그레이션) 시스템이 고른다. 강사는 문항을 등록하거나 고르지 않으므로
        // 문항 등록 API 와 문항을 골라 붙이는 API 를 두지 않는다.
        mvc.perform(
                        post("/api/v1/quiz-items")
                                .with(TestInstructors.instructor("teacher-supply"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"quizType\":\"OTHER_EMOTION_SITUATION\",\"emotion\":\"JOY\","
                                                + "\"questionText\":\"어떤 감정일까요?\","
                                                + "\"choices\":[\"기쁨\",\"슬픔\"],\"correctAnswer\":\"기쁨\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/activities/{activityId}/quiz-items", UUID.randomUUID())
                                .with(TestInstructors.instructor("teacher-supply"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"itemId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"itemVersion\":1,\"questionOrder\":1}"))
                .andExpect(status().isMethodNotAllowed());
    }
}
