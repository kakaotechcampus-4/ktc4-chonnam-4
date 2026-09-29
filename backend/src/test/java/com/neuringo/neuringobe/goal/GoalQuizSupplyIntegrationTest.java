package com.neuringo.neuringobe.goal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import java.util.UUID;
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

    @Test
    void loggedInInstructorCanCreateGoalAndItemThenAssignThem() throws Exception {
        String classroomBody =
                mvc.perform(
                                post("/api/v1/classrooms")
                                        .with(user("teacher-supply").roles("INSTRUCTOR"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"공급 테스트반\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.instructorId").value("teacher-supply"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID classId = UUID.fromString(JsonPath.read(classroomBody, "$.data.classId"));
        mvc.perform(get("/api/v1/classrooms").with(user("other-teacher").roles("INSTRUCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(
                        get("/api/v1/classrooms/{classId}", classId)
                                .with(user("other-teacher").roles("INSTRUCTOR")))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/classrooms/{classId}/children", classId)
                                .with(user("other-teacher").roles("INSTRUCTOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"권한 없는 등록\"}"))
                .andExpect(status().isNotFound());
        String childBody =
                mvc.perform(
                                post("/api/v1/classrooms/{classId}/children", classId)
                                        .with(user("teacher-supply").roles("INSTRUCTOR"))
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
                                        .with(user("teacher-supply").roles("INSTRUCTOR"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"title\":\"친구 감정 이해하기\",\"requiredElements\":[\"감정 묻기\"]}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.instructorId").value("teacher-supply"))
                        .andExpect(jsonPath("$.data.contentHash").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID goalId = UUID.fromString(JsonPath.read(goalBody, "$.data.goalId"));
        String otherChildBody =
                mvc.perform(
                                post("/api/v1/classrooms/{classId}/children", classId)
                                        .with(user("teacher-supply").roles("INSTRUCTOR"))
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
                                .with(user("teacher-supply").roles("INSTRUCTOR"))
                                .with(csrf())
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
                                .with(user("other-teacher").roles("INSTRUCTOR")))
                .andExpect(status().isNotFound());

        String itemBody =
                mvc.perform(
                                post("/api/v1/quiz-items")
                                        .with(user("teacher-supply").roles("INSTRUCTOR"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"quizType\":\"OTHER_EMOTION_SITUATION\",\"emotion\":\"JOY\","
                                                        + "\"questionText\":\"어떤 감정일까요?\","
                                                        + "\"choices\":[\"기쁨\",\"슬픔\"],\"correctAnswer\":\"기쁨\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.status").value("APPROVED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID itemId = UUID.fromString(JsonPath.read(itemBody, "$.data.itemId"));
        String activityBody =
                mvc.perform(
                                post("/api/v1/activities")
                                        .with(user("teacher-supply").roles("INSTRUCTOR"))
                                        .with(csrf())
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
        mvc.perform(
                        post("/api/v1/activities/{activityId}/quiz-items", activityId)
                                .with(user("teacher-supply").roles("INSTRUCTOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"itemId\":\""
                                                + itemId
                                                + "\",\"itemVersion\":1,\"questionOrder\":1}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        get("/api/v1/activities/{activityId}/quiz-items", activityId)
                                .with(user("teacher-supply").roles("INSTRUCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].questionText").value("어떤 감정일까요?"));
    }

    @Test
    void rejectsMissingChildAndInvalidQuizItem() throws Exception {
        UUID classId = UUID.randomUUID();
        // The API must not accept an arbitrary goal UUID just because the instructor owns the
        // child.
        // A missing child is hidden before the goal lookup.
        mvc.perform(
                        post("/api/v1/activities")
                                .with(user("teacher-supply").roles("INSTRUCTOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"childId\":\""
                                                + classId
                                                + "\",\"goalId\":\""
                                                + UUID.randomUUID()
                                                + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/quiz-items")
                                .with(user("teacher-supply").roles("INSTRUCTOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"quizType\":\"OTHER_EMOTION_SITUATION\",\"emotion\":\"JOY\","
                                                + "\"questionText\":\"질문\",\"choices\":[\"슬픔\"],"
                                                + "\"correctAnswer\":\"기쁨\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUIZ_ITEM"));
        mvc.perform(
                        post("/api/v1/quiz-items")
                                .with(user("teacher-supply").roles("INSTRUCTOR"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"quizType\":\"OTHER_EMOTION_IMAGE\",\"emotion\":\"JOY\","
                                                + "\"questionText\":\"표정을 보세요\",\"choices\":[\"기쁨\"],"
                                                + "\"correctAnswer\":\"기쁨\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUIZ_ITEM"));
    }
}
