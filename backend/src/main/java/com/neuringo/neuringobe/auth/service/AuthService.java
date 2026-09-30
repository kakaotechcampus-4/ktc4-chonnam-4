package com.neuringo.neuringobe.auth.service;

import com.neuringo.neuringobe.auth.domain.AuthSession;
import com.neuringo.neuringobe.auth.dto.LoginRequest;
import com.neuringo.neuringobe.auth.dto.LoginResponse;
import com.neuringo.neuringobe.auth.repository.AuthSessionRepository;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.validation.TextRules;
import com.neuringo.neuringobe.user.domain.AccountStatus;
import com.neuringo.neuringobe.user.domain.UserAccount;
import com.neuringo.neuringobe.user.dto.UserResponse;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 강사 로그인 세션을 발급·검증·폐기한다(DEC-001 2·3절). */
@Service
@Transactional(readOnly = true)
public class AuthService {

    // 고정 만료, 요청이 와도 연장하지 않는다(DEC-001 3절).
    static final Duration SESSION_TTL = Duration.ofHours(12);

    private static final int TOKEN_BYTES = 32; // 256비트

    private final UserAccountRepository userAccountRepository;
    private final AuthSessionRepository authSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    // 없는 이메일로 로그인할 때도 비밀번호 비교를 한 번 해서, 응답 시간으로 가입 여부를 추측할 수 없게 한다.
    // 같은 인코더로 만들어야 비용(cost)이 같아 비교 시간이 비슷하다.
    private final String dummyPasswordHash;

    public AuthService(
            UserAccountRepository userAccountRepository,
            AuthSessionRepository authSessionRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.authSessionRepository = authSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        // 제어 문자가 든 이메일은 가입 검증을 통과할 수 없어 계정이 있을 수 없다. DB 로 보내면 U+0000 에서 500 이 나므로 조회하지 않고
        // 없는 계정으로 처리한다(가짜 해시 비교는 그대로 해서 응답 시간 차이를 만들지 않는다).
        Optional<UserAccount> found =
                TextRules.containsControlCharacter(request.email())
                        ? Optional.empty()
                        : userAccountRepository.findByEmail(
                                UserAccount.normalizeEmail(request.email()));

        String hashToCompare = found.map(UserAccount::getPasswordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(request.password(), hashToCompare);
        if (found.isEmpty() || !matches) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        UserAccount user = found.get();
        // 로그인할 때마다 새 무작위 토큰을 발급한다. 클라이언트가 보낸 값을 세션 식별자로 쓰지 않으므로 세션 고정이 생기지 않는다.
        String token = newToken();
        Instant now = clock.instant();
        AuthSession session =
                new AuthSession(
                        UUID.randomUUID(),
                        user.getUserId(),
                        sha256Hex(token),
                        now.plus(SESSION_TTL),
                        now);
        authSessionRepository.save(session);

        return new LoginResponse(token, session.getExpiresAt(), UserResponse.from(user));
    }

    /**
     * 토큰 원문으로 유효한 세션을 찾는다. 세션이 없거나(폐기 포함) 만료됐으면 빈 값을 돌려준다. 어떤 이유로 실패했는지는 구분하지 않는다(모두 {@code
     * INVALID_TOKEN}).
     */
    public Optional<AuthenticatedUser> authenticate(String rawToken) {
        return authSessionRepository
                .findByTokenHash(sha256Hex(rawToken))
                .filter(session -> !session.isExpiredAt(clock.instant()))
                .flatMap(
                        session ->
                                userAccountRepository
                                        .findById(session.getUserId())
                                        .filter(user -> user.getStatus() == AccountStatus.ACTIVE)
                                        .map(
                                                user ->
                                                        new AuthenticatedUser(
                                                                session.getUserId(),
                                                                session.getSessionId(),
                                                                user.getRole())));
    }

    /** 현재 세션만 폐기한다. 같은 강사의 다른 기기 세션은 그대로 둔다. */
    @Transactional
    public void logout(UUID sessionId) {
        authSessionRepository.deleteById(sessionId);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 토큰은 256비트 무작위 값이라 솔트·느린 해시가 필요 없다. 빠른 SHA-256 으로 매 요청 조회 비용을 낮춘다.
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }
}
