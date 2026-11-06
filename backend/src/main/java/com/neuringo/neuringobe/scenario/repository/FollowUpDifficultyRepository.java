package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.domain.FollowUpDifficulty;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowUpDifficultyRepository extends JpaRepository<FollowUpDifficulty, UUID> {
    Optional<FollowUpDifficulty> findByActivityId(UUID activityId);

    /** 다음 결정의 연속 후보 수와 다음 역할극 배정의 입력. 같은 시각이면 ID 로 순서를 고정한다. */
    Optional<FollowUpDifficulty> findFirstByChildIdOrderByDecidedAtDescDecisionIdDesc(UUID childId);

    /** 아동 삭제. 고정 난이도를 지운 뒤, 활동보다 먼저 지운다. */
    @Modifying
    @Query(
            value = "delete from follow_up_difficulty_decision where child_id in (:childIds)",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
