package com.neuringo.neuringobe.goal.service;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.dto.CreateLearningGoalRequest;
import com.neuringo.neuringobe.goal.dto.GoalCharacter;
import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LearningGoalService {
    private final LearningGoalRepository goals;
    private final ChildRepository children;
    private final ChildAccessScope access;

    public LearningGoalService(
            LearningGoalRepository goals, ChildRepository children, ChildAccessScope access) {
        this.goals = goals;
        this.children = children;
        this.access = access;
    }

    /**
     * 아동 행을 잠근 뒤 저장한다. 아동 삭제가 같은 잠금을 잡으므로, 삭제 도중에 새 목표가 들어와 아동 행 삭제가 FK 에 걸리는 일이 없다. 잠금을 기다리는 사이
     * 아동이 지워졌으면 404 다.
     */
    @Transactional
    public LearningGoalResponse create(
            UUID childId, CreateLearningGoalRequest request, Authentication authentication) {
        access.requireInstructor(authentication, childId);
        children.findByIdForUpdate(childId).orElseThrow(() -> childNotFound());
        return LearningGoalResponse.from(goals.save(newGoal(childId, request, "", authentication)));
    }

    /**
     * 저장하지 않은 새 목표. 조건을 정리·검사해 content_hash 까지 채운다(규칙은 {@link GoalConditions}). 활동 배정은 이 해시로 중복을 먼저
     * 확인한 뒤 활동과 같은 트랜잭션에서 저장한다. 아동 권한 확인과 아동 행 잠금은 부르는 쪽이 먼저 한다.
     *
     * <p>조건이 잘못되면 422 INVALID_GOAL_CONDITIONS 이고, fieldErrors 의 필드 경로에 {@code fieldPrefix} 를 붙인다.
     * 활동 배정처럼 목표가 요청 안의 {@code goal} 에 있으면 요청 검증 오류(goal.title)와 같게 {@code "goal."} 을 넘긴다.
     */
    public LearningGoal newGoal(
            UUID childId,
            CreateLearningGoalRequest request,
            String fieldPrefix,
            Authentication authentication) {
        if (request.parentGoalId() != null) {
            LearningGoal parent =
                    goals.findById(request.parentGoalId()).orElseThrow(() -> notFound());
            if (!parent.getChildId().equals(childId)) {
                throw notFound();
            }
        }
        List<GoalCharacter> characters = emptyIfNull(request.characters());
        List<String> required = emptyIfNull(request.requiredElements());
        List<String> forbidden = emptyIfNull(request.forbiddenExpressions());
        GoalConditions.Prepared prepared =
                GoalConditions.prepare(
                        request.title(),
                        request.situationType(),
                        request.category(),
                        characters,
                        required,
                        forbidden,
                        fieldPrefix);
        if (!prepared.errors().isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_GOAL_CONDITIONS",
                    "목표 조건이 올바르지 않습니다.",
                    prepared.errors());
        }
        GoalConditions.Normalized goal = prepared.goal();
        return new LearningGoal(
                UUID.randomUUID(),
                childId,
                ((AuthenticatedUser) authentication.getPrincipal()).userId(),
                request.parentGoalId(),
                goal.title(),
                goal.situationType(),
                goal.category(),
                goal.characters(),
                goal.requiredElements(),
                goal.forbiddenExpressions(),
                GoalConditions.contentHash(goal),
                Instant.now());
    }

    public List<LearningGoalResponse> list(UUID childId, Authentication authentication) {
        access.requireInstructor(authentication, childId);
        return goals.findByChildIdOrderByCreatedAtDesc(childId).stream()
                .map(LearningGoalResponse::from)
                .toList();
    }

    public LearningGoalResponse get(UUID goalId, Authentication authentication) {
        LearningGoal goal = goals.findById(goalId).orElseThrow(this::notFound);
        access.requireInstructor(authentication, goal.getChildId(), this::notFound);
        return LearningGoalResponse.from(goal);
    }

    /** 목록 안의 null 은 {@link GoalConditions#violations} 가 위치와 함께 알린다. */
    private static <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다.");
    }

    private ResourceNotFoundException childNotFound() {
        return new ResourceNotFoundException("CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.");
    }
}
