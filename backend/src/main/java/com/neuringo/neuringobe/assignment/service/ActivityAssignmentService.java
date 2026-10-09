package com.neuringo.neuringobe.assignment.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.activity.dto.CreateActivityRequest;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.assignment.dto.ActivityDetailResponse;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
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
 * 활동 배정(ADR 2026-10-03 D4). 활동과 최초 퀴즈 문항을 한 트랜잭션에서 만든다. 문항은 서버가 고르고 강사는 고르지 않는다.
 *
 * <p>활동·목표·퀴즈 도메인을 함께 쓰므로 어느 한 도메인에 두면 도메인 사이 순환이 생긴다(ArchitectureTest). 그래서 이 조립만 따로 둔다.
 */
@Service
public class ActivityAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ActivityAssignmentService.class);

    // V6 의 제약 이름. 바꾸면 여기도 같이 바꾼다.
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uq_activity_idempotency_key";
    private static final String NOT_STARTED_GOAL_CONSTRAINT = "uq_activity_not_started_child_goal";

    private final ActivityRepository activities;
    private final ChildRepository children;
    private final LearningGoalRepository goals;
    private final ChildAccessScope access;
    private final QuizAssignmentService quizAssignment;
    private final TransactionTemplate transaction;

    public ActivityAssignmentService(
            ActivityRepository activities,
            ChildRepository children,
            LearningGoalRepository goals,
            ChildAccessScope access,
            QuizAssignmentService quizAssignment,
            PlatformTransactionManager transactionManager) {
        this.activities = activities;
        this.children = children;
        this.goals = goals;
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
            if (NOT_STARTED_GOAL_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw duplicateAssignment();
            }
            throw ex;
        }
    }

    /**
     * 같은 아동의 배정은 아동 행을 잠가 하나씩 처리한다. 그래서 같은 요청 키의 재전송과 같은 내용 목표의 중복 배정을 앞에서 확인해도 경합이 없다. 내용 기준 중복은 두
     * 테이블에 걸쳐 DB 제약으로 걸 수 없어 이 잠금이 유일한 방어선이다. DB 의 UNIQUE(요청 키)·부분 UNIQUE(시작 전 아동·목표 ID)는 같은 목표 행이
     * 두 번 배정되는 경우만 막는 마지막 장치다.
     */
    private Assignment assignOnce(
            CreateActivityRequest request, UUID requestKey, Authentication authentication) {
        access.requireInstructor(authentication, request.childId());
        Child child =
                children.findByIdForUpdate(request.childId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."));

        Activity prior = activities.findByIdempotencyKey(requestKey).orElse(null);
        if (prior != null) {
            if (!prior.getChildId().equals(request.childId())
                    || !prior.getGoalId().equals(request.goalId())) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "다른 배정 요청에 사용한 키입니다.");
            }
            return new Assignment(ActivityResponse.from(prior), false);
        }

        if (child.getStatus() != ChildStatus.ACTIVE) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "CHILD_NOT_ACTIVE", "참여 중인 아동에게만 활동을 배정할 수 있습니다.");
        }
        LearningGoal goal =
                goals.findById(request.goalId())
                        .filter(found -> found.getChildId().equals(request.childId()))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다."));
        // 같은 목표는 목표 ID 가 아니라 내용으로 판단한다. 마법사는 배정할 때마다 목표를 새로 저장하므로, 강사가 같은 목표를 다시 고르면
        // ID 는 달라도 content_hash 는 같다(ADR 2026-10-03 D6).
        List<UUID> sameGoals =
                goals.findByChildIdAndContentHash(request.childId(), goal.getContentHash()).stream()
                        .map(LearningGoal::getGoalId)
                        .toList();
        if (activities.existsByChildIdAndGoalIdInAndStatus(
                request.childId(), sameGoals, ActivityStatus.NOT_STARTED)) {
            throw duplicateAssignment();
        }

        List<QuizItem> picks = quizAssignment.pickInitialItems();
        if (picks.isEmpty()) {
            // 운영자가 문항 풀을 채워야 하는 상황이다. 아동 정보는 남기지 않는다.
            log.warn("배정할 승인 퀴즈 문항이 없어 활동을 만들지 않았습니다.");
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "QUIZ_ITEMS_UNAVAILABLE",
                    "배정할 수 있는 승인된 퀴즈 문항이 없습니다.");
        }

        Activity created =
                activities.saveAndFlush(
                        new Activity(
                                UUID.randomUUID(),
                                request.childId(),
                                request.goalId(),
                                requestKey,
                                Instant.now()));
        quizAssignment.attach(created.getActivityId(), picks);
        return new Assignment(ActivityResponse.from(created), true);
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
                HttpStatus.CONFLICT, "DUPLICATE_ASSIGNMENT", "같은 목표로 시작하지 않은 활동이 이미 있습니다.");
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
