package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.Optional;

/** 같은 요청의 중복 실행을 막는다. 입력 원문이나 요청 처리 이력을 데이터베이스에 저장하지 않는다. */
public interface RoleplayRequestGuard {
    Optional<Lease> tryAcquire(RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline);

    final class InputConflict extends RuntimeException {
        public InputConflict() {
            super("Roleplay processing request input conflict");
        }
    }

    final class CapacityUnavailable extends RuntimeException {
        public CapacityUnavailable() {
            super("Roleplay processing capacity unavailable");
        }
    }

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
