package com.neuringo.neuringobe.roleplay.repository;

import com.neuringo.neuringobe.roleplay.domain.RoleplayTurn;
import com.neuringo.neuringobe.roleplay.dto.CommittedRoleplayMessage;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleplayTurnRepository extends JpaRepository<RoleplayTurn, UUID> {
    @Query(
            """
            select new com.neuringo.neuringobe.roleplay.dto.CommittedRoleplayMessage(t.turnId, t.responseText)
            from RoleplayTurn t where t.sessionId = :session and t.turnId = :turn
                and t.checkpointVersion <= :version and t.turnNumber = :number
            """)
    Optional<CommittedRoleplayMessage> findLastCommittedMessage(
            @Param("session") UUID sessionId,
            @Param("turn") UUID turnId,
            @Param("version") long version,
            @Param("number") int number);

    @Modifying(flushAutomatically = true)
    @Query(
            "update RoleplayTurn t set t.canonicalUtterance = null, t.inputQuality = null where t.sessionId = :session and t.canonicalUtterance is not null")
    int deleteCanonicalUtterances(@Param("session") UUID sessionId);

    /** Permanent deletion only; delete owning sessions in the same transaction before commit. */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
            delete from roleplay_turn
            where session_id in (
                select session_id from roleplay_session where child_id in (:childIds)
            )
            """,
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);

    Optional<RoleplayTurn> findBySessionIdAndIdempotencyKey(UUID sessionId, UUID idempotencyKey);
}
