package com.neuringo.neuringobe.child.repository;

import com.neuringo.neuringobe.child.domain.ChildAccessCode;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChildAccessCodeRepository extends JpaRepository<ChildAccessCode, UUID> {

    Optional<ChildAccessCode> findByIdempotencyKey(UUID idempotencyKey);

    Optional<ChildAccessCode> findByCodeDigestAndActiveTrue(String codeDigest);

    Optional<ChildAccessCode> findByChildIdAndActiveTrue(UUID childId);

    @Modifying
    @Query(
            "update ChildAccessCode c set c.active = false where c.active = true and c.expiresAt <= :now")
    int deactivateExpired(@Param("now") Instant now);

    /** 아동 삭제(ADR 2026-10-04 D1). */
    @Modifying
    @Query(
            value = "delete from child_access_code where child_id in (:childIds)",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
