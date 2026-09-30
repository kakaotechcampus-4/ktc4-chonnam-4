package com.neuringo.neuringobe.user.service;

import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.user.domain.AccountStatus;
import com.neuringo.neuringobe.user.domain.UserAccount;
import com.neuringo.neuringobe.user.domain.UserRole;
import com.neuringo.neuringobe.user.dto.SignupRequest;
import com.neuringo.neuringobe.user.dto.UserResponse;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 공개 가입. 역할은 강사로 고정한다(DEC-001 6절, 경로는 `제안`). */
    @Transactional
    public UserResponse signup(SignupRequest request) {
        String email = UserAccount.normalizeEmail(request.email());

        UserAccount user =
                new UserAccount(
                        UUID.randomUUID(),
                        email,
                        passwordEncoder.encode(request.password()),
                        request.name().trim(),
                        blankToNull(request.orgName()),
                        UserRole.INSTRUCTOR,
                        AccountStatus.ACTIVE,
                        clock.instant());

        // 먼저 조회하고 저장하면 동시 가입이 조회를 함께 통과할 수 있어, 중복 판단을 DB UNIQUE 하나에 맡긴다.
        // 충돌을 예외가 아닌 0 행으로 받아 이메일 원문이 DB 오류 로그에 남지 않게 한다(insertIfEmailAbsent 참고).
        int inserted =
                userAccountRepository.insertIfEmailAbsent(
                        user.getUserId(),
                        user.getEmail(),
                        user.getPasswordHash(),
                        user.getName(),
                        user.getOrgName(),
                        user.getRole().name(),
                        user.getStatus().name(),
                        user.getCreatedAt());
        if (inserted == 0) {
            throw duplicateEmail();
        }
        return UserResponse.from(user);
    }

    public UserResponse get(UUID userId) {
        return userAccountRepository
                .findById(userId)
                .map(UserResponse::from)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "USER_NOT_FOUND", "사용자를 찾을 수 없습니다: " + userId));
    }

    // 이메일 원문은 개인정보라 메시지에 담지 않는다.
    private ApiException duplicateEmail() {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "이미 가입된 이메일입니다.");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
