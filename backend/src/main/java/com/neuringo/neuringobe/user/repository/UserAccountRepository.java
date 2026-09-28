package com.neuringo.neuringobe.user.repository;

import com.neuringo.neuringobe.user.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    // email 은 정규화(trim·소문자)한 값으로 조회한다.
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);
}
