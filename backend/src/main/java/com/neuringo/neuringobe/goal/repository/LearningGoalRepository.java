package com.neuringo.neuringobe.goal.repository;

import com.neuringo.neuringobe.goal.domain.LearningGoal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningGoalRepository extends JpaRepository<LearningGoal, UUID> {
    List<LearningGoal> findByChildIdOrderByCreatedAtDesc(UUID childId);

    /** 같은 아동의 같은 내용(제목·분류·조건) 목표들. 강사가 같은 목표를 다시 입력하면 행은 새로 생기고 해시는 같다. */
    List<LearningGoal> findByChildIdAndContentHash(UUID childId, String contentHash);

    /** 아동 삭제(ADR 2026-10-04 D1). 활동을 먼저 지운 뒤 부른다. 상위 목표도 같은 아동의 목표라 한 문장으로 지운다. */
    @Modifying
    @Query(value = "delete from learning_goal where child_id in (:childIds)", nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
