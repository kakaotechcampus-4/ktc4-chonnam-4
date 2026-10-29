package com.neuringo.neuringobe.roleplay.repository;

import com.neuringo.neuringobe.roleplay.domain.RoleplayStartMessage;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleplayStartMessageRepository extends JpaRepository<RoleplayStartMessage, UUID> {
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "insert into roleplay_start_message (session_id, scenario_id, scenario_version, message_id, opening_text, created_at) values (:session, :scenario, :version, :message, :text, :now)",
            nativeQuery = true)
    int insert(
            @Param("session") UUID sessionId,
            @Param("scenario") UUID scenarioId,
            @Param("version") int version,
            @Param("message") UUID messageId,
            @Param("text") String text,
            @Param("now") Instant now);
}
