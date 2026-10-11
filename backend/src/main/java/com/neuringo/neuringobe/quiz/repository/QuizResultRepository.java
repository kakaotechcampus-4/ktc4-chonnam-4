package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizResult;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizResultRepository extends JpaRepository<QuizResult, UUID> {
    @Query(
            value =
                    "select exists (select 1 from quiz_result r join activity a on a.activity_id = r.activity_id where a.child_id = :childId and r.initial_difficulty_used)",
            nativeQuery = true)
    boolean existsInitialDifficultyUsedByChild(@Param("childId") UUID childId);

    /** 아동 삭제(ADR 2026-10-04 D1). */
    @Modifying
    @Query(
            value =
                    "delete from quiz_result where activity_id in (select activity_id from activity where child_id in (:childIds))",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
