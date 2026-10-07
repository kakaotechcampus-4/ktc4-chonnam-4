package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestGuard;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Session advisory lock on an autocommit connection. Reserve pool capacity for short commits. */
@Service
public class PostgresRoleplayRequestGuard implements RoleplayRequestGuard {
    private final DataSource dataSource;
    private final Semaphore slots;

    public PostgresRoleplayRequestGuard(DataSource dataSource) {
        this.dataSource = dataSource;
        if (!(dataSource instanceof HikariDataSource pool) || pool.getMaximumPoolSize() < 2)
            throw new IllegalArgumentException(
                    "Roleplay advisory guard requires a configured Hikari pool with at least two connections");
        // Each execution holds one advisory connection and needs one connection for lookup/commit.
        this.slots = new Semaphore(pool.getMaximumPoolSize() / 2);
    }

    @Override
    public Optional<Lease> tryAcquire(
            RoleplayRequestIdentity identity, RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        if (!slots.tryAcquire()) throw new CapacityUnavailable();
        Connection connection = null;
        boolean acquired = false;
        try {
            connection = dataSource.getConnection();
            deadline.requireActive();
            connection.setAutoCommit(true);
            long key = key(identity);
            try (var query = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                query.setLong(1, key);
                try (var result = query.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        connection.close();
                        connection = null;
                        return Optional.empty();
                    }
                }
            }
            var lease = new ConnectionLease(connection, key, slots);
            connection =
                    null; // Ownership moves to lease, released by the worker after commit/cancel.
            acquired = true;
            return Optional.of(lease);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Could not acquire roleplay processing ownership", failure);
        } finally {
            if (connection != null) discard(connection);
            if (!acquired) slots.release();
        }
    }

    private static long key(RoleplayRequestIdentity identity) {
        try {
            String scope =
                    "neuringo/roleplay/v1/"
                            + identity.trace().sessionId()
                            + "/"
                            + identity.idempotencyKey();
            return ByteBuffer.wrap(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(scope.getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void discard(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private static final class ConnectionLease implements Lease {
        private final Connection connection;
        private final long key;
        private final Semaphore slots;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ConnectionLease(Connection connection, long key, Semaphore slots) {
            this.connection = connection;
            this.key = key;
            this.slots = slots;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                try (var query = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    query.setLong(1, key);
                    try (var result = query.executeQuery()) {
                        result.next();
                        if (!result.getBoolean(1))
                            throw new SQLException("Processing lock was not held");
                    }
                }
                connection.close();
            } catch (SQLException failure) {
                discard(connection);
                throw new IllegalStateException(
                        "Could not release roleplay processing ownership", failure);
            } finally {
                slots.release();
            }
        }
    }
}
