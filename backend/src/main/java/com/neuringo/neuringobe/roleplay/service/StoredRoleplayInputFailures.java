package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputFailureStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import org.springframework.stereotype.Service;

/** 저장된 실패와 현재 입력 안내 상태를 제공자 중립 실행 계약으로 변환한다. */
@Service
public class StoredRoleplayInputFailures implements RoleplayInputFailureStore {
    private final RoleplayVoiceFailureStore stored;

    public StoredRoleplayInputFailures(RoleplayVoiceFailureStore stored) {
        this.stored = stored;
    }

    @Override
    public Result findRecorded(RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        return map(stored.findRecorded(identity, deadline));
    }

    @Override
    public Result recordFailure(
            RoleplayRequestIdentity identity, InputFailure reason, RoleplayTurnDeadline deadline) {
        return map(stored.recordFailure(identity, reason, deadline));
    }

    @Override
    public Result recordFailure(
            RoleplayRequestIdentity identity,
            RoleplayTurnResult.InputRejected rejected,
            RoleplayTurnDeadline deadline) {
        return map(stored.recordFailure(identity, rejected, deadline));
    }

    private Result map(RoleplayVoiceFailureStore.Result result) {
        return switch (result) {
            case RoleplayVoiceFailureStore.Recorded recorded ->
                    new Recorded(
                            recorded.reason(),
                            recorded.currentProgress().textRecommended(),
                            recorded.replayed());
            case RoleplayVoiceFailureStore.Absent ignored -> new Absent();
            case RoleplayVoiceFailureStore.Conflict ignored -> new Conflict();
            case RoleplayVoiceFailureStore.PolicyRequired policy ->
                    new PolicyRequired(policy.textRecommended());
            case RoleplayVoiceFailureStore.Expired ignored -> new Expired();
        };
    }
}
