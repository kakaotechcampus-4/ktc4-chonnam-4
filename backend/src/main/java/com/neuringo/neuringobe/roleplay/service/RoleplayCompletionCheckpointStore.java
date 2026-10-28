package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator;
import java.util.Objects;

/** 음성 요청의 승인 결과를 활동 목표 판정과 실제 완료 저장으로 연결한다. */
public final class RoleplayCompletionCheckpointStore implements RoleplayCheckpointStore {
    private final RoleplayCheckpointStore persisted;
    private final RoleplayActivityGoalEvaluator goals;
    private final RoleplayCompletionService completion;

    public RoleplayCompletionCheckpointStore(
            RoleplayCheckpointStore persisted,
            RoleplayActivityGoalEvaluator goals,
            RoleplayCompletionService completion) {
        this.persisted = Objects.requireNonNull(persisted);
        this.goals = Objects.requireNonNull(goals);
        this.completion = Objects.requireNonNull(completion);
    }

    @Override
    public LookupResult findConfirmed(
            RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        return persisted.findConfirmed(identity, deadline);
    }

    @Override
    public CommitResult commit(RoleplayCheckpointCommand command, RoleplayTurnDeadline deadline) {
        throw new IllegalStateException("승인된 분석 결과 없이 완료 저장을 호출할 수 없습니다.");
    }

    @Override
    public CommitResult commitApproved(
            RoleplayCheckpointCommand command,
            RoleplayTurnResult.Ready approvedTurn,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(approvedTurn);
        Objects.requireNonNull(deadline);
        if (!command.turnId().equals(approvedTurn.candidate().turnId())
                || !command.candidateId().equals(approvedTurn.candidate().candidateId())) {
            return new Conflict();
        }
        var plan = deadline.withinBudget(() -> goals.evaluate(command, approvedTurn, deadline));
        if (plan == null) {
            throw new RoleplayActivityGoalEvaluator.Unavailable();
        }
        return completion.commitApprovedTurn(command, plan, deadline);
    }
}
