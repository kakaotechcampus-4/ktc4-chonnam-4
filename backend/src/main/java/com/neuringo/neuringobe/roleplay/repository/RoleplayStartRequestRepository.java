package com.neuringo.neuringobe.roleplay.repository;

import com.neuringo.neuringobe.roleplay.domain.RoleplayStartRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleplayStartRequestRepository extends JpaRepository<RoleplayStartRequest, UUID> {
    /** 키 경합으로 기존 연결을 덮어쓰지 않도록 merge 대신 INSERT만 수행한다. */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "insert into roleplay_start_request (request_key, activity_id, child_id, session_id, created_at) values (:key, :activity, :child, :session, :now)",
            nativeQuery = true)
    int insert(
            @Param("key") UUID requestKey,
            @Param("activity") UUID activityId,
            @Param("child") UUID childId,
            @Param("session") UUID sessionId,
            @Param("now") Instant now);
}
