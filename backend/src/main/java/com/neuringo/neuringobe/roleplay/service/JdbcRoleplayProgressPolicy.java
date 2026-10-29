package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.prompt.RoleplayCodes;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.roleplay.application.RoleplayProgressPolicy;
import com.neuringo.neuringobe.roleplay.config.RoleplayProgressProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcRoleplayProgressPolicy implements RoleplayProgressPolicy {
    private final JdbcTemplate jdbc;
    private final RoleplayProgressProperties limits;

    public JdbcRoleplayProgressPolicy(JdbcTemplate jdbc, RoleplayProgressProperties limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Optional<Selection> select(Stored stored, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(stored);
        Objects.requireNonNull(deadline).requireActive();
        if (!limits.configured() || stored.currentMicroGoalId() == null) return Optional.empty();
        var key = stored.key();
        readBudget(deadline);
        var rows =
                jdbc.query(
                        """
                select t.turn_id,t.turn_number,t.checkpoint_version,t.micro_goal_id,t.response_type,
                       t.input_micro_goal_id,t.input_answer_revealed,
                       a.input_micro_goal_id as judged_goal,a.input_learning_state,a.scenario_id,
                       a.scenario_version,a.checkpoint_version as assessment_version,
                       a.analysis_contract_version,a.evaluation_contract_version
                from roleplay_turn t
                left join roleplay_goal_assessment a on a.session_id=t.session_id and a.turn_id=t.turn_id
                where t.session_id=? order by t.turn_number
                """,
                        (rs, n) ->
                                new Judgment(
                                        rs.getObject("turn_id", UUID.class),
                                        rs.getInt("turn_number"),
                                        rs.getLong("checkpoint_version"),
                                        rs.getObject("micro_goal_id", UUID.class),
                                        rs.getString("response_type"),
                                        rs.getObject("input_micro_goal_id", UUID.class),
                                        rs.getObject("input_answer_revealed", Boolean.class),
                                        rs.getObject("judged_goal", UUID.class),
                                        rs.getString("input_learning_state"),
                                        rs.getObject("scenario_id", UUID.class),
                                        rs.getObject("scenario_version", Integer.class),
                                        rs.getObject("assessment_version", Long.class),
                                        rs.getString("analysis_contract_version"),
                                        rs.getString("evaluation_contract_version")),
                        key.sessionId());
        deadline.requireActive();
        if (rows.size() != stored.turnCount() - 1) return Optional.empty();
        var approved = stored.approvedMicroGoalIds();
        var turns = new HashSet<UUID>();
        long previousVersion = 0;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.number() != i + 1
                    || row.version() <= previousVersion
                    || row.version() > key.checkpointVersion()
                    || !turns.add(row.turn())
                    || row.input() == null
                    || row.inputReveal() == null
                    || row.state() == null
                    || !approved.contains(row.input())
                    || !approved.contains(row.responseGoal())
                    || !row.input().equals(row.judgedGoal())
                    || !RoleplayCodes.LEARNING_STATES.contains(row.state())
                    || row.responseType() == null
                    || !RoleplayCodes.RESPONSE_TYPES.contains(row.responseType())
                    || !key.scenarioId().equals(row.scenario())
                    || !Objects.equals(key.scenarioVersion(), row.scenarioVersion())
                    || !Objects.equals(row.version(), row.assessmentVersion())
                    || !GoalObservationOutput.ANALYSIS_VERSION.equals(row.analysisVersion())
                    || !GoalObservationOutput.EVALUATION_VERSION.equals(row.evaluationVersion()))
                return Optional.empty();
            previousVersion = row.version();
        }
        var observedByTurn = new HashMap<UUID, Set<UUID>>();
        var positiveByTurn = new HashMap<UUID, Set<UUID>>();
        readBudget(deadline);
        var observations =
                jdbc.queryForList(
                        "select turn_id,micro_goal_id,observation_status from roleplay_goal_observation where session_id=?",
                        key.sessionId());
        deadline.requireActive();
        for (var observation : observations) {
            var turn = (UUID) observation.get("turn_id");
            var goal = (UUID) observation.get("micro_goal_id");
            if (!turns.contains(turn)
                    || !approved.contains(goal)
                    || !Set.of(
                                    "EXPLICIT",
                                    "IMPLICIT",
                                    "NOT_EXPRESSED",
                                    "MISUNDERSTOOD",
                                    "CONTRADICTORY")
                            .contains(observation.get("observation_status")))
                return Optional.empty();
            observedByTurn.computeIfAbsent(turn, ignored -> new HashSet<>()).add(goal);
            if (Set.of("EXPLICIT", "IMPLICIT").contains(observation.get("observation_status"))) {
                positiveByTurn.computeIfAbsent(turn, ignored -> new HashSet<>()).add(goal);
            }
        }
        var statuses = new HashMap<UUID, String>();
        approved.forEach(goal -> statuses.put(goal, "NOT_OBSERVED"));
        var revealed = new HashSet<UUID>();
        for (var row : rows) {
            if (row.inputReveal() != revealed.contains(row.input())) return Optional.empty();
            for (var goal : observedByTurn.getOrDefault(row.turn(), Set.of())) {
                if (goal.equals(row.input()) && row.state().equals("NOT_ASSESSABLE")) continue;
                if ("NOT_OBSERVED".equals(statuses.get(goal))) statuses.put(goal, "IN_PROGRESS");
            }
            // 판정 불가로 이전 상태를 지우지 않고 정답 제공은 별도 사실로 유지한다.
            if (revealed.contains(row.input())) {
                statuses.put(row.input(), "ANSWER_REVEALED");
            } else if (!row.state().equals("NOT_ASSESSABLE")) {
                if (row.state().equals("GOAL_ACHIEVED")
                        && !positiveByTurn
                                .getOrDefault(row.turn(), Set.of())
                                .contains(row.input())) {
                    return Optional.empty();
                }
                statuses.put(
                        row.input(),
                        row.state().equals("GOAL_ACHIEVED") ? "ACHIEVED" : "IN_PROGRESS");
            }
            if (row.responseType().equals("ANSWER_REVEAL")) {
                revealed.add(row.responseGoal());
                statuses.put(row.responseGoal(), "ANSWER_REVEALED");
            }
            deadline.requireActive();
        }
        return Optional.of(
                new Selection(
                        stored.currentMicroGoalId(),
                        limits.maximumTurnCount(),
                        limits.maximumSupportTurnsPerMicroGoal(),
                        statuses));
    }

    private void readBudget(RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        long millis = Math.max(1, (deadline.remainingNanos() + 999999) / 1000000);
        jdbc.queryForObject(
                "select set_config('statement_timeout', ?, true)", String.class, millis + "ms");
    }

    private record Judgment(
            UUID turn,
            int number,
            long version,
            UUID responseGoal,
            String responseType,
            UUID input,
            Boolean inputReveal,
            UUID judgedGoal,
            String state,
            UUID scenario,
            Integer scenarioVersion,
            Long assessmentVersion,
            String analysisVersion,
            String evaluationVersion) {}
}
