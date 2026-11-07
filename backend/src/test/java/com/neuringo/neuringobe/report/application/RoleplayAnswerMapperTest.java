package com.neuringo.neuringobe.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.report.domain.roleplay.CriterionObservation;
import com.neuringo.neuringobe.report.domain.roleplay.LearningState;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoleplayAnswerMapperTest {
    private final RoleplayAnswerMapper mapper = new RoleplayAnswerMapper();
    private final UUID emotion = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final UUID cause = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Test
    void criterionReferenceMatchesTheRoleplayContextSourceFormat() {
        // JdbcRoleplayContextSource 가 "evidence/v1:" + sha256(goalId + "\n" + 문장) 으로 만든다.
        String reference = RoleplayAnswerMapper.criterionReference(emotion, "속상한 감정");
        assertThat(reference).startsWith("evidence/v1:").hasSize("evidence/v1:".length() + 64);
        assertThat(RoleplayAnswerMapper.criterionReference(emotion, "속상한 감정")).isEqualTo(reference);
        assertThat(RoleplayAnswerMapper.criterionReference(cause, "속상한 감정"))
                .isNotEqualTo(reference);
    }

    @Test
    void mapsAnsweredTurnsWithCriterionTextAndLatestStatus() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        var rows =
                rows(
                        List.of(
                                new RoleplayAnswerRows.TurnRow(
                                        t1, 1, emotion, "S1", false, "PARTIAL_UNDERSTANDING"),
                                new RoleplayAnswerRows.TurnRow(
                                        t2, 2, emotion, "S2", false, "GOAL_ACHIEVED")),
                        List.of(
                                observation(t1, emotion, "속상한 감정", "IMPLICIT"),
                                observation(t2, emotion, "속상한 감정", "EXPLICIT"),
                                // 다른 목표의 관찰은 그 턴의 답한 목표가 아니라서 쓰지 않는다.
                                observation(t2, cause, "넘어져서 아픈 이유", "EXPLICIT"),
                                // 승인 기준에 없는 해시는 버리고 센다.
                                new RoleplayAnswerRows.ObservationRow(
                                        t2, emotion, "evidence/v1:unknown", "EXPLICIT")),
                        "S1");

        var mapped = mapper.map(rows);

        assertThat(mapped.level()).isEqualTo(ScenarioLevel.L2);
        assertThat(mapped.startSupport()).isEqualTo(SupportLevel.S1);
        assertThat(mapped.turns()).hasSize(2);
        assertThat(mapped.turns().get(1).learningState()).isEqualTo(LearningState.GOAL_ACHIEVED);
        assertThat(mapped.turns().get(1).observations())
                .containsExactly(
                        new CriterionObservation("속상한 감정", CriterionObservation.Status.EXPLICIT));
        assertThat(mapped.unmatchedObservations()).isEqualTo(1);
        assertThat(mapped.criteria().get(emotion))
                .containsExactly(
                        new RoleplayAnswerMapper.CriterionView(
                                "속상한 감정", CriterionObservation.Status.EXPLICIT));
        assertThat(mapped.criteria().get(cause))
                .containsExactly(new RoleplayAnswerMapper.CriterionView("넘어져서 아픈 이유", null));
    }

    @Test
    void answerRevealedTurnCountsAsAnsweredAtS3() {
        var rows =
                rows(
                        List.of(
                                new RoleplayAnswerRows.TurnRow(
                                        UUID.randomUUID(), 1, cause, "S2", true, "GOAL_ACHIEVED")),
                        List.of(),
                        null);

        var mapped = mapper.map(rows);

        assertThat(mapped.turns().getFirst().inputSupport()).isEqualTo(SupportLevel.S3);
        // 활동에 시작 지원이 없으면 첫 답의 단계를 시작으로 본다.
        assertThat(mapped.startSupport()).isEqualTo(SupportLevel.S3);
    }

    @Test
    void withoutTurnsTheStartSupportFollowsTheScenarioLevel() {
        var mapped = mapper.map(rows(List.of(), List.of(), null));

        assertThat(mapped.startSupport()).isEqualTo(SupportLevel.S1);
        assertThat(mapped.goals()).hasSize(2);
    }

    private RoleplayAnswerRows.ObservationRow observation(
            UUID turn, UUID goal, String criterion, String status) {
        return new RoleplayAnswerRows.ObservationRow(
                turn, goal, RoleplayAnswerMapper.criterionReference(goal, criterion), status);
    }

    private RoleplayAnswerRows rows(
            List<RoleplayAnswerRows.TurnRow> turns,
            List<RoleplayAnswerRows.ObservationRow> observations,
            String startSupport) {
        return new RoleplayAnswerRows(
                UUID.randomUUID(),
                Instant.parse("2026-10-31T05:00:00Z"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "L2",
                startSupport,
                List.of(
                        new RoleplayAnswerRows.MicroGoalRow(
                                emotion, 1, "감정 이름 말하기", List.of("속상한 감정")),
                        new RoleplayAnswerRows.MicroGoalRow(
                                cause, 2, "감정의 이유 말하기", List.of("넘어져서 아픈 이유"))),
                turns,
                observations);
    }
}
