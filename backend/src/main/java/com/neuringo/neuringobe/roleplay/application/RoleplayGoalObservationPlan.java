package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator.Decision;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 참조 검증을 마친 저장 변경안이다. 목표 달성의 의미 검수 승인을 대신하지 않는다. */
public final class RoleplayGoalObservationPlan {
    private final Proposal proposal;

    private RoleplayGoalObservationPlan(Proposal proposal) {
        this.proposal = proposal;
    }

    public static RoleplayGoalObservationPlan validateReferences(
            Context context, Proposal proposal) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(proposal);
        if (!context.scope().equals(proposal.scope())) {
            throw invalid("관찰 결과의 세션·시나리오·턴·후보·저장 버전이 일치하지 않습니다.");
        }
        if (!context.analysisContractVersion().equals(proposal.analysisContractVersion())
                || !context.evaluationContractVersion()
                        .equals(proposal.evaluationContractVersion())) {
            throw invalid("관찰 결과의 분석·검수 계약 버전이 일치하지 않습니다.");
        }
        if (!context.approvedCriteria().containsKey(proposal.nextFocusGoalId())) {
            throw invalid("다음 질문의 목표가 승인 범위에 없습니다.");
        }
        if (proposal.inputGoalProgress() != null
                && !context.approvedCriteria()
                        .containsKey(proposal.inputGoalProgress().microGoalId())) {
            throw invalid("입력 당시 작은 목표가 승인 범위에 없습니다.");
        }

