package com.neuringo.neuringobe.child.repository;

import com.neuringo.neuringobe.child.domain.Child;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    List<Child> findByClassId(UUID classId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Child c where c.childId = :childId")
    Optional<Child> findByIdForUpdate(@Param("childId") UUID childId);

    /** 아동 삭제(ADR 2026-10-04 D1). 배정·입장 코드 발급과 같은 아동 행 잠금이다. 여러 명은 ID 순서로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Child c where c.childId in :childIds order by c.childId")
    List<Child> findByChildIdInForUpdate(@Param("childIds") Collection<UUID> childIds);

    @Modifying
    @Query(value = "delete from child where child_id in (:childIds)", nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
