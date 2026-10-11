package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.QuizHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizHintRepository extends JpaRepository<QuizHint, UUID> {
    List<QuizHint> findByAttemptIdOrderByHintOrder(UUID attemptId);

    long countByAttemptId(UUID attemptId);

    Optional<QuizHint> findByIdempotencyKey(UUID idempotencyKey);

    /** 아동 삭제(ADR 2026-10-04 D1). 그 아동들의 활동에 달린 힌트를 모두 지운다. */
    @Modifying
    @Query(
            value =
                    "delete from quiz_hint where attempt_id in (select t.attempt_id from quiz_attempt t"
                            + " join activity_quiz q on q.activity_quiz_id = t.activity_quiz_id"
                            + " join activity a on a.activity_id = q.activity_id where a.child_id in (:childIds))",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
