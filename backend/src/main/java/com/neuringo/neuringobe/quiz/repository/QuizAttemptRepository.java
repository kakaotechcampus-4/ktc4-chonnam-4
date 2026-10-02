package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {
    Optional<QuizAttempt> findByActivityQuizId(UUID activityQuizId);
}
