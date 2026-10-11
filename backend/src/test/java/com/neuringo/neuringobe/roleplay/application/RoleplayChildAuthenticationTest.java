package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class RoleplayChildAuthenticationTest {
    private final ChildRepository children = mock(ChildRepository.class);
    private final ActivityRepository activities = mock(ActivityRepository.class);
    private final RoleplaySessionRepository sessions = mock(RoleplaySessionRepository.class);
    private final RoleplayRequestScopeService scopes =
            new RoleplayRequestScopeService(children, activities, sessions);

    @Test
    void missingUnauthenticatedAndAnonymousCannotReadAnyScope() {
        reject(null, HttpStatus.UNAUTHORIZED);
        reject(
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(UUID.randomUUID()), null),
                HttpStatus.UNAUTHORIZED);
        reject(
                new AnonymousAuthenticationToken(
                        "test", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))),
                HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(children, activities, sessions);
    }

    @Test
    void roleLabelCannotSubstituteForVerifiedChildPrincipal() {
        reject(
                new UsernamePasswordAuthenticationToken(
                        "untrusted child id",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD"))),
                HttpStatus.FORBIDDEN);
        reject(
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(null),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD"))),
                HttpStatus.FORBIDDEN);
        verifyNoInteractions(children, activities, sessions);
    }

    @Test
    void childPrincipalWithoutChildRoleCannotReadScope() {
        reject(
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(UUID.randomUUID()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR"))),
                HttpStatus.FORBIDDEN);
        verifyNoInteractions(children, activities, sessions);
    }

    private void reject(org.springframework.security.core.Authentication auth, HttpStatus status) {
        assertThatThrownBy(() -> scopes.loadForChild(auth, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(status));
    }
}
