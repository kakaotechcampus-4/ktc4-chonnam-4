package com.neuringo.neuringobe.goal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.dto.CreateLearningGoalRequest;
import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

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
        return LearningGoalResponse.from(goals.save(newGoal(childId, request, authentication)));
    }

    /**
     * 저장하지 않은 새 목표. 조건을 확인하고 정규화해 content_hash 까지 채운다. 활동 배정은 이 해시로 중복을 먼저 확인한 뒤 활동과 같은 트랜잭션에서
     * 저장한다. 아동 권한 확인과 아동 행 잠금은 부르는 쪽이 먼저 한다.
     */
    public LearningGoal newGoal(
            UUID childId, CreateLearningGoalRequest request, Authentication authentication) {
        if (request.parentGoalId() != null) {
            LearningGoal parent =
                    goals.findById(request.parentGoalId()).orElseThrow(() -> notFound());
            if (!parent.getChildId().equals(childId)) {
                throw notFound();
            }
        }
        String title = request.title().trim();
        List<Map<String, Object>> characters = emptyIfNull(request.characters());
        List<String> required = emptyIfNull(request.requiredElements());
        List<String> forbidden = emptyIfNull(request.forbiddenExpressions());
        String situation = trimOrNull(request.situationType());
        String category = trimOrNull(request.category());
        if (required.stream().anyMatch(value -> value == null || value.isBlank())
                || forbidden.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_GOAL_CONDITIONS",
                    "목표 조건이 올바르지 않습니다.");
        }
        return new LearningGoal(
                UUID.randomUUID(),
                childId,
                ((AuthenticatedUser) authentication.getPrincipal()).userId(),
                request.parentGoalId(),
                title,
                situation,
                category,
                characters,
                required,
                forbidden,
                contentHash(title, situation, category, characters, required, forbidden),
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

    private <T> List<T> emptyIfNull(List<T> values) {
        if (values == null) {
            return List.of();
        }
        if (values.contains(null)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_GOAL_CONDITIONS",
                    "목표 조건이 올바르지 않습니다.");
        }
        return List.copyOf(values);
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }

    private String contentHash(
            String title,
            String situation,
            String category,
            List<Map<String, Object>> characters,
            List<String> required,
            List<String> forbidden) {
        try {
            byte[] canonical =
                    mapper.writeValueAsBytes(
                            new HashInput(
                                    title, situation, category, characters, required, forbidden));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("목표 해시 계산에 실패했습니다.", ex);
        }
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다.");
    }

    private ResourceNotFoundException childNotFound() {
        return new ResourceNotFoundException("CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.");
    }

    private record HashInput(
            String title,
            String situationType,
            String category,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {}
}
