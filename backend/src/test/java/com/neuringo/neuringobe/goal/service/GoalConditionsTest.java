package com.neuringo.neuringobe.goal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.common.ApiErrorResponse.FieldError;
import com.neuringo.neuringobe.goal.dto.GoalCharacter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 목표 조건 규칙을 고정한다. 해시는 중복 배정 확인과 시나리오 재사용 조회의 열쇠라, 규칙이 바뀌면 같은 목표가 다른 목표로 잡힌다.
 *
 * <ul>
 *   <li>저장 값: 앞뒤·안쪽 공백을 정리하고 같은 값은 한 번만 남기되, 강사가 넣은 순서는 지킨다.
 *   <li>해시: 칩 순서·공백·중복만 다르면 같고, 조건 내용이 바뀌면 다르다.
 *   <li>모순: 피할 표현이 필수 요소 안에 들어 있으면(띄어쓰기·대소문자 무시) 그 피할 표현의 요청 위치를 가리킨다.
 * </ul>
 */
class GoalConditionsTest {

    private static final GoalConditions.Normalized BASE =
            normalize(
                    "친구가 속상할 때 위로하기",
                    "쉬는 시간에 친구가 그림을 망쳐 속상해하고 있다.",
                    List.of(new GoalCharacter("다온", "같은 반 친구")),
                    List.of("감정을 확인하는 질문 1회", "위로 표현 1회"),
                    List.of("괜찮아", "울지 마"));

    @Test
    void storesTrimmedCollapsedDistinctValuesInInputOrder() {
        GoalConditions.Normalized goal =
                normalize(
                        "  친구가   속상할 때  ",
                        "   ",
                        List.of(
                                new GoalCharacter(" 다온 ", "  같은  반 친구 "),
                                new GoalCharacter("다온", "같은 반 친구"),
                                new GoalCharacter("나(아동)", "  ")),
                        List.of("위로 표현 1회", "감정을  확인하는 질문 1회", " 위로 표현 1회 "),
                        List.of("울지 마", "괜찮아"));

        assertThat(goal.title()).isEqualTo("친구가 속상할 때");
        assertThat(goal.situationType()).isNull();
        assertThat(goal.characters())
                .containsExactly(
                        Map.of("name", "다온", "relation", "같은 반 친구"), Map.of("name", "나(아동)"));
        assertThat(goal.requiredElements()).containsExactly("위로 표현 1회", "감정을 확인하는 질문 1회");
        assertThat(goal.forbiddenExpressions()).containsExactly("울지 마", "괜찮아");
    }

    @Test
    void sameConditionsInAnotherOrderOrSpacingHaveTheSameHash() {
        GoalConditions.Normalized reordered =
                normalize(
                        " 친구가  속상할 때 위로하기",
                        "쉬는 시간에  친구가 그림을 망쳐 속상해하고 있다. ",
                        List.of(new GoalCharacter("다온 ", "같은 반  친구")),
                        List.of("위로 표현 1회", "감정을 확인하는 질문 1회", "위로 표현 1회"),
                        List.of(" 울지 마", "괜찮아", "괜찮아"));

        assertThat(GoalConditions.contentHash(reordered))
                .isEqualTo(GoalConditions.contentHash(BASE));
    }

    @Test
    void changedConditionsHaveADifferentHash() {
        String base = GoalConditions.contentHash(BASE);

        assertThat(
                        GoalConditions.contentHash(
                                normalize(
                                        BASE.title(),
                                        BASE.situationType(),
                                        List.of(new GoalCharacter("다온", "같은 반 친구")),
                                        List.of("감정을 확인하는 질문 1회"),
                                        List.of("괜찮아", "울지 마"))))
                .as("필수 요소가 빠짐")
                .isNotEqualTo(base);
        assertThat(
                        GoalConditions.contentHash(
                                normalize(
                                        BASE.title(),
                                        "급식 시간에 친구가 반찬을 쏟았다.",
                                        List.of(new GoalCharacter("다온", "같은 반 친구")),
                                        BASE.requiredElements(),
                                        BASE.forbiddenExpressions())))
                .as("상황이 바뀜")
                .isNotEqualTo(base);
        assertThat(
                        GoalConditions.contentHash(
                                normalize(
                                        BASE.title(),
                                        BASE.situationType(),
                                        List.of(new GoalCharacter("다온", "형제")),
                                        BASE.requiredElements(),
                                        BASE.forbiddenExpressions())))
                .as("관계가 바뀜")
                .isNotEqualTo(base);
    }

