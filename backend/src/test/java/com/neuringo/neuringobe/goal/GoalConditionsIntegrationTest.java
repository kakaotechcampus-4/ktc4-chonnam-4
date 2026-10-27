package com.neuringo.neuringobe.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.LocalProfileIntegrationTest;
import com.neuringo.neuringobe.TestFixtures;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 역할극 추가 조건을 HTTP 로 확인한다. 규칙 자체는 GoalConditionsTest 가 고정하고, 여기서는 API 에 이어졌는지를 본다.
 *
 * <ul>
 *   <li>모순: 피할 표현이 필수 요소 안에 있으면 422 INVALID_GOAL_CONDITIONS 이고 fieldErrors 가 그 피할 표현을 가리킨다. 활동
 *       배정에서는 요청 검증 오류처럼 {@code goal.} 이 붙는다. 목표도 활동도 저장되지 않는다.
 *   <li>정리: 저장된 목표(응답·조회)에 정리한 값이 입력 순서대로 남는다.
 *   <li>상황 길이: 상황 설명 문장은 200자까지 저장되고 201자는 422 다(DB 칸과 요청 검증이 같은 길이).
 * </ul>
 */
@LocalProfileIntegrationTest
class GoalConditionsIntegrationTest {

    @Autowired private TestFixtures fixtures;

    private UUID childId;

    @BeforeEach
    void createChild() {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        childId = fixtures.createChild(classId, "김하늘");
    }

    @Test
    void rejectsAForbiddenExpressionInsideARequiredElement() {
        MvcTestResult result =
                saveGoal(
                        Map.of(
                                "title", "친구 위로하기",
                                "requiredElements", List.of("위로 표현 1회"),
                                "forbiddenExpressions", List.of("울지 마", "위 로")));

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.error.code")
                .isEqualTo("INVALID_GOAL_CONDITIONS");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[*].field")
                .asArray()
                .containsExactly("forbiddenExpressions[1]");
        assertThat(goalsOfChild()).isEmpty();
    }

    @Test
    void rejectsAForbiddenExpressionThatOnlyFullWidthSpacesSeparate() {
        MvcTestResult result =
                saveGoal(
                        Map.of(
                                "title", "친구 위로하기",
                                "requiredElements", List.of("위로 표현 1회"),
                                "forbiddenExpressions", List.of("\u3000위로\u3000")));

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[*].field")
                .asArray()
                .containsExactly("forbiddenExpressions[0]");
        assertThat(goalsOfChild()).isEmpty();
    }

    @Test
    void assignmentRejectsFullWidthSpacedForbiddenExpressionAndSavesNothing() {
        MvcTestResult result =
                fixtures.postJsonWithKey(
                        fixtures.instructor(),
                        "/api/v1/activities",
                        Map.of(
                                "childId",
                                childId,
                                "goal",
                                Map.of(
                                        "title", "친구 위로하기",
                                        "requiredElements", List.of("위로 표현 1회"),
                                        "forbiddenExpressions", List.of("\u3000위로\u3000"))),
                        UUID.randomUUID());

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[*].field")
                .asArray()
                .containsExactly("goal.forbiddenExpressions[0]");
        assertThat(goalsOfChild()).isEmpty();
        assertThat(fixtures.get("/api/v1/children/{childId}/activities", childId))
                .bodyJson()
                .extractingPath("$.data")
                .asArray()
                .isEmpty();
    }

    @Test
    void assignmentPointsAtTheGoalFieldAndSavesNothing() {
        MvcTestResult result =
                fixtures.postJsonWithKey(
                        fixtures.instructor(),
                        "/api/v1/activities",
                        Map.of(
                                "childId",
                                childId,
                                "goal",
                                Map.of(
                                        "title", "친구 위로하기",
                                        "requiredElements", List.of("위로 표현 1회"),
                                        "forbiddenExpressions", List.of("위로"))),
                        UUID.randomUUID());

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[*].field")
                .asArray()
                .containsExactly("goal.forbiddenExpressions[0]");
        assertThat(goalsOfChild()).isEmpty();
        assertThat(fixtures.get("/api/v1/children/{childId}/activities", childId))
                .bodyJson()
                .extractingPath("$.data")
                .asArray()
                .isEmpty();
    }

    @Test
    void storesNormalizedConditionsInInputOrder() {
        MvcTestResult result =
                saveGoal(
                        Map.of(
                                "title", " 친구  위로하기 ",
                                "situationType", "쉬는 시간에   친구가 그림을 망쳐 속상해하고 있다. ",
                                "characters",
                                        List.of(
                                                Map.of("name", " 다온", "relation", "같은 반  친구"),
                                                Map.of("name", "다온", "relation", "같은 반 친구")),
                                "requiredElements",
                                        List.of("위로 표현 1회", "감정을 확인하는 질문 1회", "위로 표현  1회"),
                                "forbiddenExpressions", List.of("울지 마")));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        String goalId = JsonPath.read(TestFixtures.body(result), "$.data.goalId");
        String stored = TestFixtures.body(fixtures.get("/api/v1/learning-goals/{goalId}", goalId));
        assertThat(JsonPath.<String>read(stored, "$.data.title")).isEqualTo("친구 위로하기");
        assertThat(JsonPath.<String>read(stored, "$.data.situationType"))
                .isEqualTo("쉬는 시간에 친구가 그림을 망쳐 속상해하고 있다.");
        assertThat(JsonPath.<List<Map<String, Object>>>read(stored, "$.data.characters"))
                .containsExactly(Map.of("name", "다온", "relation", "같은 반 친구"));
        assertThat(JsonPath.<List<String>>read(stored, "$.data.requiredElements"))
                .containsExactly("위로 표현 1회", "감정을 확인하는 질문 1회");
    }

    @Test
    void keepsASituationSentenceUpTo200Characters() {
        String twoHundred = "가".repeat(200);

        assertThat(saveGoal(Map.of("title", "상황 길이", "situationType", twoHundred)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.data.situationType")
                .isEqualTo(twoHundred);
        assertThat(saveGoal(Map.of("title", "상황 길이", "situationType", twoHundred + "가")))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[0].field")
                .isEqualTo("situationType");
    }

    @Test
    void requiresACharacterName() {
        assertThat(
                        saveGoal(
                                Map.of(
                                        "title",
                                        "등장인물",
                                        "characters",
                                        List.of(Map.of("relation", "같은 반 친구")))))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.error.fieldErrors[0].field")
                .isEqualTo("characters[0].name");
    }

    private MvcTestResult saveGoal(Map<String, Object> body) {
        return fixtures.postJson("/api/v1/children/" + childId + "/learning-goals", body);
    }

    private List<Object> goalsOfChild() {
        return JsonPath.read(
                TestFixtures.body(
                        fixtures.get("/api/v1/children/{childId}/learning-goals", childId)),
                "$.data");
    }
}
