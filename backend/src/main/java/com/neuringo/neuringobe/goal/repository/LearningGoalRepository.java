package com.neuringo.neuringobe.goal.repository;

import com.neuringo.neuringobe.goal.domain.LearningGoal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningGoalRepository extends JpaRepository<LearningGoal, UUID> {
    List<LearningGoal> findByChildIdOrderByCreatedAtDesc(UUID childId);
}
