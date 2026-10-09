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

/** Single-backend ownership. The bounded worker pool limits active pipelines, without DB leases. */
@Service
public final class InMemoryRoleplayRequestGuard implements RoleplayRequestGuard {
    private final ConcurrentHashMap<RequestKey, LocalLease> processing = new ConcurrentHashMap<>();

    @Override
    public Optional<Lease> tryAcquire(
            RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(deadline).requireActive();
        var key = new RequestKey(identity.trace().sessionId(), identity.idempotencyKey());
        var lease = new LocalLease(key);
        if (processing.putIfAbsent(key, lease) != null) return Optional.empty();
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
        private final AtomicBoolean closed = new AtomicBoolean();

        private LocalLease(RequestKey key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) processing.remove(key, this);
        }
    }
}
