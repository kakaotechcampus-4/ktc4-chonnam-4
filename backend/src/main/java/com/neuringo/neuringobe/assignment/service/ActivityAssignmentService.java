package com.neuringo.neuringobe.assignment.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.assignment.dto.ActivityDetailResponse;
import com.neuringo.neuringobe.assignment.dto.CreateActivityRequest;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 활동 배정(ADR 2026-10-03 D4). 학습 목표와 활동, 최초 퀴즈 문항을 한 트랜잭션에서 만든다. 문항은 서버가 고르고 강사는 고르지 않는다. 배정이 어느 단계에서
 * 실패해도 목표만 남지 않는다(PR #49 리뷰).
 *
 * <p>활동·목표·퀴즈 도메인을 함께 쓰므로 어느 한 도메인에 두면 도메인 사이 순환이 생긴다(ArchitectureTest). 그래서 이 조립만 따로 둔다.
 */
@Service
public class ActivityAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ActivityAssignmentService.class);

    // V6·V10 의 제약 이름. 바꾸면 여기도 같이 바꾼다.
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uq_activity_idempotency_key";
    private static final String NOT_STARTED_TYPE_CONSTRAINT = "uq_activity_not_started_child_type";

    private final ActivityRepository activities;
    private final ChildRepository children;
    private final LearningGoalRepository goals;
    private final LearningGoalService goalService;
    private final ChildAccessScope access;
    private final QuizAssignmentService quizAssignment;
    private final TransactionTemplate transaction;

    public ActivityAssignmentService(
            ActivityRepository activities,
            ChildRepository children,
            LearningGoalRepository goals,
            LearningGoalService goalService,
            ChildAccessScope access,
            QuizAssignmentService quizAssignment,
            PlatformTransactionManager transactionManager) {
        this.activities = activities;
        this.children = children;
        this.goals = goals;
        this.goalService = goalService;
        this.access = access;
        this.quizAssignment = quizAssignment;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 잠금은 아동별이지만 요청 키 UNIQUE 는 전체에 걸린다. 서로 다른 아동에게 같은 키가 동시에 오면 둘 다 "키 없음"을 보고 저장하다 한쪽이 UNIQUE 충돌로
     * 실패한다. 그 충돌은 트랜잭션이 롤백된 뒤 여기서 순차 요청과 같은 409 로 바꾼다(실패한 트랜잭션 안에서 이어 가지 않는다).
     */
    public Assignment assign(
            CreateActivityRequest request, UUID requestKey, Authentication authentication) {
        try {
            return transaction.execute(ignored -> assignOnce(request, requestKey, authentication));
        } catch (DataIntegrityViolationException ex) {
            String constraint = violatedConstraint(ex);
            if (IDEMPOTENCY_KEY_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 배정 요청에 사용한 키입니다.");
            }
            if (NOT_STARTED_TYPE_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw duplicateAssignment();
            }
            throw ex;
        }
    }

    /**
     * 같은 아동의 배정은 아동 행을 잠가 하나씩 처리한다. 그래서 같은 요청 키의 재전송, 최초 결합 1회, 같은 독립 퀴즈의 중복 배정을 앞에서 확인해도 경합이 없다.
     * 최초 결합 1회는 예전 활동이 남은 DB 에서 마이그레이션이 깨지지 않도록 인덱스를 두지 않아 이 잠금이 유일한 방어선이다. DB 의 UNIQUE(요청 키)·부분
     * UNIQUE(시작 전 아동·독립 퀴즈 유형)는 마지막 장치다.
     *
     * <p>유형 규칙: 최초 결합은 아동당 한 번이고, 그것을 마친 뒤에만 다른 유형을 배정한다. 최초 결합·역할극은 목표가 있어야 하고 독립 퀴즈는 없어야 한다. 역할극은
     * 역할극 API 가 생기기 전까지 배정하지 않는다.
     *
     * <p>목표는 확인을 모두 통과한 뒤에 저장한다. 그 뒤에 실패해도 같은 트랜잭션이라 함께 롤백된다.
     */
    private Assignment assignOnce(
            CreateActivityRequest request, UUID requestKey, Authentication authentication) {
        ActivityType type = request.type();
        access.requireInstructor(authentication, request.childId());
        Child child =
                children.findByIdForUpdate(request.childId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."));
        if (type.requiresGoal() && request.goal() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "GOAL_REQUIRED", "이 활동에는 학습 목표가 필요합니다.");
        }
        if (!type.requiresGoal() && request.goal() != null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "GOAL_NOT_ALLOWED",
                    "표정 퀴즈 활동에는 학습 목표를 붙이지 않습니다.");
        }
        LearningGoal goal =
                request.goal() == null
                        ? null
                        : goalService.newGoal(
                                request.childId(), request.goal(), "goal.", authentication);

        Activity prior = activities.findByIdempotencyKey(requestKey).orElse(null);
        if (prior != null) {
            if (!prior.getChildId().equals(request.childId())
                    || prior.getActivityType() != type
                    || !sameGoal(prior, goal)) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 배정 요청에 사용한 키입니다.");
            }
            return new Assignment(ActivityResponse.from(prior), false);
        }

        if (child.getStatus() != ChildStatus.ACTIVE) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "CHILD_NOT_ACTIVE", "참여 중인 아동에게만 활동을 배정할 수 있습니다.");
        }
        if (type == ActivityType.ROLEPLAY) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "ACTIVITY_TYPE_NOT_AVAILABLE",
                    "역할극 활동은 아직 배정할 수 없습니다.");
        }
        if (type == ActivityType.FIRST_COMBINED) {
            if (activities.existsByChildIdAndActivityType(
                    request.childId(), ActivityType.FIRST_COMBINED)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "FIRST_ACTIVITY_EXISTS",
                        "최초 활동은 아동마다 한 번만 배정할 수 있습니다.");
            }
        } else {
            if (!activities.existsByChildIdAndActivityTypeAndStatus(
                    request.childId(), ActivityType.FIRST_COMBINED, ActivityStatus.COMPLETED)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "FIRST_ACTIVITY_NOT_COMPLETED",
                        "최초 활동을 마친 뒤에 배정할 수 있습니다.");
            }
            if (activities.existsByChildIdAndActivityTypeAndStatus(
                    request.childId(), type, ActivityStatus.NOT_STARTED)) {
                throw duplicateAssignment();
            }
        }

        List<QuizItem> picks = quizAssignment.pickItems(type);
        if (picks.isEmpty()) {
            // 팀이 문항 풀을 채워야 하는 상황이다. 아동 정보는 남기지 않는다.
            log.warn("배정할 승인 퀴즈 문항이 모자라 활동을 만들지 않았습니다: activityType={}", type);
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "QUIZ_ITEMS_UNAVAILABLE",
                    "배정할 수 있는 승인된 퀴즈 문항이 모자랍니다.");
        }

        UUID goalId = goal == null ? null : goals.save(goal).getGoalId();
        Activity created =
                activities.saveAndFlush(
                        new Activity(
                                UUID.randomUUID(),
                                request.childId(),
                                type,
                                goalId,
                                requestKey,
                                Instant.now()));
        quizAssignment.attach(created.getActivityId(), picks);
        return new Assignment(ActivityResponse.from(created), true);
    }

    /** 같은 요청 키의 재전송인지. 처음 배정한 목표와 이번 요청의 목표 내용이 같아야 한다. 독립 퀴즈는 둘 다 목표가 없다. */
    private boolean sameGoal(Activity prior, LearningGoal requested) {
        if (prior.getGoalId() == null || requested == null) {
            return prior.getGoalId() == null && requested == null;
        }
        return goals.findById(prior.getGoalId())
                .map(LearningGoal::getContentHash)
                .filter(requested.getContentHash()::equals)
                .isPresent();
    }

    @Transactional(readOnly = true)
    public ActivityDetailResponse detail(UUID activityId, Authentication authentication) {
        Activity activity =
                activities
                        .findById(activityId)
                        .orElseThrow(ActivityAssignmentService::activityNotFound);
        access.requireOwnerOrInstructor(
                authentication, activity.getChildId(), ActivityAssignmentService::activityNotFound);
        return ActivityDetailResponse.of(
                ActivityResponse.from(activity), quizAssignment.list(activityId, authentication));
    }

    private static ApiException duplicateAssignment() {
        return new ApiException(
                HttpStatus.CONFLICT, "DUPLICATE_ASSIGNMENT", "같은 활동이 시작 전 상태로 이미 있습니다.");
    }

    /** 충돌한 DB 제약 이름. 제약 위반이 아니거나 이름을 모르면 null. */
    private static String violatedConstraint(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    /** created 가 false 면 같은 요청 키로 이미 만든 활동을 돌려준 것이다. */
    public record Assignment(ActivityResponse activity, boolean created) {}

    private static ResourceNotFoundException activityNotFound() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }
}
