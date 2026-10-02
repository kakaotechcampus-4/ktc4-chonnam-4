package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizHintRepository extends JpaRepository<QuizHint, UUID> {
    List<QuizHint> findByAttemptIdOrderByHintOrder(UUID attemptId);

    long countByAttemptId(UUID attemptId);

    Optional<QuizHint> findByIdempotencyKey(UUID idempotencyKey);
}
