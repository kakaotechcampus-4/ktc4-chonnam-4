package com.neuringo.neuringobe.roleplay.repository;

import com.neuringo.neuringobe.roleplay.domain.RoleplaySession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleplaySessionRepository extends JpaRepository<RoleplaySession, UUID> {
    /**
     * Lock after activity and child, before deleting turns, matching checkpoint/retention writes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RoleplaySession s where s.childId in :childIds order by s.sessionId")
    List<RoleplaySession> findByChildIdInForUpdate(@Param("childIds") Collection<UUID> childIds);

    @Modifying(flushAutomatically = true)
    @Query(value = "delete from roleplay_session where child_id in (:childIds)", nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);

    Optional<RoleplaySession> findBySessionIdAndChildId(UUID sessionId, UUID childId);

    @Query(
            value =
                    """
            select s.* from roleplay_session s
            where (s.status = 'COMPLETED' or s.last_activity_at <= :cutoff)
              and exists (select 1 from roleplay_turn t where t.session_id = s.session_id and t.canonical_utterance is not null)
            order by s.session_id
            for update of s skip locked
            """,
            nativeQuery = true)
    List<RoleplaySession> findRetainingExpiredForUpdate(@Param("cutoff") Instant cutoff);

    @Query(
            "select s from RoleplaySession s where s.sessionId = :session and s.childId = :child and s.activityId = :activity")
    Optional<RoleplaySession> findOwned(
            @Param("session") UUID sessionId,
            @Param("child") UUID childId,
            @Param("activity") UUID activityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select s from RoleplaySession s where s.sessionId = :session and s.childId = :child and s.activityId = :activity")
    Optional<RoleplaySession> findOwnedForUpdate(
            @Param("session") UUID sessionId,
            @Param("child") UUID childId,
            @Param("activity") UUID activityId);
}
