package com.neuringo.neuringobe.user.repository;

import com.neuringo.neuringobe.user.domain.UserAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    // email 은 정규화(trim·소문자)한 값으로 조회한다.
    Optional<UserAccount> findByEmail(String email);

    /**
     * 이메일이 없을 때만 계정을 저장하고, 저장한 행 수(1 또는 0)를 돌려준다.
     *
     * <p>save() 로 저장하면 동시 가입이 DB UNIQUE 에서 충돌할 때 Hibernate 가 DB 오류 상세("Key (email)=(...)")를 로그에 남겨,
     * 409 로 바꿔도 이메일 원문이 로그에 기록된다. ON CONFLICT DO NOTHING 은 충돌을 오류 없이 0 행으로 끝내므로 그 로그가 생기지 않는다. 중복
     * 방지는 그대로 DB UNIQUE 가 보장한다.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO user_account
                        (user_id, email, password_hash, name, org_name, role, status, created_at)
                    VALUES
                        (:userId, :email, :passwordHash, :name, :orgName, :role, :status, :createdAt)
                    ON CONFLICT (email) DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfEmailAbsent(
            @Param("userId") UUID userId,
            @Param("email") String email,
            @Param("passwordHash") String passwordHash,
            @Param("name") String name,
            @Param("orgName") String orgName,
            @Param("role") String role,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt);
}
