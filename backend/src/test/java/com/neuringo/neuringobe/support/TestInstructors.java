package com.neuringo.neuringobe.support;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.user.domain.AccountStatus;
import com.neuringo.neuringobe.user.domain.UserAccount;
import com.neuringo.neuringobe.user.domain.UserRole;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

public final class TestInstructors {
    private TestInstructors() {}

    public static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    public static RequestPostProcessor instructor(String name) {
        var principal = new AuthenticatedUser(id(name), UUID.randomUUID(), UserRole.INSTRUCTOR);
        var authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")));
        return SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }

    public static void save(UserAccountRepository users, String name) {
        UUID userId = id(name);
        if (!users.existsById(userId)) {
            users.save(
                    new UserAccount(
                            userId,
                            name + "@example.test",
                            "test-password-hash",
                            name,
                            null,
                            UserRole.INSTRUCTOR,
                            AccountStatus.ACTIVE,
                            Instant.now()));
        }
    }
}
