package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side scope only. authenticatedChildId must come from verified authentication, never a
 * request body.
 */
@Service
public class RoleplayRequestScopeService {
    private final ChildRepository children;
    private final ActivityRepository activities;
    private final RoleplaySessionRepository sessions;

    public RoleplayRequestScopeService(
            ChildRepository children,
            ActivityRepository activities,
            RoleplaySessionRepository sessions) {
        this.children = children;
        this.activities = activities;
        this.sessions = sessions;
    }

    /** Child input boundary: derive child ID only from the verified session principal. */
    @Transactional(readOnly = true)
    public Scope loadForChild(Authentication authentication, UUID activityId, UUID sessionId) {
        return load(requireChildId(authentication), activityId, sessionId);
    }

    @Transactional(readOnly = true)
    public Scope loadForChildSession(Authentication authentication, UUID sessionId) {
        UUID childId = requireChildId(authentication);
        var session =
                sessions.findBySessionIdAndChildId(Objects.requireNonNull(sessionId), childId)
                        .orElseThrow(RoleplayRequestScopeService::hidden);
        return load(childId, session.getActivityId(), sessionId);
    }

    private UUID requireChildId(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
        }
        if (!(authentication.getPrincipal() instanceof ChildPrincipal child)
                || child.childId() == null
                || authentication.getAuthorities().stream()
                        .noneMatch(a -> "ROLE_CHILD".equals(a.getAuthority()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다.");
        }
        return child.childId();
    }

    /**
     * Reads a snapshot without holding DB locks during AI calls. Checkpoint commit rechecks the
     * session version.
     */
    @Transactional(readOnly = true)
    public Scope load(UUID authenticatedChildId, UUID activityId, UUID sessionId) {
        Objects.requireNonNull(authenticatedChildId);
        Objects.requireNonNull(activityId);
        Objects.requireNonNull(sessionId);
        var child =
                children.findById(authenticatedChildId)
                        .filter(found -> found.getStatus() == ChildStatus.ACTIVE)
                        .orElseThrow(RoleplayRequestScopeService::hidden);
        var activity =
                activities
                        .findById(activityId)
                        .filter(found -> found.getChildId().equals(authenticatedChildId))
                        .orElseThrow(RoleplayRequestScopeService::hidden);
        var session =
                sessions.findOwned(sessionId, authenticatedChildId, activityId)
                        .orElseThrow(RoleplayRequestScopeService::hidden);
        if (!session.getScenarioId().equals(activity.getScenarioId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ROLEPLAY_SCENARIO_CONFLICT", "활동과 역할극 시나리오가 일치하지 않습니다.");
        }
        return new Scope(
                authenticatedChildId,
                child.getClassId(),
                activityId,
                activity.getGoalId(),
                sessionId,
                session.getScenarioId(),
                session.getScenarioVersion(),
                session.getRowVersion(),
                session.getLastTurnNumber(),
                session.getCurrentMicroGoalId(),
                session.getCurrentSupportLevel(),
                activity.getStatus() == ActivityStatus.IN_PROGRESS
                        && "IN_PROGRESS".equals(session.getStatus()));
    }

    private static ResourceNotFoundException hidden() {
        return new ResourceNotFoundException("ROLEPLAY_NOT_FOUND", "역할극을 찾을 수 없습니다.");
    }

    /** Internal snapshot, not an HTTP response or a complete approved prompt context. */
    public record Scope(
            UUID childId,
            UUID classId,
            UUID activityId,
            UUID goalId,
            UUID sessionId,
            UUID scenarioId,
            int scenarioVersion,
            long checkpointVersion,
            int lastTurnNumber,
            UUID currentMicroGoalId,
            String currentSupportLevel,
            boolean acceptsNewTurn) {
        public AiTraceContext trace(UUID requestId, UUID turnId) {
            return new AiTraceContext(
                    requestId,
                    activityId,
                    classId,
                    childId,
                    scenarioId,
                    scenarioVersion,
                    sessionId,
                    Objects.requireNonNull(turnId),
                    null,
                    null);
        }
    }
}
