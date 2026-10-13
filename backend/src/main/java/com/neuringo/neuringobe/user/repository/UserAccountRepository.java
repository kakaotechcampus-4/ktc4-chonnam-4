package com.neuringo.neuringobe.user.repository;

import com.neuringo.neuringobe.user.domain.UserAccount;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** 강사 탈퇴. 동의 기록도 같은 행이라 함께 사라진다. */
    @Modifying
    @Query(value = "delete from user_account where user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * 강사 탈퇴가 맨 먼저 잡는 계정 잠금. PostgreSQL 에서 {@code FOR NO KEY UPDATE} 가 되어, 로그인의 {@link
     * #findForShare} 가 탈퇴 커밋까지 기다리게 한다. 잠금 순서는 계정 → 학급 → 활동 → 아동 → 역할극 세션이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.userId = :userId")
    Optional<UserAccount> findForUpdate(@Param("userId") UUID userId);

    /**
     * 로그인이 세션을 저장하기 직전에 계정을 다시 읽는다({@code FOR SHARE}). 세션 INSERT 의 FK 검사({@code KEY SHARE})만으로는 탈퇴
     * 잠금을 기다리지 않아, 탈퇴가 세션을 지운 틈에 새 세션이 들어가 계정 삭제가 FK 에 막힌다. 이 잠금으로 탈퇴가 끝나기를 기다리고, 그 사이 계정이 지워졌으면 빈
     * 값을 받는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from UserAccount u where u.userId = :userId")
    Optional<UserAccount> findForShare(@Param("userId") UUID userId);

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
                        (user_id, email, password_hash, name, org_name, role, status, created_at,
                         terms_version, privacy_version, consented_at)
                    VALUES
                        (:userId, :email, :passwordHash, :name, :orgName, :role, :status, :createdAt,
                         :termsVersion, :privacyVersion, :consentedAt)
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
            @Param("createdAt") Instant createdAt,
            @Param("termsVersion") String termsVersion,
            @Param("privacyVersion") String privacyVersion,
            @Param("consentedAt") Instant consentedAt);
}
