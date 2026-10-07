package com.neuringo.neuringobe.goal.repository;

import com.neuringo.neuringobe.goal.domain.LearningGoal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningGoalRepository extends JpaRepository<LearningGoal, UUID> {
    List<LearningGoal> findByChildIdOrderByCreatedAtDesc(UUID childId);

    /** 같은 아동의 같은 내용(제목·분류·조건) 목표들. 강사가 같은 목표를 다시 입력하면 행은 새로 생기고 해시는 같다. */
    List<LearningGoal> findByChildIdAndContentHash(UUID childId, String contentHash);
}
