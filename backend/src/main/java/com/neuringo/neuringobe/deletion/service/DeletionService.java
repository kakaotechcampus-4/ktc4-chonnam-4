package com.neuringo.neuringobe.deletion.service;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.repository.ChildAccessCodeRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizAttemptRepository;
import com.neuringo.neuringobe.quiz.repository.QuizHintRepository;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아동·학급 영구 삭제(ADR 2026-10-04 D1~D3). 학습 기록이 있어도 함께 지운다. 아동 아래 데이터를 쓰는 도메인(goal·activity·quiz)이 모두
 * child 에 기대고 있어, 지우는 순서를 child 안에 두면 순환이 생긴다. 그래서 assignment 처럼 바깥 패키지에서 조립한다.
 *
 * <p>FK 에 ON DELETE CASCADE 가 없으므로 자식 테이블부터 지운다. 학급 삭제는 그 학급의 아동 삭제와 같은 경로를 탄 뒤 학급 행을 지운다. 지우는 순서는
 * {@link #deleteChildren} 한 곳에만 있다.
 */
@Service
public class DeletionService {

    private final ClassroomService classroomService;
    private final ClassroomRepository classrooms;
    private final ChildRepository children;
    private final ChildAccessCodeRepository accessCodes;
    private final LearningGoalRepository goals;
    private final ActivityRepository activities;
    private final ActivityQuizRepository activityQuizzes;
    private final QuizAttemptRepository attempts;
    private final QuizHintRepository hints;
    private final QuizResultRepository results;

    public DeletionService(
            ClassroomService classroomService,
            ClassroomRepository classrooms,
            ChildRepository children,
            ChildAccessCodeRepository accessCodes,
            LearningGoalRepository goals,
            ActivityRepository activities,
            ActivityQuizRepository activityQuizzes,
            QuizAttemptRepository attempts,
            QuizHintRepository hints,
            QuizResultRepository results) {
        this.classroomService = classroomService;
        this.classrooms = classrooms;
        this.children = children;
        this.accessCodes = accessCodes;
        this.goals = goals;
        this.activities = activities;
        this.activityQuizzes = activityQuizzes;
        this.attempts = attempts;
        this.hints = hints;
        this.results = results;
    }

    /** 담당 학급의 아동만 지운다. 다른 강사의 아동도 없는 아동과 똑같이 404 다. */
    @Transactional
    public void deleteChild(UUID instructorId, UUID childId) {
        Child child =
                children.findById(childId)
                        .filter(
                                found ->
                                        classroomService.isOwnedBy(
                                                instructorId, found.getClassId()))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."));
        deleteChildren(List.of(child.getChildId()));
    }

    /**
     * 학급과 그 학급의 아동·기록을 모두 지운다. 학급을 먼저 잠가, 목록을 읽은 뒤 등록된 아동이 남아 FK 에 걸리지 않게 한다. 아동 등록도 같은 잠금을 잡아야 이
     * 보장이 선다({@link ClassroomService#lockOwnedClassroom}).
     */
    @Transactional
    public void deleteClassroom(UUID instructorId, UUID classId) {
        classroomService.lockOwnedClassroom(instructorId, classId);
        List<UUID> childIds =
                children.findByClassId(classId).stream().map(Child::getChildId).toList();
        deleteChildren(childIds);
        classrooms.deleteByClassId(classId);
    }

    /**
     * 활동 → 아동 순서로 잠근 뒤 자식 테이블부터 지운다. 퀴즈 응답 저장이 활동 → 아동 순서로 잠그고, 배정은 아동을 잠그므로, 같은 순서를 지켜 교착 없이 진행 중인
     * 요청이 끝나기를 기다린다.
     */
    private void deleteChildren(List<UUID> childIds) {
        if (childIds.isEmpty()) {
            return;
        }
        activities.findByChildIdInForUpdate(childIds);
        children.findByChildIdInForUpdate(childIds);

        hints.deleteByChildIds(childIds);
        attempts.deleteByChildIds(childIds);
        results.deleteByChildIds(childIds);
        activityQuizzes.deleteByChildIds(childIds);
        activities.deleteByChildIds(childIds);
        goals.deleteByChildIds(childIds);
        accessCodes.deleteByChildIds(childIds);
        children.deleteByChildIds(childIds);
    }
}
