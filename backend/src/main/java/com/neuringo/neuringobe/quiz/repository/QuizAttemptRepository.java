package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizAttempt;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {
    Optional<QuizAttempt> findByActivityQuizId(UUID activityQuizId);

    /** 아동 삭제(ADR 2026-10-04 D1). 힌트를 먼저 지운 뒤 부른다. */
    @Modifying
    @Query(
            value =
                    "delete from quiz_attempt where activity_quiz_id in (select q.activity_quiz_id from activity_quiz q"
                            + " join activity a on a.activity_id = q.activity_id where a.child_id in (:childIds))",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
