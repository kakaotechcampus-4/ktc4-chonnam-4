package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityQuizRepository extends JpaRepository<ActivityQuiz, UUID> {
    List<ActivityQuiz> findByActivityIdOrderByQuestionOrder(UUID activityId);

    long countByActivityId(UUID activityId);
}
