package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.domain.Scenario;
import com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {
    @Query(
            value =
                    """
            select f.micro_goal_id from scenario_opening_focus f
            join scenario s on s.scenario_id=f.scenario_id
            where s.scenario_id=:id and s.scenario_version=:version and s.status='APPROVED'
                and f.support_level=:support
            """,
            nativeQuery = true)
    Optional<UUID> findApprovedOpeningFocus(
            @Param("id") UUID scenarioId,
            @Param("version") int version,
            @Param("support") String support);

    @Query(
            value =
                    """
            select s.opening_prompts ->> cast(:support as text)
            from scenario s
                join activity a on a.scenario_id = s.scenario_id and a.activity_id = :activity
                join child c on c.child_id = a.child_id
                join learning_goal g on g.goal_id = a.goal_id and g.child_id = c.child_id
            where s.scenario_id = :id and s.scenario_version = :version and s.status = 'APPROVED'
                and s.class_id = c.class_id and s.goal_content_hash = g.content_hash
                and jsonb_typeof(s.opening_prompts) = 'object'
                and jsonb_typeof(s.opening_prompts -> cast(:support as text)) = 'string'
            for share of s
            """,
            nativeQuery = true)
    Optional<String> findApprovedOpening(
            @Param("activity") UUID activityId,
            @Param("id") UUID scenarioId,
            @Param("version") int version,
            @Param("support") String support);

    /** 새 세션 저장이 끝날 때까지 승인 폐기와의 경합을 막는다. */
    @Query(
            value =
                    "select scenario_id from scenario where scenario_id = :id and scenario_version = :version and status = 'APPROVED' for share",
            nativeQuery = true)
    Optional<UUID> lockApprovedVersion(@Param("id") UUID scenarioId, @Param("version") int version);

    @Query(
            """
            select new com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion(s.scenarioId, s.scenarioVersion)
            from Scenario s
            where s.scenarioId = :scenarioId
                and s.status = com.neuringo.neuringobe.scenario.domain.ScenarioStatus.APPROVED
                and s.classId = :classId and s.goalContentHash = :goalHash
                and s.scenarioLevel = :level and s.scenarioVersion > 0
            """)
    Optional<ApprovedScenarioVersion> findMatchingApprovedVersion(
            @Param("scenarioId") UUID scenarioId,
            @Param("classId") UUID classId,
            @Param("goalHash") String goalHash,
            @Param("level") String level);

    /** 배정 이력이 없는 동일 조건 승인본을 고르고, 연결이 끝날 때까지 승인 상태를 잠근다. */
    @Query(
            value =
                    """
                    select s.scenario_id from scenario s
                    where s.status = 'APPROVED'
                      and s.class_id = :classId
                      and s.goal_content_hash = :goalHash
                      and s.scenario_level = :level
                      and s.scenario_version > 0
                      and not exists (
                          select 1 from activity a
                          where a.child_id = :childId and a.scenario_id = s.scenario_id)
                    order by s.created_at, s.scenario_id
                    limit 1
                    for share of s
                    """,
            nativeQuery = true)
    Optional<UUID> findFirstUnusedApproved(
            @Param("classId") UUID classId,
            @Param("goalHash") String goalHash,
            @Param("level") String level,
            @Param("childId") UUID childId);
}
