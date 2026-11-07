package com.neuringo.neuringobe.assignment.service;

import com.neuringo.neuringobe.activity.domain.ActivityReadiness;
import com.neuringo.neuringobe.activity.domain.ActivityReadinessPolicy;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityReadinessRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.service.QuizAssignmentService;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ActivityReadinessQueryService {
    private final ChildAccessScope access;
    private final ActivityReadinessRepository repository;

    public ActivityReadinessQueryService(
            ChildAccessScope access, ActivityReadinessRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    public Map<UUID, ActivityReadiness> read(
            UUID childId, Collection<UUID> activityIds, Authentication authentication) {
        access.requireOwnerOrInstructor(authentication, childId);
        Objects.requireNonNull(activityIds);
        var ids = new LinkedHashSet<>(activityIds);
        if (ids.size() > 100 || ids.contains(null)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "준비 상태 조회 범위가 올바르지 않습니다.");
        }
        var composition =
                Arrays.stream(ActivityType.values())
                        .collect(
                                Collectors.toMap(
                                        Enum::name,
                                        type ->
                                                QuizAssignmentService.composition(type).stream()
                                                        .map(Enum::name)
                                                        .sorted()
                                                        .toList()));
        var evidence = repository.load(childId, ids, composition);
        if (!evidence.keySet().equals(ids)) {
            throw new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
        }
        var result = new LinkedHashMap<UUID, ActivityReadiness>();
        ids.forEach(id -> result.put(id, ActivityReadinessPolicy.evaluate(evidence.get(id))));
        return Collections.unmodifiableMap(result);
    }
}
