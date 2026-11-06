package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.domain.RoleplayActivityDifficulty;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleplayActivityDifficultyRepository
        extends JpaRepository<RoleplayActivityDifficulty, UUID> {

    /** 아동 삭제. 다음 난이도 결정과 활동보다 먼저 지운다. */
    @Modifying
    @Query(
            value =
                    "delete from roleplay_activity_difficulty where activity_id in (select activity_id from activity where child_id in (:childIds))",
            nativeQuery = true)
    int deleteByChildIds(@Param("childIds") Collection<UUID> childIds);
}
