package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioTeacherConditionsTest {

    @Test
    void passesWhenOutputKeepsTeacherRoleText() {
        var input = teacher(List.of(Map.of("id", "FRIEND", "role", "넘어진 친구 지우")), List.of());
        assertThat(ScenarioTeacherConditions.violation(input, scenario("넘어진  친구 지우", "지우가 넘어졌다.")))
                .isEmpty();
    }

    @Test
    void rejectsNameReplacedByRelation() {
        // 강사가 이름만 적었는데 모델이 "친구"로 바꿨다. 2026-11-08 시나리오 생성 실패의 원인이다.
        var input = teacher(List.of(Map.of("id", "OTHER", "role", "민수")), List.of());
        assertThat(ScenarioTeacherConditions.violation(input, scenario("친구", "친구가 넘어졌다.")))
                .contains(ScenarioTeacherConditions.CHARACTER_ROLE_MISMATCH);
        assertThat(ScenarioTeacherConditions.violation(input, scenario("민수", "민수가 넘어졌다.")))
                .isEmpty();
    }

    @Test
    void skipsCharacterCheckWhenTeacherGaveNoCharacters() {
        var input = teacher(List.of(), List.of());
        assertThat(ScenarioTeacherConditions.violation(input, scenario("같은 반 친구", "친구가 넘어졌다.")))
                .isEmpty();
    }

    @Test
    void rejectsForbiddenExpressionHiddenByUnicodeSpace() {
        var input = teacher(List.of(), List.of("badword"));
        assertThat(
                        ScenarioTeacherConditions.violation(
                                input, scenario("친구", "친구가 B adword 라고 했다.")))
                .contains(ScenarioTeacherConditions.FORBIDDEN_EXPRESSION_USED);
    }

    private static ScenarioGenerationInput.TeacherInput teacher(
            List<Map<String, Object>> characters, List<String> forbidden) {
        return new ScenarioGenerationInput.TeacherInput(
                "친구의 감정을 알아차리기", null, null, characters, List.of(), forbidden);
    }

    private static GeneratedScenario scenario(String friendRole, String fact) {
        return new GeneratedScenario(
                "놀이터에서",
                "친구가 넘어졌다",
                "L1",
                "FRIEND",
                List.of(
                        new GeneratedScenario.Character("LEARNER", "학습자"),
                        new GeneratedScenario.Character("FRIEND", friendRole, "다정함", "반말")),
                List.of(fact),
                List.of("일부러 넘어졌다고 단정하지 않는다."),
                List.of(
                        new GeneratedScenario.MicroGoal(
                                "MG-01", "사건을 파악한다", List.of("넘어졌다는 것을 말한다."), "너 넘어졌어?")),
                Map.of("S0", "(훌쩍이며) 흐윽…", "S1", "(무릎을 잡으며) 아야…", "S2", "넘어진 건지…"),
                Map.of("S0", "MG-01", "S1", "MG-01", "S2", "MG-01"));
    }
}
