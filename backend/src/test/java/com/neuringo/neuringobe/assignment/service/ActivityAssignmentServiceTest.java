package com.neuringo.neuringobe.assignment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.assignment.dto.CreateActivityRequest;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.dto.CreateLearningGoalRequest;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;

/** 통합 테스트는 시드 문항이 늘 있어 "최초 결합 문항 없음"을 만들 수 없다. 그 경로만 여기서 본다. */
@ExtendWith(MockitoExtension.class)
class ActivityAssignmentServiceTest {

    @Mock private ActivityRepository activities;
    @Mock private ChildRepository children;
    @Mock private LearningGoalRepository goals;
    @Mock private LearningGoalService goalService;
    @Mock private ChildAccessScope access;
    @Mock private QuizAssignmentService quizAssignment;
    @Mock private Authentication authentication;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks private ActivityAssignmentService service;

    private static final CreateLearningGoalRequest GOAL_REQUEST =
            new CreateLearningGoalRequest("친구 감정 알아보기", null, null, null, null, null, null);

    @Test
    void doesNotCreateActivityWhenNoApprovedQuizItemExists() {
        UUID childId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        given(children.findByIdForUpdate(childId))
                .willReturn(
                        Optional.of(
                                new Child(childId, UUID.randomUUID(), "김하늘", ChildStatus.ACTIVE)));
        given(goalService.newGoal(childId, GOAL_REQUEST, "goal.", authentication))
                .willReturn(goal(goalId, childId));
        given(activities.findByIdempotencyKey(key)).willReturn(Optional.empty());
        given(activities.existsByChildIdAndActivityType(childId, ActivityType.FIRST_COMBINED))
                .willReturn(false);
        given(quizAssignment.pickItems(ActivityType.FIRST_COMBINED)).willReturn(List.of());

        assertThatThrownBy(
                        () ->
                                service.assign(
                                        new CreateActivityRequest(childId, null, GOAL_REQUEST),
                                        key,
                                        authentication))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "QUIZ_ITEMS_UNAVAILABLE");
        verify(goals, never()).save(any());
        verify(activities, never()).saveAndFlush(any());
    }

    @Test
    void doesNotAssignToChildWhoIsNotActive() {
        UUID childId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        given(children.findByIdForUpdate(childId))
                .willReturn(
                        Optional.of(
                                new Child(childId, UUID.randomUUID(), "김하늘", ChildStatus.PAUSED)));
        given(goalService.newGoal(childId, GOAL_REQUEST, "goal.", authentication))
                .willReturn(goal(UUID.randomUUID(), childId));
        given(activities.findByIdempotencyKey(key)).willReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.assign(
                                        new CreateActivityRequest(childId, null, GOAL_REQUEST),
                                        key,
                                        authentication))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CHILD_NOT_ACTIVE");
        verify(goals, never()).save(any());
        verify(activities, never()).saveAndFlush(any());
    }

    private static LearningGoal goal(UUID goalId, UUID childId) {
        return new LearningGoal(
                goalId,
                childId,
                UUID.randomUUID(),
                null,
                "친구 감정 알아보기",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                "hash",
                Instant.now());
    }
}
