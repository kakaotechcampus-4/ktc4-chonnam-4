package com.neuringo.neuringobe.report.domain.roleplay;

import static com.neuringo.neuringobe.report.domain.roleplay.LearningState.GOAL_ACHIEVED;
import static com.neuringo.neuringobe.report.domain.roleplay.LearningState.HELP_NEEDED;
import static com.neuringo.neuringobe.report.domain.roleplay.LearningState.MISCONCEPTION;
import static com.neuringo.neuringobe.report.domain.roleplay.LearningState.NOT_ASSESSABLE;
import static com.neuringo.neuringobe.report.domain.roleplay.LearningState.OFF_TOPIC;
import static com.neuringo.neuringobe.report.domain.roleplay.LearningState.PARTIAL_UNDERSTANDING;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S0;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S1;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S2;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoleplayGoalSummarizerTest {

    private final RoleplayGoalSummarizer summarizer = new RoleplayGoalSummarizer();
    private final RoleplayMicroGoal emotion = new RoleplayMicroGoal(UUID.randomUUID(), "감정 이름 말하기");
    private final RoleplayMicroGoal cause = new RoleplayMicroGoal(UUID.randomUUID(), "감정의 이유 말하기");
    private final List<RoleplayTurnRecord> turns = new ArrayList<>();

    private void turn(RoleplayMicroGoal goal, SupportLevel support, LearningState state) {
        turns.add(new RoleplayTurnRecord(goal.id(), turns.size() + 1, support, state, false));
    }

    private RoleplaySummary summarize() {
        return summarizer.summarize(List.of(emotion, cause), turns, S1);
    }

    @Test
    void reachedGoalEndsAtTheSupportWhereItWasFirstAchieved() {
        turn(emotion, S1, HELP_NEEDED);
        turn(emotion, S2, GOAL_ACHIEVED);
        turn(cause, S2, GOAL_ACHIEVED);

        RoleplaySummary summary = summarize();

        assertThat(summary.outcomes())
                .extracting(
                        GoalOutcome::startSupport, GoalOutcome::finalSupport, GoalOutcome::result)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(S1, S2, Result.REACHED),
                        org.assertj.core.groups.Tuple.tuple(S2, S2, Result.REACHED));
        GoalEvidence first = summary.goals().getFirst();
        assertThat(first.observations())
                .containsExactly("상황 단서를 들은 뒤 모르겠다고 하거나 도움을 요청함", "두 선택지를 들은 뒤 목표에 맞게 답함");
        assertThat(first.effectiveSupports()).containsExactly("두 선택지");
        // 시작 단계 그대로 도달하면 더 받은 지원이 없다.
        assertThat(summary.goals().get(1).effectiveSupports()).isEmpty();
    }

    @Test
    void answerRevealedWithoutAchievementIsNotReachedAtS3() {
        turn(emotion, S1, MISCONCEPTION);
        turn(emotion, S2, MISCONCEPTION);
        turn(emotion, S3, PARTIAL_UNDERSTANDING);
        turn(cause, S1, GOAL_ACHIEVED);

        GoalOutcome outcome = summarize().outcomes().getFirst();

        assertThat(outcome.result()).isEqualTo(Result.NOT_REACHED);
        assertThat(outcome.finalSupport()).isEqualTo(S3);
        assertThat(outcome.neededAnswerOrMissed()).isTrue();
    }

    @Test
    void repeatingTheRevealedAnswerIsReachedAtS3AndCountsAsAnswerSupport() {
        turn(emotion, S3, GOAL_ACHIEVED);
        turn(cause, S0, GOAL_ACHIEVED);

        RoleplaySummary summary = summarize();

        GoalOutcome outcome = summary.outcomes().getFirst();
        assertThat(outcome.result()).isEqualTo(Result.REACHED);
        assertThat(outcome.reachedWithoutAnswer()).isFalse();
        assertThat(summary.goals().getFirst().observations()).containsExactly("답을 들은 뒤 그 답을 따라 말함");
    }

    @Test
    void goalNeverAskedIsExcludedAsInterrupted() {
        turn(emotion, S0, GOAL_ACHIEVED);

        GoalOutcome cause = summarize().outcomes().get(1);

        assertThat(cause.result()).isEqualTo(Result.EXCLUDED_INTERRUPTED);
        assertThat(cause.valid()).isFalse();
    }

    @Test
    void onlyUnassessableAnswersAreATechnicalExclusionNotADifficulty() {
        turn(emotion, S1, NOT_ASSESSABLE);
        turn(emotion, S1, NOT_ASSESSABLE);
        turn(cause, S1, GOAL_ACHIEVED);

        RoleplaySummary summary = summarize();

        assertThat(summary.outcomes().getFirst().result())
                .isEqualTo(Result.EXCLUDED_TECHNICAL_ERROR);
        assertThat(summary.goals().getFirst().observations()).isEmpty();
        assertThat(summary.technicalExclusions())
                .containsExactly(RoleplayGoalSummarizer.UNCLEAR_INPUT);
    }

    @Test
    void unassessableAnswersMixedWithRealOnesAreIgnoredForTheResult() {
        turn(emotion, S1, NOT_ASSESSABLE);
        turn(emotion, S1, GOAL_ACHIEVED);
        turn(cause, S0, GOAL_ACHIEVED);

        RoleplaySummary summary = summarize();

        GoalOutcome outcome = summary.outcomes().getFirst();
        assertThat(outcome.result()).isEqualTo(Result.REACHED);
        assertThat(outcome.reachedWithoutExtraSupport()).isTrue();
        assertThat(summary.goals().getFirst().observations())
                .containsExactly("상황 단서를 들은 뒤 목표에 맞게 답함");
        assertThat(summary.technicalExclusions())
                .containsExactly(RoleplayGoalSummarizer.UNCLEAR_INPUT);
    }

    @Test
    void onlyRefusalsAreExcludedAsRefused() {
        turns.add(new RoleplayTurnRecord(emotion.id(), 1, S0, OFF_TOPIC, true));
        turns.add(new RoleplayTurnRecord(emotion.id(), 2, S0, OFF_TOPIC, true));
        turn(cause, S0, GOAL_ACHIEVED);

        assertThat(summarize().outcomes().getFirst().result()).isEqualTo(Result.EXCLUDED_REFUSED);
    }

    @Test
    void unreachedGoalEndsAtTheLastAssessableSupportAndKeepsFirstAndLastObservations() {
        turn(emotion, S0, OFF_TOPIC);
        turn(emotion, S0, HELP_NEEDED);
        turn(emotion, S1, PARTIAL_UNDERSTANDING);
        turn(emotion, S1, PARTIAL_UNDERSTANDING);
        turn(emotion, S2, HELP_NEEDED);
        turn(emotion, S2, NOT_ASSESSABLE);

        GoalEvidence evidence = summarize().goals().getFirst();

        assertThat(evidence.outcome().result()).isEqualTo(Result.NOT_REACHED);
        assertThat(evidence.outcome().finalSupport()).isEqualTo(S2);
        // 같은 문장은 한 번만, 넷 이상이면 처음과 마지막 둘만 남는다.
        assertThat(evidence.observations())
                .containsExactly(
                        "열린 질문에 질문과 다른 이야기를 함",
                        "상황 단서를 들은 뒤 일부만 답함",
                        "두 선택지를 들은 뒤 모르겠다고 하거나 도움을 요청함");
    }

    @Test
    void shortenedObservationsNeverRepeatTheFirstSentence() {
        // 무작위 불변식 테스트(case 44)가 찾은 결함: A, B, A, C 를 줄이면 A, A, C 가 됐다.
        turn(emotion, S0, HELP_NEEDED);
        turn(emotion, S0, OFF_TOPIC);
        turn(emotion, S0, HELP_NEEDED);
        turn(emotion, S1, GOAL_ACHIEVED);

        assertThat(summarize().goals().getFirst().observations())
                .containsExactly(
                        "열린 질문에 모르겠다고 하거나 도움을 요청함",
                        "열린 질문에 질문과 다른 이야기를 함",
                        "상황 단서를 들은 뒤 목표에 맞게 답함");
    }

    @Test
    void criterionObservationsSayWhatWasExpressedWithoutQuotingTheChild() {
        turns.add(
                new RoleplayTurnRecord(
                        cause.id(),
                        1,
                        S0,
                        HELP_NEEDED,
                        false,
                        List.of(
                                new CriterionObservation(
                                        "넘어져서 아프다는 이유",
                                        CriterionObservation.Status.NOT_EXPRESSED))));
        turns.add(
                new RoleplayTurnRecord(
                        cause.id(),
                        2,
                        S1,
                        MISCONCEPTION,
                        false,
                        List.of(
                                new CriterionObservation(
                                        "넘어져서 아프다는 이유",
                                        CriterionObservation.Status.MISUNDERSTOOD))));
        turns.add(
                new RoleplayTurnRecord(
                        cause.id(),
                        3,
                        S2,
                        GOAL_ACHIEVED,
                        false,
                        List.of(
                                new CriterionObservation(
                                        "넘어져서 아프다는 이유", CriterionObservation.Status.EXPLICIT))));
        turns.add(
                new RoleplayTurnRecord(
                        emotion.id(),
                        4,
                        S3,
                        GOAL_ACHIEVED,
                        false,
                        List.of(
                                new CriterionObservation(
                                        "친구가 속상하다는 감정", CriterionObservation.Status.EXPLICIT))));

        RoleplaySummary summary = summarize();

        // 말하지 않은 기준은 내용이 없으니 학습 상태 문장으로 남는다.
        assertThat(summary.goals().get(1).observations())
                .containsExactly(
                        "열린 질문에 모르겠다고 하거나 도움을 요청함",
                        "상황 단서를 들은 뒤 넘어져서 아프다는 이유를 잘못 이해함",
                        "두 선택지를 들은 뒤 넘어져서 아프다는 이유를 분명히 말함");
        assertThat(summary.goals().getFirst().observations())
                .containsExactly("답을 들은 뒤 친구가 속상하다는 감정을 따라 말함");
    }

    @Test
    void objectParticleFollowsTheFinalConsonant() {
        assertThat(RoleplayGoalSummarizer.objectParticle("이유")).isEqualTo("이유를");
        assertThat(RoleplayGoalSummarizer.objectParticle("감정")).isEqualTo("감정을");
        assertThat(RoleplayGoalSummarizer.objectParticle("위로의 말")).isEqualTo("위로의 말을");
        assertThat(RoleplayGoalSummarizer.objectParticle("ABC")).isEqualTo("ABC을(를)");
    }

    @Test
    void turnsAreOrderedByTurnNumberAndUnknownGoalsAreRejected() {
        turns.add(new RoleplayTurnRecord(emotion.id(), 2, S1, GOAL_ACHIEVED, false));
        turns.add(new RoleplayTurnRecord(emotion.id(), 1, S0, HELP_NEEDED, false));
        assertThat(summarize().outcomes().getFirst().startSupport()).isEqualTo(S0);

        turns.add(new RoleplayTurnRecord(UUID.randomUUID(), 3, S0, GOAL_ACHIEVED, false));
        assertThatThrownBy(this::summarize).isInstanceOf(IllegalArgumentException.class);
    }
}
