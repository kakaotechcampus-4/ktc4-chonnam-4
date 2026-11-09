package com.neuringo.neuringobe.assignment.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.dto.ActivityResponse;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.assignment.dto.ClassroomActivityResponse;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.goal.domain.LearningGoal;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학급 아동들의 활동을 한 목록으로 읽는다. 학급 개요·배정 활동·대시보드가 아동마다 따로 부르지 않게 한다.
 *
 * <p>준비 상태(nextAction)는 아동별 목록에만 있다. 학급 화면은 상태·유형·시각으로 세고 늘어놓으므로 여기서는 계산하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class ClassroomActivityQueryService {
    private final ClassroomService classrooms;
    private final ChildRepository children;
    private final ActivityRepository activities;
    private final LearningGoalRepository goals;

    public ClassroomActivityQueryService(
            ClassroomService classrooms,
            ChildRepository children,
            ActivityRepository activities,
            LearningGoalRepository goals) {
        this.classrooms = classrooms;
        this.children = children;
        this.activities = activities;
        this.goals = goals;
    }

    public List<ClassroomActivityResponse> list(
            UUID instructorId, UUID classId, int page, int size) {
        classrooms.requireOwnedClassroom(instructorId, classId);
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "페이지 범위가 올바르지 않습니다.");
        }
        Map<UUID, String> names =
                children.findByClassId(classId).stream()
                        .collect(Collectors.toMap(Child::getChildId, Child::getDisplayName));
        if (names.isEmpty()) return List.of();

        List<Activity> selected =
                activities
                        .findByChildIdInOrderByAssignedAtDescActivityIdAsc(
                                names.keySet(), PageRequest.of(page, size))
                        .getContent();
        Map<UUID, String> titles =
                goals
                        .findAllById(
                                selected.stream()
                                        .map(Activity::getGoalId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        LearningGoal::getGoalId,
                                        LearningGoal::getTitle,
                                        (first, ignored) -> first));
        Function<Activity, String> titleOf =
                a -> a.getGoalId() == null ? null : titles.get(a.getGoalId());
        return selected.stream()
                .map(
                        a ->
                                ClassroomActivityResponse.of(
                                        ActivityResponse.from(a),
                                        names.get(a.getChildId()),
                                        titleOf.apply(a)))
                .toList();
    }
}
