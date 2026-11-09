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
 *   <li>등장인물: id 가 비면 OTHER, 같은 id 는 role 글자 순서로 번호를 붙인다.
 *   <li>해시: 칩 순서·공백·중복만 다르면 같고, 조건 내용이 바뀌면 다르다.
 *   <li>모순: 피할 표현이 필수 요소나 등장인물 role 안에 들어 있으면(띄어쓰기·대소문자 무시) 그 피할 표현의 요청 위치를 가리킨다.
 * </ul>
 */
class GoalConditionsTest {

    private static final GoalConditions.Normalized BASE =
            normalize(
                    "친구가 속상할 때 위로하기",
                    "쉬는 시간에 친구가 그림을 망쳐 속상해하고 있다.",
                    List.of(new GoalCharacter("FRIEND", "그림을 망친 친구")),
                    List.of("감정을 확인하는 질문 1회", "위로 표현 1회"),
                    List.of("괜찮아", "울지 마"));

    @Test
    void storesTrimmedCollapsedDistinctValuesInInputOrder() {
        GoalConditions.Normalized goal =
                normalize(
                        "  친구가   속상할 때  ",
                        "   ",
                        List.of(
                                new GoalCharacter("FRIEND", "  그림을  망친 친구 "),
                                new GoalCharacter("FRIEND", "그림을 망친 친구"),
                                new GoalCharacter(null, "선생님"),
                                new GoalCharacter("  ", "짝꿍")),
                        List.of("위로 표현 1회", "감정을  확인하는 질문 1회", " 위로 표현 1회 "),
                        List.of("울지 마", "괜찮아"));

        assertThat(goal.title()).isEqualTo("친구가 속상할 때");
        assertThat(goal.situationType()).isNull();
        assertThat(goal.characters())
                .containsExactly(
                        Map.of("id", "FRIEND", "role", "그림을 망친 친구"),
                        Map.of("id", "OTHER", "role", "선생님"),
                        Map.of("id", "OTHER_2", "role", "짝꿍"));
        assertThat(goal.requiredElements()).containsExactly("위로 표현 1회", "감정을 확인하는 질문 1회");
        assertThat(goal.forbiddenExpressions()).containsExactly("울지 마", "괜찮아");
    }

    @Test
    void numbersRepeatedIdsInRoleOrderAndSkipsTakenNumbers() {
        GoalConditions.Normalized goal =
                normalize(
                        BASE.title(),
                        null,
                        List.of(
                                new GoalCharacter("FRIEND", "짝꿍"),
                                new GoalCharacter("FRIEND_2", "옆 반 친구"),
                                new GoalCharacter("FRIEND", "넘어진 친구")),
                        List.of(),
                        List.of());

        // "넘어진 친구"가 "짝꿍"보다 글자 순서가 앞이라 FRIEND 를 갖고, FRIEND_2 는 이미 쓰여 짝꿍은 FRIEND_3 이 된다.
        assertThat(goal.characters())
                .containsExactly(
                        Map.of("id", "FRIEND_3", "role", "짝꿍"),
                        Map.of("id", "FRIEND_2", "role", "옆 반 친구"),
                        Map.of("id", "FRIEND", "role", "넘어진 친구"));
    }

    @Test
    void charactersInAnotherOrderGetTheSameIdsAndHash() {
        GoalConditions.Normalized first =
                normalize(
                        BASE.title(),
                        null,
                        List.of(
                                new GoalCharacter("FRIEND", "짝꿍"),
                                new GoalCharacter("FRIEND", "넘어진 친구")),
                        List.of(),
                        List.of());
        GoalConditions.Normalized swapped =
                normalize(
                        BASE.title(),
                        null,
                        List.of(
                                new GoalCharacter("FRIEND", "넘어진 친구"),
                                new GoalCharacter("FRIEND", "짝꿍")),
                        List.of(),
                        List.of());

        assertThat(swapped.characters()).containsExactlyInAnyOrderElementsOf(first.characters());
        assertThat(GoalConditions.contentHash(swapped))
                .isEqualTo(GoalConditions.contentHash(first));
    }

    @Test
    void sameConditionsInAnotherOrderOrSpacingHaveTheSameHash() {
        GoalConditions.Normalized reordered =
                normalize(
                        " 친구가  속상할 때 위로하기",
                        "쉬는 시간에  친구가 그림을 망쳐 속상해하고 있다. ",
                        List.of(new GoalCharacter("FRIEND", "그림을  망친 친구 ")),
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
                                        List.of(new GoalCharacter("FRIEND", "그림을 망친 친구")),
                                        List.of("감정을 확인하는 질문 1회"),
                                        List.of("괜찮아", "울지 마"))))
                .as("필수 요소가 빠짐")
                .isNotEqualTo(base);
        assertThat(
                        GoalConditions.contentHash(
                                normalize(
                                        BASE.title(),
                                        "급식 시간에 친구가 반찬을 쏟았다.",
                                        List.of(new GoalCharacter("FRIEND", "그림을 망친 친구")),
                                        BASE.requiredElements(),
                                        BASE.forbiddenExpressions())))
                .as("상황이 바뀜")
                .isNotEqualTo(base);
        assertThat(
                        GoalConditions.contentHash(
                                normalize(
                                        BASE.title(),
                                        BASE.situationType(),
                                        List.of(new GoalCharacter("SIBLING", "그림을 망친 친구")),
                                        BASE.requiredElements(),
                                        BASE.forbiddenExpressions())))
                .as("관계가 바뀜")
                .isNotEqualTo(base);
    }

    @Test
    void hashOfAKnownGoalStaysTheSame() {
        // 해시 입력 모양(필드 이름·정렬·JSON 직렬화)이 바뀌면 이미 저장된 목표와 새 목표가 갈라진다. 바꿀 때는 기존 행을 어떻게 할지 먼저 정한다.
        assertThat(GoalConditions.contentHash(BASE))
                .isEqualTo("161f7b8160d93bb0c5baff17f840d5fc211512b90e2e726298386e40116546a5");
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
    void rejectsAForbiddenExpressionInsideACharacterRole() {
        // 생성 결과 검사는 등장인물 role 도 훑으므로, role 에 든 피할 표현은 생성이 계속 실패한다.
        List<FieldError> errors =
                GoalConditions.violations(
                        List.of(new GoalCharacter("FRIEND", "넘어진 친구")),
                        List.of(),
                        List.of("괜찮아", "넘어 진"),
                        "");

        assertThat(errors).extracting(FieldError::field).containsExactly("forbiddenExpressions[1]");
    }

    @Test
    void rejectsTheLearnerId() {
        List<FieldError> errors =
                GoalConditions.violations(
                        List.of(
                                new GoalCharacter("FRIEND", "짝꿍"),
                                new GoalCharacter("LEARNER", "나")),
                        List.of(),
                        List.of(),
                        "goal.");

        assertThat(errors).extracting(FieldError::field).containsExactly("goal.characters[1].id");
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
                                List.of(new GoalCharacter(null, "그림을 망친 친구")),
                                BASE.requiredElements(),
                                BASE.forbiddenExpressions(),
                                ""))
                .isEmpty();
    }

    @Test
    void pointsAtBlankOrMissingItemsByTheirRequestPosition() {
        List<FieldError> errors =
                GoalConditions.violations(
                        Arrays.asList(new GoalCharacter(null, "짝꿍"), null),
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
