package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizResultRepository extends JpaRepository<QuizResult, UUID> {
    @Query(
            value =
                    "select exists (select 1 from quiz_result r join activity a on a.activity_id = r.activity_id where a.child_id = :childId and r.initial_difficulty_used)",
            nativeQuery = true)
    boolean existsInitialDifficultyUsedByChild(@Param("childId") UUID childId);
}
