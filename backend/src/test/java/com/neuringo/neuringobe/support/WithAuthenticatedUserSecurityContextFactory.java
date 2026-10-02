package com.neuringo.neuringobe.support;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

public class WithAuthenticatedUserSecurityContextFactory
        implements WithSecurityContextFactory<WithAuthenticatedUser> {

    @Override
    public SecurityContext createSecurityContext(WithAuthenticatedUser annotation) {
        AuthenticatedUser principal =
                new AuthenticatedUser(
                        UUID.fromString(annotation.userId()),
                        UUID.fromString(annotation.sessionId()),
                        UserRole.INSTRUCTOR);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR"))));
        return context;
    }
}
