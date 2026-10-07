package com.neuringo.neuringobe.roleplay.repository;

import com.neuringo.neuringobe.roleplay.domain.RoleplayTurn;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleplayTurnRepository extends JpaRepository<RoleplayTurn, UUID> {
    @Modifying(flushAutomatically = true)
    @Query(
            "update RoleplayTurn t set t.canonicalUtterance = null where t.sessionId = :session and t.canonicalUtterance is not null")
    int deleteCanonicalUtterances(@Param("session") UUID sessionId);

    Optional<RoleplayTurn> findBySessionIdAndIdempotencyKey(UUID sessionId, UUID idempotencyKey);
}
