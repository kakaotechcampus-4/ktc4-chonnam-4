package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.domain.Scenario;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {
    /**
     * 첫 활동에 붙일 시나리오. 난이도가 같은 승인본 중 이 아동이 배정받은 적 없는 것을 먼저 넣은 순서로 하나 고른다. 목표는 보지 않는다.
     *
     * <p>"이미 한 것"은 완료했거나 아직 끝나지 않은 배정이다. 지금 활동 상태에는 "그만둠"이 없어 이 아동의 모든 배정이 빠진다.
     */
    @Query(
            value =
                    """
                    select s.scenario_id from scenario s
                    where s.status = 'APPROVED'
                      and s.scenario_level = :level
                      and not exists (
                          select 1 from activity a
                          where a.child_id = :childId and a.scenario_id = s.scenario_id)
                    order by s.created_at, s.scenario_id
                    limit 1
                    """,
            nativeQuery = true)
    Optional<UUID> findFirstUnusedApproved(
            @Param("level") String level, @Param("childId") UUID childId);
}
