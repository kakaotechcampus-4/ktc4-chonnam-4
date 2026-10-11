package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizItemRepository extends JpaRepository<QuizItem, UUID> {
    List<QuizItem> findByQuizTypeAndStatus(QuizType quizType, String status);
}
