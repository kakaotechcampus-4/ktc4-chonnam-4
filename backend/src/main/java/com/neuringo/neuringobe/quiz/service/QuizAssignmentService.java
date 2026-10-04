package com.neuringo.neuringobe.quiz.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.dto.AssignQuizItemRequest;
import com.neuringo.neuringobe.quiz.dto.AssignedQuizItemResponse;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.util.List;
import java.util.UUID;
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
