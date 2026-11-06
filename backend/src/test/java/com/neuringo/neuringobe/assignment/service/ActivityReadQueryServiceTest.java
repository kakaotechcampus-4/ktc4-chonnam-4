package com.neuringo.neuringobe.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ActivityReadQueryServiceTest {
    @Test
    void unsupportedOrMismatchedAuthenticationCannotFallBackToInstructorResponse() {
        var activities = mock(ActivityRepository.class);
        var access = mock(ChildAccessScope.class);
        var readiness = mock(ActivityReadinessQueryService.class);
        var quiz = mock(QuizAssignmentService.class);
        var service = new ActivityReadQueryService(activities, access, readiness, quiz);
        var child = new ChildPrincipal(UUID.randomUUID());
        var instructor =
                new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), UserRole.INSTRUCTOR);
        var childRole = List.of(new SimpleGrantedAuthority("ROLE_CHILD"));
        var instructorRole = List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR"));
        for (var authentication :
                Arrays.asList(
                        null,
                        UsernamePasswordAuthenticationToken.unauthenticated(child, null),
                        UsernamePasswordAuthenticationToken.authenticated(
                                "unknown", null, instructorRole),
                        UsernamePasswordAuthenticationToken.authenticated(
                                child, null, instructorRole),
                        UsernamePasswordAuthenticationToken.authenticated(
                                instructor, null, childRole),
                        UsernamePasswordAuthenticationToken.authenticated(
                                new ChildPrincipal(null), null, childRole))) {
            assertThatThrownBy(() -> service.list(UUID.randomUUID(), null, 0, 20, authentication))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            failure -> {
                                assertThat(failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                                assertThat(failure.getCode()).isEqualTo("ACCESS_DENIED");
                            });
        }
        verifyNoInteractions(activities, access, readiness, quiz);
    }
}