    @Test
    void hashOfAKnownGoalStaysTheSame() {
        // 해시 입력 모양(필드 이름·정렬·JSON 직렬화)이 바뀌면 이미 저장된 목표와 새 목표가 갈라진다. 바꿀 때는 기존 행을 어떻게 할지 먼저 정한다.
        assertThat(GoalConditions.contentHash(BASE))
                .isEqualTo("d961d5074e7c40f157a9324ae2c60406e30f2568026fc5e2cb31f017dc4dff08");
    }

    @Test
    void rejectsAForbiddenExpressionInsideARequiredElementIgnoringSpacesAndCase() {
        List<FieldError> errors =
                GoalConditions.violations(
                        List.of(),
                        List.of("위로 표현 1회", "Hi 라고 인사하기"),
                        List.of("화내지 마", "위 로", "hi"),
                        "goal.");

        assertThat(errors)
                .extracting(FieldError::field)
                .containsExactly("goal.forbiddenExpressions[1]", "goal.forbiddenExpressions[2]");
    }

    @Test
    void checksTheSameValuesItWouldStoreIncludingUnicodeSpaces() {
        // 전각 공백(U+3000)과 줄 바꿈 없는 공백(U+00A0)은 저장할 때 지워지므로, 검사도 지운 값으로 한다.
        List<FieldError> errors =
                GoalConditions.violations(
                        List.of(),
                        List.of("위로 표현 1회"),
                        List.of("\u3000위로\u3000", "\u00A0위\u00A0로", "괜찮아"),
                        "");

        assertThat(errors)
                .extracting(FieldError::field)
                .containsExactly("forbiddenExpressions[0]", "forbiddenExpressions[1]");
    }

    @Test
    void pointsAtEveryRequestPositionEvenWhenStoringMergesThem() {
        // 저장하면 둘이 "위로" 하나가 되지만, 오류는 화면이 보낸 칩 두 개를 각각 가리킨다.
        GoalConditions.Prepared prepared =
                GoalConditions.prepare(
                        "위로하기",
                        null,
                        null,
                        List.of(),
                        List.of("위로 표현 1회"),
                        List.of("괜찮아", "위로", "\u3000위로"),
                        "goal.");

        assertThat(prepared.goal()).isNull();
        assertThat(prepared.errors())
                .extracting(FieldError::field)
                .containsExactly("goal.forbiddenExpressions[1]", "goal.forbiddenExpressions[2]");
    }

    @Test
    void preparedGoalIsStoredWithUnicodeSpacesCleaned() {
        GoalConditions.Prepared prepared =
                GoalConditions.prepare(
                        "\u3000친구\u00A0위로하기 ",
                        "\u3000",
                        null,
                        List.of(),
                        List.of("\u3000위로 표현 1회"),
                        List.of("울지\u3000마"),
                        "");

        assertThat(prepared.errors()).isEmpty();
        assertThat(prepared.goal().title()).isEqualTo("친구 위로하기");
        assertThat(prepared.goal().situationType()).isNull();
        assertThat(prepared.goal().requiredElements()).containsExactly("위로 표현 1회");
        assertThat(prepared.goal().forbiddenExpressions()).containsExactly("울지 마");
    }

    @Test
    void acceptsConditionsWithoutOverlap() {
        assertThat(
                        GoalConditions.violations(
                                List.of(new GoalCharacter("다온", null)),
                                BASE.requiredElements(),
                                BASE.forbiddenExpressions(),
                                ""))
                .isEmpty();
    }

    @Test
    void pointsAtBlankOrMissingItemsByTheirRequestPosition() {
        List<FieldError> errors =
                GoalConditions.violations(
                        Arrays.asList(new GoalCharacter("다온", null), null),
                        Arrays.asList("위로 표현 1회", "\u3000"),
                        Arrays.asList(null, "괜찮아"),
                        "");

        assertThat(errors)
                .extracting(FieldError::field)
                .containsExactly("characters[1]", "requiredElements[1]", "forbiddenExpressions[0]");
    }

    private static GoalConditions.Normalized normalize(
            String title,
            String situation,
            List<GoalCharacter> characters,
            List<String> required,
            List<String> forbidden) {
        return GoalConditions.normalize(
                title, situation, "EMPATHY_EXPRESSION", characters, required, forbidden);
    }
}
