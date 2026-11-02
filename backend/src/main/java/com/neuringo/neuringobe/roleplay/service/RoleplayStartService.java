package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.roleplay.domain.RoleplaySession;
import com.neuringo.neuringobe.roleplay.dto.RoleplaySessionState;
import com.neuringo.neuringobe.roleplay.dto.RoleplayStartResponse;
import com.neuringo.neuringobe.roleplay.repository.RoleplaySessionRepository;
import com.neuringo.neuringobe.roleplay.repository.RoleplayStartRequestRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RoleplayStartService {
    private final RoleplayRequestScopeService scopes;
    private final InitialRoleplayStartQueryService initialInputs;
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final RoleplaySessionRepository sessions;
    private final RoleplayStartRequestRepository requests;
    private final ScenarioRepository scenarios;
    private final TransactionTemplate transaction;
    private final RoleplayStartDeliveryService delivery;

    public RoleplayStartService(
            RoleplayRequestScopeService scopes,
            InitialRoleplayStartQueryService initialInputs,
            ActivityRepository activities,
            ChildRepository children,
            RoleplaySessionRepository sessions,
            RoleplayStartRequestRepository requests,
            ScenarioRepository scenarios,
            RoleplayStartDeliveryService delivery,
            PlatformTransactionManager manager) {
        this.scopes = scopes;
        this.initialInputs = initialInputs;
        this.activities = activities;
        this.children = children;
        this.sessions = sessions;
        this.requests = requests;
        this.scenarios = scenarios;
        this.delivery = delivery;
        transaction = new TransactionTemplate(manager);
    }

    public StartResult start(UUID activityId, UUID requestKey, Authentication authentication) {
        return execute(activityId, requestKey, authentication, Function.identity());
    }

    public DeliveryResult startForDelivery(
            UUID activityId, UUID requestKey, Authentication authentication) {
        return execute(
                activityId,
                requestKey,
                authentication,
                result -> new DeliveryResult(delivery.require(result.session()), result.created()));
    }

    private <T> T execute(
            UUID activityId,
            UUID requestKey,
            Authentication authentication,
            Function<StartResult, T> projection) {
        UUID childId = scopes.requireChildId(authentication);
        Objects.requireNonNull(requestKey);
        try {
            return transaction.execute(
                    status ->
                            projection.apply(
                                    startOnce(activityId, childId, requestKey, authentication)));
        } catch (DataIntegrityViolationException exception) {
            // 다른 활동의 동일 키 경합도 전체 롤백 뒤 기존 충돌 응답으로 통일한다.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "roleplay_start_request_pkey".equals(violation.getConstraintName())) {
                    throw keyConflict();
                }
            }
            throw exception;
        }
    }

    private StartResult startOnce(
            UUID activityId, UUID childId, UUID requestKey, Authentication authentication) {
        var activity =
                activities
                        .findByIdForUpdate(activityId)
                        .filter(found -> found.getChildId().equals(childId))
                        .orElseThrow(this::hidden);
        children.findByIdForUpdate(childId)
                .filter(child -> child.getStatus() == ChildStatus.ACTIVE)
                .orElseThrow(this::hidden);
        var prior = requests.findById(requestKey).orElse(null);
        if (prior != null && !prior.matches(activityId, childId)) {
            throw keyConflict();
        }
        var existing = sessions.findForActivityForUpdate(activityId, childId);
        if (existing.size() > 1) {
            // 기존 데이터를 임의로 합치거나 하나를 선택하지 않는다.
            throw new ApiException(
                    HttpStatus.CONFLICT, "ROLEPLAY_SESSION_AMBIGUOUS", "연결된 역할극 세션을 확인해야 합니다.");
        }
        Instant now = Instant.now();
        if (!existing.isEmpty()) {
            var session = existing.getFirst();
            if (!session.getScenarioId().equals(activity.getScenarioId())
                    || (prior != null && !prior.getSessionId().equals(session.getSessionId()))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ROLEPLAY_SCENARIO_CONFLICT",
                        "활동과 역할극 시나리오가 일치하지 않습니다.");
            }
            if (prior == null) {
                requests.insert(requestKey, activityId, childId, session.getSessionId(), now);
            }
            return new StartResult(RoleplaySessionState.from(session, activity.getStatus()), false);
        }
        if (activity.getStatus() != ActivityStatus.NOT_STARTED
                && activity.getStatus() != ActivityStatus.IN_PROGRESS) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INITIAL_ROLEPLAY_NOT_READY",
                    "현재 활동 상태에서는 역할극을 시작할 수 없습니다.");
        }
        var input = initialInputs.requireForActivity(activityId, authentication);
        scenarios
                .lockApprovedVersion(input.scenario().scenarioId(), input.scenario().version())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.CONFLICT,
                                        "INITIAL_ROLEPLAY_NOT_READY",
                                        "역할극 승인 버전을 확인할 수 없습니다."));
        var session =
                sessions.saveAndFlush(
                        RoleplaySession.start(
                                UUID.randomUUID(),
                                activityId,
                                childId,
                                input.scenario().scenarioId(),
                                input.scenario().version(),
                                input.initialDifficulty().initialSupportLevel(),
                                scenarios
                                        .findApprovedOpeningFocus(
                                                input.scenario().scenarioId(),
                                                input.scenario().version(),
                                                input.initialDifficulty().initialSupportLevel())
                                        .orElse(null),
                                now));
        activity.start(now);
        requests.insert(requestKey, activityId, childId, session.getSessionId(), now);
        return new StartResult(RoleplaySessionState.from(session, activity.getStatus()), true);
    }

    private ApiException keyConflict() {
        return new ApiException(
                HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 역할극 시작 요청에 사용한 키입니다.");
    }

    private ResourceNotFoundException hidden() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }

    public record StartResult(RoleplaySessionState session, boolean created) {}

    public record DeliveryResult(RoleplayStartResponse response, boolean created) {}
}
