package com.neuringo.neuringobe.activity.repository;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {
    List<Activity> findByChildIdOrderByAssignedAtDesc(UUID childId);

    List<Activity> findByChildIdAndStatusOrderByAssignedAtDesc(UUID childId, ActivityStatus status);

    Page<Activity> findByChildIdOrderByAssignedAtDesc(UUID childId, Pageable pageable);

    Page<Activity> findByChildIdAndStatusOrderByAssignedAtDesc(
            UUID childId, ActivityStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Activity a where a.activityId = :id")
    Optional<Activity> findByIdForUpdate(@Param("id") UUID id);
}
