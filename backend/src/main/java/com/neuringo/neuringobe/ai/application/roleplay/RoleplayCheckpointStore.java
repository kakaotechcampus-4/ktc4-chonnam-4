package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.Objects;
import java.util.UUID;

/** 승인된 턴과 체크포인트를 함께 저장한다. 외부 호출 중에는 저장 트랜잭션을 유지하지 않는다. */
public interface RoleplayCheckpointStore {
    CommitResult commit(RoleplayCheckpointCommand command, RoleplayTurnDeadline deadline);

    /** 완료 연결이 필요한 구현체는 승인된 분석 결과를 함께 받아 활동 목표 판정에 사용한다. */
    default CommitResult commitApproved(
            RoleplayCheckpointCommand command,
            RoleplayTurnResult.Ready approvedTurn,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(approvedTurn);
        return commit(command, deadline);
    }

    LookupResult findConfirmed(RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline);

    sealed interface LookupResult {}

    record Found(Committed result) implements LookupResult {
        public Found {
            Objects.requireNonNull(result);
        }
    }

    record Absent() implements LookupResult {}

    sealed interface CommitResult {}

    /** 재전송 결과라면 턴 수·목표 상태·보상을 다시 반영하지 않는다. */
    record Committed(
            UUID turnId, UUID candidateId, long version, boolean replayed, String responseText)
            implements CommitResult {
        public Committed {
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(candidateId);
            if (responseText == null || responseText.isBlank())
                throw new IllegalArgumentException("Approved response required");
            if (version < 1)
                throw new IllegalArgumentException("Committed version must be positive");
        }

        @Override
        public String toString() {
            return "Committed[content=redacted]";
        }
    }

    record Conflict() implements CommitResult, LookupResult {}

    record Expired() implements CommitResult, LookupResult {}
}
