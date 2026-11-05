package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import java.util.Objects;

/** 학습 턴과 별도로 확정한 입력 실패를 조회·저장한다. 원본 음성이나 발화는 전달하지 않는다. */
public interface RoleplayInputFailureStore {
    Result findRecorded(RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline);

    Result recordFailure(
            RoleplayRequestIdentity identity, InputFailure reason, RoleplayTurnDeadline deadline);

    default Result recordFailure(
            RoleplayRequestIdentity identity,
            RoleplayTurnResult.InputRejected rejected,
            RoleplayTurnDeadline deadline) {
        return recordFailure(identity, rejected.reason(), deadline);
    }

    sealed interface Result permits Recorded, Absent, Conflict, PolicyRequired, Expired {}

    record Recorded(InputFailure reason, boolean textRecommended, boolean replayed)
            implements Result {
        public Recorded {
            Objects.requireNonNull(reason);
        }
    }

    record Absent() implements Result {}

    record Conflict() implements Result {}

    record PolicyRequired(boolean textRecommended) implements Result {
        public PolicyRequired() {
            this(false);
        }
    }

    record Expired() implements Result {}

    /** 실패 집계를 연결하지 않는 기존 내부 실행용이다. 음성 HTTP에는 실제 저장소를 명시적으로 주입한다. */
    static RoleplayInputFailureStore untracked() {
        return new RoleplayInputFailureStore() {
            @Override
            public Result findRecorded(
                    RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
                return new Absent();
            }

            @Override
            public Result recordFailure(
                    RoleplayRequestIdentity identity,
                    InputFailure reason,
                    RoleplayTurnDeadline deadline) {
                return new PolicyRequired();
            }
        };
    }
}
