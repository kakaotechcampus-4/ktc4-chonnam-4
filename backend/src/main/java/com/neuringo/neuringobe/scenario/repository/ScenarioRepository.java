package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.domain.Scenario;
import com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion;
import java.util.List;
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
                and s.goal_content_hash = g.content_hash
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
                and s.goalContentHash = :goalHash
                and s.scenarioLevel = :level and s.scenarioVersion > 0
            """)
    Optional<ApprovedScenarioVersion> findMatchingApprovedVersion(
            @Param("scenarioId") UUID scenarioId,
            @Param("goalHash") String goalHash,
            @Param("level") String level);

    /**
     * 이 아동의 활동에 붙일 수 있는 재사용 후보를 보여 줄 순서대로 돌려준다.
     *
     * <ul>
     *   <li>조건: 승인본, 같은 목표 내용·난이도, 이 아동이 그 시나리오로 배정받은 적 없음(완료·진행 중 모두 제외).
     *   <li>순서: 그 시나리오가 붙은 활동의 가장 늦은 배정 시각이 오래된 것부터. 한 번도 붙지 않은 것이 맨 앞이다. 같으면 최근 승인본, 그래도 같으면 ID
     *       순이다.
     * </ul>
     *
     * 사용 시각은 순서를 정하는 데만 쓰고 돌려주지 않는다. 다른 반 아동의 활동 시점이 드러나지 않게 하기 위해서다.
     */
    @Query(
            value =
                    """
                    select s.scenario_id from scenario s
                    where s.status = 'APPROVED'
                      and s.goal_content_hash = :goalHash
                      and s.scenario_level = :level
                      and s.scenario_version > 0
                      and not exists (
                          select 1 from activity mine
                          where mine.child_id = :childId and mine.scenario_id = s.scenario_id)
                    order by (select max(used.assigned_at) from activity used
                              where used.scenario_id = s.scenario_id) asc nulls first,
                             s.approved_at desc,
                             s.scenario_id
                    """,
            nativeQuery = true)
    List<UUID> findReuseCandidates(
            @Param("goalHash") String goalHash,
            @Param("level") String level,
            @Param("childId") UUID childId);
}
