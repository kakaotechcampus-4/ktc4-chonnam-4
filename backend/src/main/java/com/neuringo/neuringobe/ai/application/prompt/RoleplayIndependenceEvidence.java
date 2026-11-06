package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Observation;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Status;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 확정 관찰의 사전 도움을 분류한다. 독립 도달·연속 달성·지원 감소를 확정하지 않는다. */
public final class RoleplayIndependenceEvidence {
    private RoleplayIndependenceEvidence() {}

    public static List<Review> confirmed(GoalObservationInput observations) {
        Map<Reference, GoalObservationInput.ObservationContext> contexts =
                observations.confirmedObservationContexts().stream()
                        .collect(
                                Collectors.toMap(
                                        GoalObservationInput.ObservationContext::reference,
                                        Function.identity()));
        return observations.confirmedObservations().stream()
                .map(
                        o ->
                                new Review(
                                        o.reference(),
                                        classify(
                                                o.status(),
                                                o.reference().microGoalId(),
                                                contexts.get(o.reference()).inputContext(),
                                                contexts.get(o.reference())
                                                        .goalExposureBeforeInput())))
                .toList();
    }

    public static List<Review> current(
            GoalObservationInput observations,
            RoleplayInputContext input,
            List<Observation> current) {
        var exposures =
                observations.currentGoalExposures().stream()
                        .collect(
                                Collectors.toMap(
                                        GoalObservationInput.CurrentGoalExposure::microGoalId,
                                        GoalObservationInput.CurrentGoalExposure
                                                ::exposureBeforeInput));
        return current.stream()
                .map(
                        o -> {
                            var ref = o.reference();
                            if (!observations.contains(ref.microGoalId(), ref.criterionReference()))
                                throw new IllegalArgumentException("이번 관찰의 학습 증거가 승인 범위에 없습니다.");
                            return new Review(
                                    ref,
                                    classify(
                                            o.status(),
                                            ref.microGoalId(),
                                            input,
                                            exposures.get(ref.microGoalId())));
                        })
                .toList();
    }

    private static Eligibility classify(
            Status status,
            UUID goal,
            RoleplayInputContext input,
            GoalObservationInput.GoalExposure exposure) {
        boolean inputGoal = input.microGoalId().equals(goal);
        if (exposure.answerRevealed() || (inputGoal && input.answerRevealed()))
            return Eligibility.ANSWER_ALREADY_REVEALED;
        if (status != Status.EXPLICIT && status != Status.IMPLICIT)
            return Eligibility.NON_POSITIVE_OBSERVATION;
        if (exposure.mascotHintSeen()
                || (exposure.highestSupportLevel() != null
                        && !exposure.highestSupportLevel().equals("S0"))
                || (inputGoal && !input.supportLevel().equals("S0")))
            return Eligibility.POSITIVE_WITH_HELP;
        // 다른 목표의 질문 지원을 복사하거나 지원 기록 부재를 S0로 간주하지 않는다.
        if (!inputGoal && exposure.highestSupportLevel() == null)
            return Eligibility.SUPPORT_UNKNOWN;
        return Eligibility.POSITIVE_WITHOUT_RECORDED_HELP;
    }

    public enum Eligibility {
        POSITIVE_WITHOUT_RECORDED_HELP,
        POSITIVE_WITH_HELP,
        ANSWER_ALREADY_REVEALED,
        NON_POSITIVE_OBSERVATION,
        SUPPORT_UNKNOWN
    }

    public record Review(Reference reference, Eligibility eligibility) {}
}
