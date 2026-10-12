package com.neuringo.neuringobe.quiz.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.dto.AssignedQuizItemResponse;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

    /**
     * 활동 유형별 문항 구성. 위에서부터 그 순서대로 문항이 붙는다. 최초 결합은 첫 난이도를 재는 측정용이라 유형마다 1개씩 3문항이다. 독립 퀴즈는 10문항이고, 상대
     * 감정 퀴즈의 상황 7 + 이미지 3 은 그림 제작이 필요한 이미지 문항을 가장 적게 쓰는 비율이다. 비율을 바꾸면 여기만 고친다.
     */
    private static final Map<ActivityType, List<QuizType>> COMPOSITION =
            Map.of(
                    ActivityType.FIRST_COMBINED,
                    List.of(
                            QuizType.SELF_EMOTION_SITUATION,
                            QuizType.OTHER_EMOTION_SITUATION,
                            QuizType.OTHER_EMOTION_IMAGE),
                    ActivityType.SELF_EMOTION_QUIZ,
                    Collections.nCopies(10, QuizType.SELF_EMOTION_SITUATION),
                    ActivityType.OTHER_EMOTION_QUIZ,
                    concat(
                            Collections.nCopies(7, QuizType.OTHER_EMOTION_SITUATION),
                            Collections.nCopies(3, QuizType.OTHER_EMOTION_IMAGE)));

    /**
     * 활동에 붙일 퀴즈 문항을 고른다. 시스템이 검증된 문항 풀에서 구성한다. 같은 유형의 승인 문항은 서로 바꿔 쓸 수 있다고 보고 무작위로 고르며, 한 활동에 같은
     * 문항은 두 번 붙이지 않는다. 아동마다 노출 문항이 퍼진다. 구성의 칸을 하나라도 못 채우면 빈 목록이다. 문항 수가 화면 안내와 같고 활동끼리 정답률을 비교할 수
     * 있어야 해서 모자란 채로 배정하지 않는다(팀 결정). 문항이 없는 유형(역할극)도 빈 목록이다.
     */
    public List<QuizItem> pickItems(ActivityType activityType) {
        List<QuizType> slots = COMPOSITION.getOrDefault(activityType, List.of());
        Map<QuizType, Deque<QuizItem>> shuffled = new EnumMap<>(QuizType.class);
        List<QuizItem> picks = new ArrayList<>();
        for (QuizType type : slots) {
            QuizItem next = shuffled.computeIfAbsent(type, this::shuffledAssignable).poll();
            if (next == null) {
                return List.of();
            }
            picks.add(next);
        }
        return picks;
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

    private Deque<QuizItem> shuffledAssignable(QuizType type) {
        List<QuizItem> candidates =
                new ArrayList<>(
                        items.findByQuizTypeAndStatus(type, "APPROVED").stream()
                                .filter(QuizAssignmentService::isAssignable)
                                .toList());
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        return new ArrayDeque<>(candidates);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> joined = new ArrayList<>(first);
        joined.addAll(second);
        return List.copyOf(joined);
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
                        .orElseThrow(QuizAssignmentService::activityNotFound);
        access.requireOwnerOrInstructor(
                authentication, activity.getChildId(), QuizAssignmentService::activityNotFound);
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

    private static ResourceNotFoundException activityNotFound() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }
}
