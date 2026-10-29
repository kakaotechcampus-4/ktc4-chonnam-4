package com.neuringo.neuringobe.ai.application.prompt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Observation;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput.Reference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 같은 승인 버전에서 서버가 조회한 기준 참조와 기존 확정 관찰을 공급한다. */
public record GoalObservationInput(
        @JsonProperty("approved_criteria") List<Criterion> approvedCriteria,
        @JsonProperty("confirmed_observations") List<Observation> confirmedObservations,
        @JsonProperty("confirmed_observation_contexts")
                List<ObservationContext> confirmedObservationContexts,
        @JsonProperty("current_goal_exposures") List<CurrentGoalExposure> currentGoalExposures) {
    public GoalObservationInput {
        approvedCriteria = List.copyOf(approvedCriteria);
        confirmedObservations = List.copyOf(confirmedObservations);
        confirmedObservationContexts = List.copyOf(confirmedObservationContexts);
        currentGoalExposures = List.copyOf(currentGoalExposures);
        if (approvedCriteria.isEmpty()) {
            throw new IllegalArgumentException("승인된 학습 증거가 필요합니다.");
        }
        var keys = new HashSet<CriterionKey>();
        for (var criterion : approvedCriteria) {
            if (!keys.add(new CriterionKey(criterion.microGoalId(), criterion.reference()))) {
                throw new IllegalArgumentException("승인된 학습 증거 참조가 중복됐습니다.");
            }
        }
        var history = new HashSet<>();
        for (var observation : confirmedObservations) {
            if (!keys.contains(
                            new CriterionKey(
                                    observation.reference().microGoalId(),
                                    observation.reference().criterionReference()))
                    || !history.add(observation.reference())) {
                throw new IllegalArgumentException("기존 확정 관찰이 승인 기준과 맞지 않거나 중복됐습니다.");
            }
        }
        var contextReferences = new HashSet<Reference>();
        var inputGoals =
                approvedCriteria.stream().map(Criterion::microGoalId).collect(Collectors.toSet());
        var exposureGoals = new HashSet<UUID>();
        for (var exposure : currentGoalExposures) {
            var prior = exposure.exposureBeforeInput().lastResponse();
            if (!inputGoals.contains(exposure.microGoalId())
                    || !exposureGoals.add(exposure.microGoalId())
                    || (prior != null && !prior.microGoalId().equals(exposure.microGoalId()))) {
                throw new IllegalArgumentException("현재 발화의 사전 지원 목표가 승인 범위와 다르거나 중복됐습니다.");
            }
        }
        if (!exposureGoals.equals(inputGoals)) {
            throw new IllegalArgumentException("승인 목표마다 현재 발화 전 지원 이력이 필요합니다.");
        }
        var sources = new HashMap<UUID, ObservationContext>();
        for (var context : confirmedObservationContexts) {
            if (!history.contains(context.reference())
                    || !contextReferences.add(context.reference())
                    || !inputGoals.contains(context.inputContext().microGoalId())) {
                throw new IllegalArgumentException("관찰의 입력 상태가 승인 범위와 다르거나 중복됐습니다.");
            }
            var prior = context.goalExposureBeforeInput().lastResponse();
            if (prior != null
                    && (!prior.microGoalId().equals(context.reference().microGoalId())
                            || prior.turnId().equals(context.reference().turnId())
                            || prior.turnNumber() >= context.turnNumber()
                            || prior.checkpointVersion() >= context.checkpointVersion())) {
                throw new IllegalArgumentException("관찰 이전 지원 이력의 목표 또는 시점이 올바르지 않습니다.");
            }
            var sameTurn = sources.putIfAbsent(context.reference().turnId(), context);
            if (sameTurn != null
                    && (!sameTurn.candidateId().equals(context.candidateId())
                            || sameTurn.turnNumber() != context.turnNumber()
                            || sameTurn.checkpointVersion() != context.checkpointVersion()
                            || !sameTurn.inputContext().equals(context.inputContext()))) {
                throw new IllegalArgumentException("같은 발화에서 나온 관찰의 입력 상태가 서로 다릅니다.");
            }
        }
        if (!contextReferences.equals(history)) {
            throw new IllegalArgumentException("확정 관찰마다 입력·지원 이력 연결이 필요합니다.");
        }
    }

    public boolean contains(UUID goalId, String reference) {
        return approvedCriteria.stream()
                .anyMatch(c -> c.microGoalId().equals(goalId) && c.reference().equals(reference));
    }

    public void requireMatches(RoleplayTurnInput input) {
        for (var exposure : currentGoalExposures) {
            var prior = exposure.exposureBeforeInput().lastResponse();
            if (prior != null
                    && (prior.turnId().equals(input.learnerTurn().turnId())
                            || prior.turnNumber() >= input.state().turnCount())) {
                throw new IllegalArgumentException("현재 또는 미래 응답을 이번 발화의 사전 지원으로 사용할 수 없습니다.");
            }
        }
        var ids = new HashSet<UUID>();
        for (var goal : input.scenario().microGoals()) {
            Set<String> supplied =
                    approvedCriteria.stream()
                            .filter(c -> c.microGoalId().equals(goal.id()))
                            .map(Criterion::description)
                            .collect(Collectors.toSet());
            if (!ids.add(goal.id())
                    || supplied.isEmpty()
                    || !supplied.equals(Set.copyOf(goal.requiredEvidence()))) {
                throw new IllegalArgumentException("목표 관찰 기준이 승인 시나리오의 학습 증거와 일치하지 않습니다.");
            }
        }
        if (approvedCriteria.stream().anyMatch(c -> !ids.contains(c.microGoalId()))
                || confirmedObservations.stream()
                        .anyMatch(
                                o -> o.reference().turnId().equals(input.learnerTurn().turnId()))) {
            throw new IllegalArgumentException("목표 관찰 기준 또는 기존 관찰의 턴이 일치하지 않습니다.");
        }
    }

    /** 현재 발화에서 관찰할 수 있는 모든 승인 목표의 사전 응답 이력이다. */
    public record CurrentGoalExposure(
            @JsonProperty("micro_goal_id") UUID microGoalId,
            @JsonProperty("exposure_before_input") GoalExposure exposureBeforeInput) {
        public CurrentGoalExposure {
            Objects.requireNonNull(microGoalId);
            Objects.requireNonNull(exposureBeforeInput);
        }
    }

    public record Criterion(
            @JsonProperty("micro_goal_id") UUID microGoalId, String reference, String description) {
        public Criterion {
            Objects.requireNonNull(microGoalId);
            if (reference == null
                    || reference.isBlank()
                    || description == null
                    || description.isBlank()) {
                throw new IllegalArgumentException("학습 증거의 승인 참조와 설명이 필요합니다.");
            }
        }
    }

    private record CriterionKey(UUID goalId, String reference) {}

    public record ObservationContext(
            Reference reference,
            @JsonProperty("candidate_id") UUID candidateId,
            @JsonProperty("turn_number") int turnNumber,
            @JsonProperty("checkpoint_version") long checkpointVersion,
            @JsonProperty("input_context") RoleplayInputContext inputContext,
            @JsonProperty("goal_exposure_before_input") GoalExposure goalExposureBeforeInput) {
        public ObservationContext {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(candidateId);
            Objects.requireNonNull(inputContext);
            Objects.requireNonNull(goalExposureBeforeInput);
            if (turnNumber < 1 || checkpointVersion < 1)
                throw new IllegalArgumentException("관찰이 발생한 확정 턴 번호가 올바르지 않습니다.");
        }
    }

    /** 관찰된 목표에 발화 전에 제공한 응답만 요약한다. 입력 질문의 지원 수준을 대신하지 않는다. */
    public record GoalExposure(
            @JsonProperty("question_count") int questionCount,
            @JsonProperty("answer_revealed") boolean answerRevealed,
            @JsonProperty("last_response") PriorResponse lastResponse) {
        public GoalExposure {
            if (questionCount < 0
                    || (lastResponse == null && (questionCount != 0 || answerRevealed))
                    || (lastResponse != null
                            && ((RoleplayCodes.QUESTION_RESPONSE_TYPES.contains(
                                                    lastResponse.responseType())
                                            && questionCount == 0)
                                    || (lastResponse.responseType().equals("ANSWER_REVEAL")
                                            && !answerRevealed)))) {
                throw new IllegalArgumentException("관찰 이전 질문·정답 제공 이력이 일치하지 않습니다.");
            }
        }
    }

    public record PriorResponse(
            @JsonProperty("turn_id") UUID turnId,
            @JsonProperty("candidate_id") UUID candidateId,
            @JsonProperty("micro_goal_id") UUID microGoalId,
            @JsonProperty("turn_number") int turnNumber,
            @JsonProperty("checkpoint_version") long checkpointVersion,
            @JsonProperty("support_level") String supportLevel,
            @JsonProperty("response_type") String responseType) {
        public PriorResponse {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(candidateId);
            Objects.requireNonNull(microGoalId);
            RoleplayCodes.requireOneOf(RoleplayCodes.SUPPORT_LEVELS, supportLevel, "supportLevel");
            RoleplayCodes.requireOneOf(RoleplayCodes.RESPONSE_TYPES, responseType, "responseType");
            if (turnNumber < 1 || checkpointVersion < 1)
                throw new IllegalArgumentException("지원 응답의 확정 턴 번호가 올바르지 않습니다.");
        }
    }
}
