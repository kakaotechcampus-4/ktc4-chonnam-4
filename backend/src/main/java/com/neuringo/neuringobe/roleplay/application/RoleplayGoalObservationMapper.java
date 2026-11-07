package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator.Decision;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Context;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.EvidenceReference;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Observation;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.ObservationStatus;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Proposal;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan.Scope;
import java.util.HashSet;
import java.util.Objects;
import java.util.stream.Collectors;

/** 검수된 관찰을 서버의 승인 범위에 묶인 변경안으로 변환한다. 저장·외부 호출은 수행하지 않는다. */
public final class RoleplayGoalObservationMapper {
    private RoleplayGoalObservationMapper() {}

    public static Context contextFor(
            RoleplayContextSource.Snapshot snapshot, RoleplayCheckpointCommand command) {
        var key = snapshot.key();
        return new Context(
                new Scope(
                        key.sessionId(),
                        key.scenarioId(),
                        key.scenarioVersion(),
                        snapshot.approvalReference(),
                        command.turnId(),
                        command.candidateId(),
                        key.checkpointVersion()),
                snapshot.observations().approvedCriteria().stream()
                        .collect(
                                Collectors.groupingBy(
                                        c -> c.microGoalId(),
                                        Collectors.mapping(
                                                c -> c.reference(), Collectors.toSet()))),
                snapshot.observations().confirmedObservations().stream()
                        .map(o -> new Observation(reference(o.reference()), status(o.status())))
                        .toList(),
                GoalObservationOutput.ANALYSIS_VERSION,
                GoalObservationOutput.EVALUATION_VERSION);
    }

    public static RoleplayGoalObservationPlan toPlan(
            Context context,
            RoleplayCheckpointCommand command,
            RoleplayTurnResult.Ready approvedTurn) {
        return toPlan(context, command, approvedTurn, null);
    }

    public static RoleplayGoalObservationPlan toPlan(
            RoleplayContextSource.Snapshot snapshot,
            RoleplayCheckpointCommand command,
            RoleplayTurnResult.Ready approvedTurn) {
        return toPlan(
                contextFor(snapshot, command),
                command,
                approvedTurn,
                RoleplayInputGoalAssessment.assess(
                        Objects.requireNonNull(command.inputContext()),
                        snapshot.observations(),
                        approvedTurn));
    }

    private static RoleplayGoalObservationPlan toPlan(
            Context context,
            RoleplayCheckpointCommand command,
            RoleplayTurnResult.Ready approvedTurn,
            RoleplayInputGoalAssessment inputAssessment) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(command);
        Objects.requireNonNull(approvedTurn);
        var candidate = approvedTurn.candidate();
        if (!command.turnId().equals(candidate.turnId())
                || !command.candidateId().equals(candidate.candidateId())
                || !command.microGoalId().equals(candidate.targetMicroGoalId())
                || !command.microGoalId()
                        .equals(approvedTurn.analysis().nextStrategy().targetMicroGoalId())
                || !command.supportLevel().equals(candidate.supportLevel())
                || !command.responseType().equals(candidate.responseType())
                || !command.strategyUsed().equals(candidate.strategyUsed())
                || !command.responseText().equals(candidate.text())) {
            throw new IllegalArgumentException("저장할 턴과 검수된 응답이 일치하지 않습니다.");
        }
        if (inputAssessment != null
                && inputAssessment.supportApplication() != null
                && (!command.microGoalId()
                                .equals(inputAssessment.supportApplication().targetMicroGoalId())
                        || !command.supportLevel()
                                .equals(
                                        inputAssessment
                                                .supportApplication()
                                                .appliedSupportLevel())))
            throw new IllegalArgumentException("저장할 목표·지원과 서버 정책 적용이 일치하지 않습니다.");
        var analysis = approvedTurn.analysis().goalObservation();
        var review = approvedTurn.evaluation().goalObservationReview();
        if (analysis == null
                || review == null
                || analysis.activityGoal().decision()
                        == GoalObservationOutput.ActivityDecision.UNAVAILABLE) {
            throw new RoleplayActivityGoalEvaluator.Unavailable();
        }

        var scope =
                new Scope(
                        command.sessionId(),
                        command.scenarioId(),
                        command.scenarioVersion(),
                        context.scope().approvalReference(),
                        command.turnId(),
                        command.candidateId(),
                        command.expectedVersion());
        var observations =
                analysis.observations().stream()
                        .map(o -> new Observation(reference(o.reference()), status(o.status())))
                        .toList();
        var basis = new HashSet<EvidenceReference>();
        for (var ref : analysis.activityGoal().basis()) {
            if (!basis.add(reference(ref))) {
                throw new IllegalArgumentException("활동 목표 판정의 관찰 근거가 중복됐습니다.");
            }
        }
        var decision =
                switch (analysis.activityGoal().decision()) {
                    case ACHIEVED -> Decision.ACHIEVED;
                    case IN_PROGRESS -> Decision.IN_PROGRESS;
                    case UNAVAILABLE -> throw new RoleplayActivityGoalEvaluator.Unavailable();
                };
        return RoleplayGoalObservationPlan.validateReferences(
                context,
                new Proposal(
                        scope,
                        analysis.schemaVersion(),
                        review.schemaVersion(),
                        observations,
                        candidate.targetMicroGoalId(),
                        decision,
                        basis,
                        command.inputContext() == null
                                ? null
                                : new RoleplayGoalObservationPlan.GoalProgress(
                                        command.inputContext().microGoalId(),
                                        approvedTurn.analysis().learningState(),
                                        inputAssessment)));
    }

    private static EvidenceReference reference(GoalObservationOutput.Reference reference) {
        return new EvidenceReference(
                reference.turnId(), reference.microGoalId(), reference.criterionReference());
    }

    private static ObservationStatus status(GoalObservationOutput.Status status) {
        return switch (status) {
            case EXPLICIT -> ObservationStatus.EXPLICIT;
            case IMPLICIT -> ObservationStatus.IMPLICIT;
            case NOT_EXPRESSED -> ObservationStatus.NOT_EXPRESSED;
            case MISUNDERSTOOD -> ObservationStatus.MISUNDERSTOOD;
            case CONTRADICTORY -> ObservationStatus.CONTRADICTORY;
        };
    }
}
