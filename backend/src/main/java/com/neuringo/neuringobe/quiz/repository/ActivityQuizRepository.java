package com.neuringo.neuringobe.quiz.repository;

import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityQuizRepository extends JpaRepository<ActivityQuiz, UUID> {
    List<ActivityQuiz> findByActivityIdOrderByQuestionOrder(UUID activityId);

    long countByActivityId(UUID activityId);

    /** 아동 삭제(ADR 2026-10-04 D1). 응답을 먼저 지운 뒤 부른다. */
    @Modifying
    @Query(
            value =
                    "delete from activity_quiz where activity_id in (select activity_id from activity where child_id in (:childIds))",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
