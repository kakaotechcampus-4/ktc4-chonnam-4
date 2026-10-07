package com.neuringo.neuringobe.quiz.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.dto.AssignQuizItemRequest;
import com.neuringo.neuringobe.quiz.dto.AssignedQuizItemResponse;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QuizAssignmentService {
    private final ActivityRepository activities;
    private final ActivityQuizRepository assignments;
    private final QuizItemRepository items;
    private final ChildAccessScope access;

    public QuizAssignmentService(
            ActivityRepository activities,
            ActivityQuizRepository assignments,
            QuizItemRepository items,
            ChildAccessScope access) {
        this.activities = activities;
        this.assignments = assignments;
        this.items = items;
        this.access = access;
    }

    @Transactional
    public AssignedQuizItemResponse assign(
            UUID activityId, AssignQuizItemRequest request, Authentication authentication) {
        Activity activity =
                activities
                        .findByIdForUpdate(activityId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        access.requireInstructor(authentication, activity.getChildId());
        if (activity.getStatus() != ActivityStatus.NOT_STARTED) {
            throw conflict("ACTIVITY_ALREADY_STARTED", "시작한 활동에는 문항을 배정할 수 없습니다.");
        }
        if (assignments.countByActivityId(activityId) >= 3) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "MAX_QUIZ_ITEMS_EXCEEDED",
                    "퀴즈 문항은 최대 3개입니다.");
        }
        QuizItem item =
                items.findById(request.itemId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "QUIZ_ITEM_NOT_FOUND", "퀴즈 문항을 찾을 수 없습니다."));
        if (!"APPROVED".equals(item.getStatus())
                || item.getItemVersion() != request.itemVersion()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "UNAPPROVED_QUIZ_ITEM",
                    "승인된 문항 버전만 배정할 수 있습니다.");
        }
        try {
            item.validateForAssignment();
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_QUIZ_ITEM", "퀴즈 문항 구성이 올바르지 않습니다.");
        }
        ActivityQuiz assignment =
                new ActivityQuiz(
                        UUID.randomUUID(),
                        activityId,
                        item.getItemId(),
                        item.getItemVersion(),
                        request.questionOrder());
        try {
            assignments.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("DUPLICATE_QUESTION_ORDER", "이미 배정된 문항 또는 순서입니다.");
        }
        return AssignedQuizItemResponse.from(assignment, item);
    }

    /**
     * 최초 퀴즈 문항을 고른다(ADR 2026-10-03 D4, 요구사항 v3 "시스템이 검증된 문항 풀에서 구성"). 유형마다 승인 문항 1개씩, 유형 순서대로 최대
     * 3개다. 승인 문항이 없는 유형은 건너뛴다(S1-BAE-02 Q2). 같은 유형의 승인 문항은 서로 바꿔 쓸 수 있다고 보고 그중 무작위로 하나를 고른다. 아동마다
     * 노출 문항이 퍼진다(QUIZ-11). 어떤 문항이 붙었는지는 activity_quiz 에 남는다.
     */
    public List<QuizItem> pickInitialItems() {
        return Arrays.stream(QuizType.values())
                .map(this::randomAssignable)
                .flatMap(Optional::stream)
                .toList();
    }

    /** 고른 문항을 순서대로 활동에 붙인다. 활동 생성과 같은 트랜잭션에서 불러야 반쯤 구성된 활동이 남지 않는다. */
    @Transactional
    public List<AssignedQuizItemResponse> attach(UUID activityId, List<QuizItem> picks) {
        List<AssignedQuizItemResponse> attached = new ArrayList<>();
        for (int index = 0; index < picks.size(); index++) {
            QuizItem item = picks.get(index);
            ActivityQuiz assignment =
                    new ActivityQuiz(
                            UUID.randomUUID(),
                            activityId,
                            item.getItemId(),
                            item.getItemVersion(),
                            index + 1);
            assignments.save(assignment);
            attached.add(AssignedQuizItemResponse.from(assignment, item));
        }
        return attached;
    }

    private Optional<QuizItem> randomAssignable(QuizType type) {
        List<QuizItem> candidates =
                items.findByQuizTypeAndStatus(type, "APPROVED").stream()
                        .filter(QuizAssignmentService::isAssignable)
                        .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }

    private static boolean isAssignable(QuizItem item) {
        try {
            item.validateForAssignment();
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public List<AssignedQuizItemResponse> list(UUID activityId, Authentication authentication) {
        Activity activity =
                activities
                        .findById(activityId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        access.requireOwnerOrInstructor(authentication, activity.getChildId());
        return assignments.findByActivityIdOrderByQuestionOrder(activityId).stream()
                .map(assigned -> AssignedQuizItemResponse.from(assigned, requireVersion(assigned)))
                .toList();
    }

    public QuizItem requireVersion(ActivityQuiz assignment) {
        QuizItem item =
                items.findById(assignment.getItemId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "QUIZ_ITEM_NOT_FOUND", "퀴즈 문항을 찾을 수 없습니다."));
        if (item.getItemVersion() != assignment.getItemVersion()) {
            throw conflict("ITEM_VERSION_CONFLICT", "배정된 문항 버전이 변경되었습니다.");
        }
        return item;
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
