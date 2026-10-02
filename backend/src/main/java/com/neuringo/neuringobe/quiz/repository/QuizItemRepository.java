package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizItemRepository extends JpaRepository<QuizItem, UUID> {}
