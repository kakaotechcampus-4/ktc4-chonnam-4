package com.neuringo.neuringobe.auth.repository;

import com.neuringo.neuringobe.auth.domain.AuthSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenHash(String tokenHash);
}
