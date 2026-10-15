package com.neuringo.neuringobe.auth.repository;

import com.neuringo.neuringobe.auth.domain.AuthSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenHash(String tokenHash);

    /** 강사 탈퇴. 그 강사의 모든 세션을 지워 기존 토큰을 바로 막는다. */
    @Modifying
    @Query(value = "delete from auth_session where user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);
}
