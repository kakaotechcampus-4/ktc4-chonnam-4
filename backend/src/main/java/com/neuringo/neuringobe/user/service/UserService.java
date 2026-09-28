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
import org.springframework.dao.DataIntegrityViolationException;
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
        if (userAccountRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }

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

        try {
            // 같은 이메일 가입이 동시에 들어오면 위 검사를 둘 다 통과할 수 있다. DB UNIQUE 위반을 여기서 409 로 바꾼다.
            return UserResponse.from(userAccountRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            throw duplicateEmail();
        }
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
