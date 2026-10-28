package com.neuringo.neuringobe.learningrecord.repository;

import com.neuringo.neuringobe.learningrecord.domain.LearningRecord;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningRecordRepository extends JpaRepository<LearningRecord, UUID> {
    boolean existsByActivityId(UUID activityId);

    long countByActivityId(UUID activityId);

    /** 아동 삭제. 활동보다 먼저 지운다. */
    @Modifying
    @Query(
            value =
                    "delete from learning_record where activity_id in (select activity_id from activity where child_id in (:childIds))",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
