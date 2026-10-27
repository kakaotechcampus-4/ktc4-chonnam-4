package com.neuringo.neuringobe.activity.repository;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {
    List<Activity> findByChildIdOrderByAssignedAtDesc(UUID childId);

    List<Activity> findByChildIdAndStatusOrderByAssignedAtDesc(UUID childId, ActivityStatus status);

    Page<Activity> findByChildIdOrderByAssignedAtDesc(UUID childId, Pageable pageable);

    Page<Activity> findByChildIdAndStatusOrderByAssignedAtDesc(
            UUID childId, ActivityStatus status, Pageable pageable);

    Optional<Activity> findByIdempotencyKey(UUID idempotencyKey);

    boolean existsByChildIdAndActivityType(UUID childId, ActivityType activityType);

    boolean existsByChildIdAndActivityTypeAndStatus(
            UUID childId, ActivityType activityType, ActivityStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Activity a where a.activityId = :id")
    Optional<Activity> findByIdForUpdate(@Param("id") UUID id);

    /** 아동 삭제(ADR 2026-10-04 D1) 전에 그 아동들의 활동을 잠근다. 퀴즈 응답 저장도 활동 → 아동 순서로 잠그므로 같은 순서를 지켜 교착을 피한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Activity a where a.childId in :childIds order by a.activityId")
    List<Activity> findByChildIdInForUpdate(@Param("childIds") Collection<UUID> childIds);

    /** 첫 활동에 역할극 시나리오를 붙인다. 이미 붙어 있으면 바꾸지 않고 0을 돌려준다. scenario_source 는 값이 정해지지 않아 비워 둔다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value =
                    "update activity set scenario_id = :scenarioId"
                            + " where activity_id = :activityId and scenario_id is null",
            nativeQuery = true)
    int attachScenarioIfEmpty(
            @Param("activityId") UUID activityId, @Param("scenarioId") UUID scenarioId);

    /** 아동 삭제(ADR 2026-10-04 D1). 배정 문항·결과를 먼저 지운 뒤 부른다. */
    @Modifying
    @Query(value = "delete from activity where child_id in (:childIds)", nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
