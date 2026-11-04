package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** 같은 세션의 유효한 입력 중에는 기한이 지난 정기 삭제를 미룬다. */
@Component
public final class RoleplayRetentionGate {
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public Lease enterInput(UUID sessionId, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(deadline).requireActive();
        var entry = retain(sessionId);
        try {
            synchronized (entry) {
                while (entry.cleaning) {
                    deadline.requireActive();
                    TimeUnit.NANOSECONDS.timedWait(entry, deadline.remainingNanos());
                }
                deadline.requireActive();
                var lease = new Lease(sessionId, entry, deadline);
                entry.inputs.add(lease);
                return lease;
            }
        } catch (InterruptedException interrupted) {
            releaseReference(sessionId, entry);
            Thread.currentThread().interrupt();
            throw new CancellationException("입력 대기가 중단되었습니다.");
        } catch (RuntimeException failure) {
            releaseReference(sessionId, entry);
            throw failure;
        }
    }

    public Optional<Lease> tryEnterCleanup(UUID sessionId) {
        var entry = retain(sessionId);
        synchronized (entry) {
            entry.inputs.removeIf(input -> input.deadline.remainingNanos() == 0);
            if (!entry.cleaning && entry.inputs.isEmpty()) {
                entry.cleaning = true;
                return Optional.of(new Lease(sessionId, entry, null));
            }
        }
        releaseReference(sessionId, entry);
        return Optional.empty();
    }

    private Entry retain(UUID sessionId) {
        Objects.requireNonNull(sessionId);
        return entries.compute(
                sessionId,
                (key, entry) -> {
                    var current = entry == null ? new Entry() : entry;
                    current.references++;
                    return current;
                });
    }

    private void releaseReference(UUID sessionId, Entry entry) {
        entries.compute(
                sessionId,
                (key, current) -> {
                    if (current != entry) throw new IllegalStateException("정리 잠금 상태가 올바르지 않습니다.");
                    return --current.references == 0 ? null : current;
                });
    }

    private static final class Entry {
        private final Set<Lease> inputs = new HashSet<>();
        private int references;
        private boolean cleaning;
    }

    public final class Lease implements AutoCloseable {
        private final UUID sessionId;
        private final Entry entry;
        private final RoleplayTurnDeadline deadline;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(UUID sessionId, Entry entry, RoleplayTurnDeadline deadline) {
            this.sessionId = sessionId;
            this.entry = entry;
            this.deadline = deadline;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (entry) {
                if (deadline == null) {
                    entry.cleaning = false;
                    entry.notifyAll();
                } else {
                    entry.inputs.remove(this);
                }
            }
            releaseReference(sessionId, entry);
        }
    }
}
