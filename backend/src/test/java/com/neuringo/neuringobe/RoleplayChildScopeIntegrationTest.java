package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@TestPropertySource(
        properties = {
            "roleplay.retention.cleanup-enabled=false",
            "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long"
        })
class RoleplayChildScopeIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ChildAccessCodeService accessCodes;
    @Autowired RoleplayRequestScopeService scopes;

    @Test
    void existingHttpEntryCreatesPrincipalThatLoadsOnlyOwnRoleplayScope() throws Exception {
        var f = fixture();
        var auth = enter(f);
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(new ChildPrincipal(f.child));
        var scope = scopes.loadForChild(auth, f.activity, f.session);
        assertThat(scope.childId()).isEqualTo(f.child);
        assertThat(scope.activityId()).isEqualTo(f.activity);
        assertThat(scope.sessionId()).isEqualTo(f.session);
        assertThat(scope.acceptsNewTurn()).isTrue();
    }

    @Test
    void validChildSessionCannotUseAnotherChildActivityOrSession() throws Exception {
        var own = fixture();
        var other = fixture();
        var auth = enter(own);
        assertThatThrownBy(() -> scopes.loadForChild(auth, other.activity, other.session))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> scopes.loadForChild(auth, own.activity, other.session))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void childPausedAfterHttpEntryLosesRoleplayAccess() throws Exception {
        var f = fixture();
        var auth = enter(f);
        jdbc.update("update child set status='PAUSED' where child_id=?", f.child);
        assertThatThrownBy(() -> scopes.loadForChild(auth, f.activity, f.session))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void instructorEvenWithAddedChildAuthorityCannotSubmitAsAChild() {
        var f = fixture();
        var auth =
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(f.instructor, UUID.randomUUID(), UserRole.INSTRUCTOR),
                        null,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_INSTRUCTOR"),
                                new SimpleGrantedAuthority("ROLE_CHILD")));
        assertThatThrownBy(() -> scopes.loadForChild(auth, f.activity, f.session))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                            assertThat(error.getCode()).isEqualTo("ACCESS_DENIED");
                        });
    }

    @Test
    void existingChildEntryRequiresCsrf() throws Exception {
        var f = fixture();
        var code =
                accessCodes.issue(f.child, UUID.randomUUID(), f.instructor).response().accessCode();
        mvc.perform(
                        post("/api/v1/child-access-sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"accessCode\":\"" + code + "\"}"))
                .andExpect(status().isForbidden());
    }

    private Authentication enter(Fixture f) throws Exception {
        var code =
                accessCodes.issue(f.child, UUID.randomUUID(), f.instructor).response().accessCode();
        var result =
                mvc.perform(
                                post("/api/v1/child-access-sessions")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"accessCode\":\"" + code + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return ((SecurityContext)
                        session.getAttribute(
                                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .getAuthentication();
    }

    private record Fixture(
            UUID session, UUID activity, UUID child, UUID scenario, UUID instructor) {}

    private Fixture fixture() {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID(),
                session = UUID.randomUUID(),
                scenario = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)",
                user,
                user + "@example.com",
                "test-hash",
                now);
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,?)",
                goal,
                child,
                user,
                "a".repeat(64),
                now);
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,status,assigned_at) values(?,?,?,?,'IN_PROGRESS',?)",
                activity,
                child,
                goal,
                scenario,
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                session,
                activity,
                child,
                scenario,
                now);
        return new Fixture(session, activity, child, scenario, user);
    }
}
