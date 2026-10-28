package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.ActivityDecision;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Analysis;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Assessment;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Observation;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Review;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Status;
import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator.Decision;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Context;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.EvidenceReference;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.ObservationStatus;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Scope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleplayGoalObservationMapperTest {
    private final UUID turnId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final UUID analysisId = UUID.randomUUID();
    private final UUID emotionGoal = UUID.randomUUID();
    private final UUID reasonGoal = UUID.randomUUID();
    private final UUID eventGoal = UUID.randomUUID();
    private final Reference emotion = new Reference(turnId, emotionGoal, "emotion-evidence");
    private final Reference priorEvent =
            new Reference(UUID.randomUUID(), eventGoal, "event-evidence");

    @Test
    void mapsReviewedObservationsAndPriorBasisWithoutMarkingNextFocusAsAchieved() {
        var ready = ready(extension(ActivityDecision.ACHIEVED));
        var command = command(ready);
        var plan = RoleplayGoalObservationMapper.toPlan(context(command), command, ready);

        assertThat(plan.proposal().observations())
                .containsExactly(
                        new RoleplayGoalObservationPlan.Observation(
                                ref(emotion), ObservationStatus.EXPLICIT));
        assertThat(plan.proposal().nextFocusGoalId()).isEqualTo(reasonGoal);
        assertThat(plan.proposal().decision()).isEqualTo(Decision.ACHIEVED);
        assertThat(plan.proposal().decisionBasis())
                .containsExactlyInAnyOrder(ref(emotion), ref(priorEvent));
        assertThat(plan.proposal().analysisContractVersion())
                .isEqualTo(GoalObservationOutput.ANALYSIS_VERSION);
        assertThat(plan.proposal().evaluationContractVersion())
                .isEqualTo(GoalObservationOutput.EVALUATION_VERSION);
        assertThat(plan.toString())
                .doesNotContain(ready.candidate().text(), command.canonicalUtterance());
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    void preservesReviewedObservationStatus(Status status) {
        var extension =
                new Analysis(
                        GoalObservationOutput.ANALYSIS_VERSION,
                        analysisId,
                        List.of(new Observation(emotion, status)),
                        new Assessment(ActivityDecision.IN_PROGRESS, List.of(emotion)));
        var ready = ready(extension);
        var command = command(ready);
        var plan = RoleplayGoalObservationMapper.toPlan(context(command), command, ready);

        assertThat(plan.proposal().observations().getFirst().status().name())
                .isEqualTo(status.name());
        assertThat(plan.proposal().decision()).isEqualTo(Decision.IN_PROGRESS);
    }

    @Test
    void multipleObservedGoalsRemainSeparateFromUnobservedQuestionFocus() {
        var currentEvent = new Reference(turnId, eventGoal, "event-evidence");
        var extension =
                new Analysis(
                        GoalObservationOutput.ANALYSIS_VERSION,
                        analysisId,
                        List.of(
                                new Observation(emotion, Status.EXPLICIT),
                                new Observation(currentEvent, Status.IMPLICIT)),
                        new Assessment(
                                ActivityDecision.IN_PROGRESS, List.of(emotion, currentEvent)));
        var ready = ready(extension);
        var command = command(ready);
        var plan = RoleplayGoalObservationMapper.toPlan(context(command), command, ready);

        assertThat(plan.proposal().observations())
                .extracting(o -> o.reference().microGoalId())
                .containsExactly(emotionGoal, eventGoal);
        assertThat(plan.proposal().nextFocusGoalId()).isEqualTo(reasonGoal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOAL_ACHIEVED", "CLOSING"})
    void currentGoalAchievementOrClosingDoesNotReplaceTheActivityDecision(String signal) {
        var base = ready(extension(ActivityDecision.IN_PROGRESS));
        var analysis =
                new AnalysisResult(
                        turnId,
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        signal.equals("GOAL_ACHIEVED") ? signal : "PARTIAL_UNDERSTANDING",
                        base.analysis().primaryGap(),
                        new AnalysisResult.NextStrategy(
                                signal.equals("CLOSING") ? "CLOSE_ROLEPLAY" : "PROBE_CAUSE",
                                reasonGoal,
                                "이유 확인",
                                "S1"),
                        0.9,
                        base.analysis().goalObservation());
        var candidate =
                candidate(
                        turnId,
                        candidateId,
                        reasonGoal,
                        "S1",
                        base.candidate().text(),
                        signal.equals("CLOSING") ? "CLOSING" : "GUIDING_QUESTION");
        var ready = new RoleplayTurnResult.Ready(analysis, candidate, base.evaluation());
        var command = command(ready);
        assertThat(
                        RoleplayGoalObservationMapper.toPlan(context(command), command, ready)
                                .proposal()
                                .decision())
                .isEqualTo(Decision.IN_PROGRESS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"session", "scenario", "scenarioVersion", "turn", "candidate", "checkpoint"})
    void refusesToAttachObservationsToAnotherServerScope(String changed) {
        var ready = ready(extension(ActivityDecision.ACHIEVED));
        var command = command(ready);
        var context = context(command);
        var scope = context.scope();
        var wrong =
                new Scope(
                        changed.equals("session") ? UUID.randomUUID() : scope.sessionId(),
                        changed.equals("scenario") ? UUID.randomUUID() : scope.scenarioId(),
                        changed.equals("scenarioVersion") ? 4 : scope.scenarioVersion(),
                        scope.approvalReference(),
                        changed.equals("turn") ? UUID.randomUUID() : scope.turnId(),
                        changed.equals("candidate") ? UUID.randomUUID() : scope.candidateId(),
                        changed.equals("checkpoint") ? 8 : scope.checkpointVersion());
        rejects(
                new Context(
                        wrong,
                        context.approvedCriteria(),
                        context.confirmedObservations(),
                        context.analysisContractVersion(),
                        context.evaluationContractVersion()),
                command,
                ready);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "turn",
                "candidate",
                "goal",
                "support",
                "text",
                "analysisFocus",
                "responseType",
                "strategy"
            })
    void refusesToMapAChangedCandidateEvenWhenSomeIdentifiersAreReused(String changed) {
        var base = ready(extension(ActivityDecision.IN_PROGRESS));
        var command = command(base);
        var candidate =
                candidate(
                        changed.equals("turn") ? UUID.randomUUID() : turnId,
                        changed.equals("candidate") ? UUID.randomUUID() : candidateId,
                        changed.equals("goal") ? emotionGoal : reasonGoal,
                        changed.equals("support") ? "S2" : "S1",
                        changed.equals("text") ? "다른 응답" : base.candidate().text(),
                        changed.equals("responseType") ? "ANSWER_REVEAL" : "GUIDING_QUESTION");
        if (changed.equals("strategy")) {
            candidate =
                    new CandidateResponse(
                            candidate.candidateId(),
                            candidate.turnId(),
                            candidate.text(),
                            candidate.responseType(),
                            "PROBE_EVENT",
                            candidate.targetMicroGoalId(),
                            candidate.supportLevel(),
                            candidate.previousFailedCandidateIds());
        }
        var ready =
                ready(
                        base.analysis().goalObservation(),
                        candidate,
                        changed.equals("analysisFocus") ? emotionGoal : reasonGoal);
        rejects(context(command), command, ready);
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysisVersion", "evaluationVersion", "criterion", "history"})
    void validatesAgainstServerCriteriaAndHistoryAgainAfterMapping(String changed) {
        var ready = ready(extension(ActivityDecision.ACHIEVED));
        var command = command(ready);
        var context = context(command);
        var criteria =
                changed.equals("criterion")
                        ? Map.of(
                                emotionGoal,
                                Set.of("other-evidence"),
                                reasonGoal,
                                Set.of("reason-evidence"),
                                eventGoal,
                                Set.of("event-evidence"))
                        : context.approvedCriteria();
        var wrong =
                new Context(
                        context.scope(),
                        criteria,
                        changed.equals("history") ? List.of() : context.confirmedObservations(),
                        changed.equals("analysisVersion")
                                ? "analysis-result/v1"
                                : context.analysisContractVersion(),
                        changed.equals("evaluationVersion")
                                ? "evaluation-result/v1"
                                : context.evaluationContractVersion());
        rejects(wrong, command, ready);
    }

    @ParameterizedTest
    @ValueSource(strings = {"basis", "observations"})
    void conversionDoesNotSilentlyRemoveDuplicateData(String changed) {
        var extension =
                new Analysis(
                        GoalObservationOutput.ANALYSIS_VERSION,
                        analysisId,
                        changed.equals("observations")
                                ? List.of(
                                        new Observation(emotion, Status.EXPLICIT),
                                        new Observation(emotion, Status.IMPLICIT))
                                : List.of(new Observation(emotion, Status.EXPLICIT)),
                        new Assessment(
                                ActivityDecision.ACHIEVED,
                                changed.equals("basis")
                                        ? List.of(emotion, emotion)
                                        : List.of(emotion)));
        var ready = ready(extension);
        var command = command(ready);
        rejects(context(command), command, ready);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "unavailable"})
    void missingOrUnavailableAssessmentDoesNotBecomeInProgressOrAchievement(String type) {
        var ready = ready(type.equals("legacy") ? null : extension(ActivityDecision.UNAVAILABLE));
        var command = command(ready);
        assertThatThrownBy(
                        () ->
                                RoleplayGoalObservationMapper.toPlan(
                                        context(command), command, ready))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
    }

    private Analysis extension(ActivityDecision decision) {
        return new Analysis(
                GoalObservationOutput.ANALYSIS_VERSION,
                analysisId,
                List.of(new Observation(emotion, Status.EXPLICIT)),
                new Assessment(decision, List.of(emotion, priorEvent)));
    }

    private RoleplayTurnResult.Ready ready(Analysis extension) {
        return ready(
                extension,
                candidate(turnId, candidateId, reasonGoal, "S1", "친구는 왜 속상할까?", "GUIDING_QUESTION"),
                reasonGoal);
    }

    private RoleplayTurnResult.Ready ready(
            Analysis extension, CandidateResponse candidate, UUID nextFocus) {
        var analysis =
                new AnalysisResult(
                        candidate.turnId(),
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        "PARTIAL_UNDERSTANDING",
                        new AnalysisResult.PrimaryGap("MISSING_CAUSE_LINK", "이유 확인"),
                        new AnalysisResult.NextStrategy("PROBE_CAUSE", nextFocus, "이유 확인", "S1"),
                        0.9,
                        extension);
        var evaluation =
                new EvaluationResult(
                        candidate.candidateId(),
                        true,
                        EvaluationDecision.PASS,
                        null,
                        0,
                        List.of(),
                        null,
                        extension == null
                                ? null
                                : new Review(
                                        GoalObservationOutput.EVALUATION_VERSION,
                                        extension.analysisId(),
                                        true,
                                        true));
        return new RoleplayTurnResult.Ready(analysis, candidate, evaluation);
    }

    private CandidateResponse candidate(
            UUID turn, UUID id, UUID goal, String support, String text, String responseType) {
        return new CandidateResponse(
                id, turn, text, responseType, "PROBE_CAUSE", goal, support, List.of());
    }

    private RoleplayCheckpointCommand command(RoleplayTurnResult.Ready ready) {
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        3,
                        UUID.randomUUID(),
                        ready.candidate().turnId(),
                        analysisId,
                        ready.candidate().candidateId());
        return RoleplayCheckpointCommand.fromApproved(
                trace,
                7,
                3,
                UUID.randomUUID(),
                "a".repeat(64),
                new RoleplayTurnResult.SpokenReady(ready, "친구가 넘어져서 속상해.", null));
    }

    private Context context(RoleplayCheckpointCommand command) {
        return new Context(
                new Scope(
                        command.sessionId(),
                        command.scenarioId(),
                        command.scenarioVersion(),
                        "approval-3",
                        command.turnId(),
                        command.candidateId(),
                        command.expectedVersion()),
                Map.of(
                        emotionGoal,
                        Set.of("emotion-evidence"),
                        reasonGoal,
                        Set.of("reason-evidence"),
                        eventGoal,
                        Set.of("event-evidence")),
                List.of(
                        new RoleplayGoalObservationPlan.Observation(
                                ref(priorEvent), ObservationStatus.EXPLICIT)),
                GoalObservationOutput.ANALYSIS_VERSION,
                GoalObservationOutput.EVALUATION_VERSION);
    }

    private EvidenceReference ref(Reference reference) {
        return new EvidenceReference(
                reference.turnId(), reference.microGoalId(), reference.criterionReference());
    }

    private void rejects(
            Context context, RoleplayCheckpointCommand command, RoleplayTurnResult.Ready ready) {
        assertThatThrownBy(() -> RoleplayGoalObservationMapper.toPlan(context, command, ready))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
