package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestGuard;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/** 서버 한 대 안에서 같은 요청의 중복 실행을 막는다. 동시에 실행할 작업 수는 작업자 풀에서 제한한다. */
@Service
public final class InMemoryRoleplayRequestGuard implements RoleplayRequestGuard {
    private final ConcurrentHashMap<RequestKey, LocalLease> processing = new ConcurrentHashMap<>();

    @Override
    public Optional<Lease> tryAcquire(
            RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(deadline).requireActive();
        var key = new RequestKey(identity.trace().sessionId(), identity.idempotencyKey());
        var lease = new LocalLease(key, identity.fingerprint());
        var existing = processing.putIfAbsent(key, lease);
        if (existing != null) {
            if (!existing.fingerprint.equals(identity.fingerprint())) throw new InputConflict();
            return Optional.empty();
        }
        try {
            deadline.requireActive();
            return Optional.of(lease);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private record RequestKey(UUID sessionId, UUID idempotencyKey) {}

    private final class LocalLease implements Lease {
        private final RequestKey key;
        private final String fingerprint;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LocalLease(RequestKey key, String fingerprint) {
            this.key = key;
            this.fingerprint = fingerprint;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) processing.remove(key, this);
        }
    }
}