        var available = new HashSet<EvidenceReference>();
        var positive = new HashSet<EvidenceReference>();
        for (var prior : context.confirmedObservations()) {
            requireApproved(context.approvedCriteria(), prior.reference());
            if (prior.reference().turnId().equals(context.scope().turnId())
                    || !available.add(prior.reference())) {
                throw invalid("기존 관찰 이력에 현재 턴 또는 중복 근거가 있습니다.");
            }
            if (prior.status() == ObservationStatus.EXPLICIT
                    || prior.status() == ObservationStatus.IMPLICIT) {
                positive.add(prior.reference());
            }
        }
        for (var observation : proposal.observations()) {
            var reference = observation.reference();
            requireApproved(context.approvedCriteria(), reference);
            if (!context.scope().turnId().equals(reference.turnId())) {
                throw invalid("새 관찰 결과가 다른 턴을 참조합니다.");
            }
            if (!available.add(reference)) {
                throw invalid("같은 턴·목표·학습 증거의 관찰이 중복됐습니다.");
            }
            if (observation.status() == ObservationStatus.EXPLICIT
                    || observation.status() == ObservationStatus.IMPLICIT) {
                positive.add(reference);
            }
        }
        if (!available.containsAll(proposal.decisionBasis())) {
            throw invalid("활동 목표 판정에 존재하지 않는 관찰 근거가 있습니다.");
        }
        var progress = proposal.inputGoalProgress();
        if (progress != null && progress.assessment() != null) {
            var assessment = progress.assessment();
            if (assessment.outcome() == RoleplayInputGoalAssessment.Outcome.IN_PROGRESS
                    && progress.learningState().equals("GOAL_ACHIEVED"))
                throw invalid("도달 판정을 진행 중 결과로 저장할 수 없습니다.");
            var approved = context.approvedCriteria().get(progress.microGoalId());
            var supplied = new HashSet<String>();
            for (var basis : assessment.basis()) {
                if (!basis.turnId().equals(context.scope().turnId())
                        || !basis.microGoalId().equals(progress.microGoalId())
                        || proposal.observations().stream()
                                .noneMatch(
                                        o ->
                                                o.reference().equals(basis)
                                                        && (o.status() == ObservationStatus.EXPLICIT
                                                                || o.status()
                                                                        == ObservationStatus
                                                                                .IMPLICIT)))
                    throw invalid("입력 목표의 도달 근거가 이번 발화의 긍정 관찰과 일치하지 않습니다.");
                supplied.add(basis.criterionReference());
            }
            if (!assessment.basis().isEmpty()
                    && (!progress.learningState().equals("GOAL_ACHIEVED")
                            || !supplied.equals(approved)))
                throw invalid("입력 목표의 도달 판정과 승인된 학습 증거가 일치하지 않습니다.");
            if ((assessment.outcome() == RoleplayInputGoalAssessment.Outcome.NOT_ASSESSABLE)
                    != progress.learningState().equals("NOT_ASSESSABLE"))
                throw invalid("판정 불가 상태와 입력 목표 결과가 일치하지 않습니다.");
        }
        if (progress != null
                && progress.assessment() != null
                && progress.assessment().supportApplication() != null) {
            var application = progress.assessment().supportApplication();
            if (!application.targetMicroGoalId().equals(proposal.nextFocusGoalId())
                    || application.targetMicroGoalId().equals(progress.microGoalId()))
                throw invalid("지원 감소는 다른 목표의 시작 질문에만 적용합니다.");
            for (var input : application.basis()) {
                var supplied = new HashSet<String>();
                for (var ref : input.basis()) {
                    var basis =
                            new EvidenceReference(
                                    ref.turnId(), ref.microGoalId(), ref.criterionReference());
                    if (!ref.turnId().equals(input.turnId())
                            || !ref.microGoalId().equals(input.microGoalId())
                            || !available.contains(basis)
                            || !supplied.add(ref.criterionReference()))
                        throw invalid("지원 감소 근거가 같은 입력·목표의 확정 관찰과 다릅니다.");
                }
                if (!supplied.equals(context.approvedCriteria().get(input.microGoalId())))
                    throw invalid("지원 감소 근거에 목표의 필수 관찰이 누락됐습니다.");
                if (input.turnId().equals(context.scope().turnId())
                        && (!input.microGoalId().equals(progress.microGoalId())
                                || progress.assessment().outcome()
                                        != RoleplayInputGoalAssessment.Outcome
                                                .ACHIEVED_WITHOUT_HINT))
                    throw invalid("이번 발화는 검수된 힌트 없는 도달이어야 합니다.");
            }
        }
        if (proposal.decision() == Decision.ACHIEVED && proposal.decisionBasis().isEmpty()) {
            throw invalid("활동 목표 달성 판정에는 관찰 근거가 필요합니다.");
        }
        // 관찰이 존재하는 것과 달성을 뒷받침하는 것은 다르다. 실제 판정 근거만 검사한다.
        if (proposal.decision() == Decision.ACHIEVED
                && proposal.decisionBasis().stream().noneMatch(positive::contains)) {
            throw invalid("활동 목표 달성 판정에는 명시적 또는 간접 표현 근거가 필요합니다.");
        }
        if (proposal.decision() == Decision.UNAVAILABLE) {
            throw invalid("판정 불가 결과는 저장 변경안으로 확정할 수 없습니다.");
        }
        return new RoleplayGoalObservationPlan(proposal);
    }

    private static void requireApproved(
            Map<UUID, Set<String>> approvedCriteria, EvidenceReference reference) {
        var criteria = approvedCriteria.get(reference.microGoalId());
        if (criteria == null || !criteria.contains(reference.criterionReference())) {
            throw invalid("관찰한 목표 또는 학습 증거가 승인 범위에 없습니다.");
        }
    }

    public Proposal proposal() {
        return proposal;
    }

    @Override
    public String toString() {
        return "RoleplayGoalObservationPlan[content=redacted]";
    }

    public record Scope(
            UUID sessionId,
            UUID scenarioId,
            int scenarioVersion,
            String approvalReference,
            UUID turnId,
            UUID candidateId,
            long checkpointVersion) {
        public Scope {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(scenarioId);
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(candidateId);
            requireText(approvalReference);
            if (scenarioVersion < 1 || checkpointVersion < 0) {
                throw invalid("승인 시나리오 또는 저장 버전이 올바르지 않습니다.");
            }
        }
    }

    /** 승인 기준과 이전 관찰은 서버가 같은 세션·승인 버전에서 조회해 공급한다. */
    public record Context(
            Scope scope,
            Map<UUID, Set<String>> approvedCriteria,
            List<Observation> confirmedObservations,
            String analysisContractVersion,
            String evaluationContractVersion) {
        public Context {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(approvedCriteria);
            var copied = new HashMap<UUID, Set<String>>();
            approvedCriteria.forEach(
                    (goal, criteria) -> {
                        Objects.requireNonNull(goal);
                        var references = Set.copyOf(criteria);
                        references.forEach(RoleplayGoalObservationPlan::requireText);
                        if (references.isEmpty()) {
                            throw invalid("승인 목표의 학습 증거가 비어 있습니다.");
                        }
                        copied.put(goal, references);
                    });
            if (copied.isEmpty()) {
                throw invalid("승인 목표가 비어 있습니다.");
            }
            approvedCriteria = Map.copyOf(copied);
            confirmedObservations = List.copyOf(confirmedObservations);
            requireText(analysisContractVersion);
            requireText(evaluationContractVersion);
        }
    }

    public record EvidenceReference(UUID turnId, UUID microGoalId, String criterionReference) {
        public EvidenceReference {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(microGoalId);
            requireText(criterionReference);
        }
    }

    public enum ObservationStatus {
        EXPLICIT,
        IMPLICIT,
        NOT_EXPRESSED,
        MISUNDERSTOOD,
        CONTRADICTORY
    }

    public record Observation(EvidenceReference reference, ObservationStatus status) {
        public Observation {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(status);
        }
    }

    public record Proposal(
            Scope scope,
            String analysisContractVersion,
            String evaluationContractVersion,
            List<Observation> observations,
            UUID nextFocusGoalId,
            Decision decision,
            Set<EvidenceReference> decisionBasis,
            GoalProgress inputGoalProgress) {
        public Proposal(
                Scope scope,
                String analysisContractVersion,
                String evaluationContractVersion,
                List<Observation> observations,
                UUID nextFocusGoalId,
                Decision decision,
                Set<EvidenceReference> decisionBasis) {
            this(
                    scope,
                    analysisContractVersion,
                    evaluationContractVersion,
                    observations,
                    nextFocusGoalId,
                    decision,
                    decisionBasis,
                    null);
        }

        public Proposal {
            Objects.requireNonNull(scope);
            requireText(analysisContractVersion);
            requireText(evaluationContractVersion);
            observations = List.copyOf(observations);
            Objects.requireNonNull(nextFocusGoalId);
            Objects.requireNonNull(decision);
            decisionBasis = Set.copyOf(decisionBasis);
        }
    }

    /** 입력을 받은 목표의 검수된 판정이며 다음 질문의 초점과 구분한다. */
    public record GoalProgress(
            UUID microGoalId, String learningState, RoleplayInputGoalAssessment assessment) {
        public GoalProgress(UUID microGoalId, String learningState) {
            this(microGoalId, learningState, null);
        }

        public GoalProgress {
            Objects.requireNonNull(microGoalId);
            if (learningState == null || !RoleplayCodes.LEARNING_STATES.contains(learningState)) {
                throw invalid("작은 목표의 학습 상태가 올바르지 않습니다.");
            }
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("승인 참조와 계약 버전은 비어 있을 수 없습니다.");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
