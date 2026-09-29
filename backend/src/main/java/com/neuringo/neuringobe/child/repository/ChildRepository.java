package com.neuringo.neuringobe.child.repository;

import com.neuringo.neuringobe.child.domain.Child;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    List<Child> findByClassId(UUID classId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Child c where c.childId = :childId")
    Optional<Child> findByIdForUpdate(@Param("childId") UUID childId);
}
