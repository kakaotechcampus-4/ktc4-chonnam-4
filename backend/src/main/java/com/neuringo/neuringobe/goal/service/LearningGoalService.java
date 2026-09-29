package com.neuringo.neuringobe.goal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiDomainException;
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
    private final ChildAccessScope access;
    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public LearningGoalService(LearningGoalRepository goals, ChildAccessScope access) {
        this.goals = goals;
        this.access = access;
    }

    @Transactional
    public LearningGoalResponse create(
            UUID childId, CreateLearningGoalRequest request, Authentication authentication) {
        access.requireInstructor(authentication, childId);
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
        String situation = request.situationType() == null ? null : request.situationType().trim();
        if (required.stream().anyMatch(value -> value == null || value.isBlank())
                || forbidden.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new ApiDomainException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_GOAL_CONDITIONS",
                    "목표 조건이 올바르지 않습니다.");
        }
        LearningGoal goal =
                new LearningGoal(
                        UUID.randomUUID(),
                        childId,
                        authentication.getName(),
                        request.parentGoalId(),
                        title,
                        situation,
                        characters,
                        required,
                        forbidden,
                        contentHash(title, situation, characters, required, forbidden),
                        Instant.now());
        return LearningGoalResponse.from(goals.save(goal));
    }

    public List<LearningGoalResponse> list(UUID childId, Authentication authentication) {
        access.requireInstructor(authentication, childId);
        return goals.findByChildIdOrderByCreatedAtDesc(childId).stream()
                .map(LearningGoalResponse::from)
                .toList();
    }

    public LearningGoalResponse get(UUID goalId, Authentication authentication) {
        LearningGoal goal = goals.findById(goalId).orElseThrow(this::notFound);
        access.requireInstructor(authentication, goal.getChildId());
        return LearningGoalResponse.from(goal);
    }

    private <T> List<T> emptyIfNull(List<T> values) {
        if (values == null) {
            return List.of();
        }
        if (values.contains(null)) {
            throw new ApiDomainException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_GOAL_CONDITIONS",
                    "목표 조건이 올바르지 않습니다.");
        }
        return List.copyOf(values);
    }

    private String contentHash(
            String title,
            String situation,
            List<Map<String, Object>> characters,
            List<String> required,
            List<String> forbidden) {
        try {
            byte[] canonical =
                    mapper.writeValueAsBytes(
                            new HashInput(title, situation, characters, required, forbidden));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("목표 해시 계산에 실패했습니다.", ex);
        }
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("GOAL_NOT_FOUND", "학습 목표를 찾을 수 없습니다.");
    }

    private record HashInput(
            String title,
            String situationType,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {}
}
