package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.prompt.GoalObservationInput;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationHistory;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalResponseHistory;
import com.neuringo.neuringobe.roleplay.application.RoleplayProgressSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JdbcRoleplayContextSource implements RoleplayContextSource {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Optional<RoleplayProgressSource> progress;

    public JdbcRoleplayContextSource(
            JdbcTemplate jdbc, JsonMapper json, Optional<RoleplayProgressSource> progress) {
        this.jdbc = jdbc;
        this.json = json;
        this.progress = progress;
    }

    /** 한 읽기 시점의 자료를 모은 뒤 트랜잭션을 닫는다. AI 호출 동안 DB 잠금이나 연결을 잡지 않는다. */
    @Override
    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ)
    public Optional<Snapshot> load(Key key, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(deadline);
        try {
            var snapshot = readSnapshot(key, deadline);
            deadline.requireActive();
            return snapshot;
        } catch (RuntimeException failure) {
            deadline.requireActive();
            throw failure;
        }
    }

    private Optional<Snapshot> readSnapshot(Key key, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(deadline).requireActive();
        if (progress.isEmpty()) return Optional.empty();
        readBudget(deadline);
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select to_regclass('scenario') is not null and to_regclass('scenario_micro_goal') is not null",
                        Boolean.class))) {
            return Optional.empty();
        }
        readBudget(deadline);
        var owned =
                jdbc.query(
                        """
                select g.title, s.last_turn_number, s.last_turn_id, s.current_micro_goal_id, s.current_support_level
                from roleplay_session s
                join activity a on a.activity_id=s.activity_id and a.child_id=s.child_id
                join child c on c.child_id=s.child_id
                join learning_goal g on g.goal_id=a.goal_id and g.child_id=s.child_id
                where s.session_id=? and s.child_id=? and s.activity_id=? and a.goal_id=?
                  and s.scenario_id=? and a.scenario_id=s.scenario_id and s.scenario_version=?
                  and s.row_version=? and s.status='IN_PROGRESS' and a.status='IN_PROGRESS' and c.status='ACTIVE'
                """,
                        (rs, n) ->
                                new Owned(
                                        rs.getString("title"),
                                        rs.getInt("last_turn_number"),
                                        rs.getObject("last_turn_id", UUID.class),
                                        rs.getObject("current_micro_goal_id", UUID.class),
                                        rs.getString("current_support_level")),
                        key.sessionId(),
                        key.childId(),
                        key.activityId(),
                        key.goalId(),
                        key.scenarioId(),
                        key.scenarioVersion(),
                        key.checkpointVersion());
        if (owned.size() != 1) return Optional.empty();
        readBudget(deadline);
        var rows =
                jdbc.queryForList(
                        """
                        select s.scenario_id, s.scenario_version, s.class_id, s.goal_content_hash,
                               s.scenario_level, s.title, s.summary, s.ai_role, s.characters,
                               s.scenario_facts, s.prohibited_inferences, s.opening_prompts
                        from scenario s
                        join child c on c.child_id=?
                        join learning_goal g on g.goal_id=? and g.child_id=c.child_id
                        where s.scenario_id=? and s.scenario_version=? and s.status='APPROVED'
                          and s.class_id=c.class_id and s.goal_content_hash=g.content_hash
                        """,
                        key.childId(),
                        key.goalId(),
                        key.scenarioId(),
                        key.scenarioVersion());
        if (rows.size() != 1) return Optional.empty();
        var content = rows.getFirst();
        try {
            readBudget(deadline);
            var goals =
                    jdbc.query(
                            "select micro_goal_id, order_no, description, required_evidence::text, achievement_example from scenario_micro_goal where scenario_id=? order by order_no",
                            (rs, n) ->
                                    new Goal(
                                            rs.getObject("micro_goal_id", UUID.class),
                                            rs.getInt("order_no"),
                                            rs.getString("description"),
                                            strings(rs.getString("required_evidence")),
                                            rs.getString("achievement_example")),
                            key.scenarioId());
            if (goals.isEmpty() || goals.stream().anyMatch(g -> g.evidence().isEmpty()))
                return Optional.empty();
            readBudget(deadline);
            var current = progress.get().load(key, deadline);
            deadline.requireActive();
            if (current == null || current.isEmpty()) return Optional.empty();
            var policy = current.get();
            var statuses = current.get().microGoalStatuses();
            var owner = owned.getFirst();
            if (!statuses.keySet().equals(goals.stream().map(Goal::id).collect(Collectors.toSet()))
                    || policy.turnCount() != Math.addExact(owner.lastTurn(), 1)
                    || (owner.lastTurn() > 0
                            && (owner.currentGoal() == null || owner.support() == null))
                    || (owner.currentGoal() != null
                            && !owner.currentGoal().equals(policy.currentMicroGoalId()))
                    || (owner.support() != null
                            && !owner.support().equals(policy.currentSupportLevel()))) {
                return Optional.empty();
            }
            var approvedGoals = goals.stream().map(Goal::id).collect(Collectors.toSet());
            if (!approvedGoals.contains(policy.currentMicroGoalId())) return Optional.empty();
            var responseHistory = responseHistory(key, owner, approvedGoals, deadline);
            var currentResponses = responseHistory.byGoal().get(policy.currentMicroGoalId());
            int supportTurn =
                    responseHistory
                            .inputHistory()
                            .nextSupportTurn(policy.currentMicroGoalId())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "입력 당시 목표가 누락되어 유도 턴을 복원할 수 없습니다."));
            var state =
                    new RoleplayTurnInput.State(
                            policy.currentMicroGoalId(),
                            policy.currentSupportLevel(),
                            supportTurn,
                            currentResponses.questionCount(),
                            currentResponses.answerRevealed(),
                            policy.turnCount(),
                            policy.maximumTurnCount(),
                            policy.maximumSupportTurnsPerMicroGoal());
            var scenario =
                    new RoleplayTurnInput.Scenario(
                            owner.learningGoal(),
                            content.get("scenario_level").toString(),
                            strings(content.get("scenario_facts").toString()),
                            strings(content.get("prohibited_inferences").toString()),
                            goals.stream()
                                    .map(
                                            g ->
                                                    new RoleplayTurnInput.MicroGoal(
                                                            g.id(),
                                                            g.description(),
                                                            g.evidence(),
                                                            statuses.get(g.id())))
                                    .toList());
            if (goals.stream().noneMatch(g -> g.id().equals(state.currentMicroGoalId())))
                return Optional.empty();

            var pinned = new LinkedHashMap<String, Object>();
            for (var field :
                    List.of(
                            "scenario_id",
                            "scenario_version",
                            "class_id",
                            "goal_content_hash",
                            "scenario_level",
                            "title",
                            "summary",
                            "ai_role",
                            "characters",
                            "scenario_facts",
                            "prohibited_inferences",
                            "opening_prompts")) {
                pinned.put(
                        field, content.get(field) == null ? null : content.get(field).toString());
            }
            pinned.put("learning_goal", owner.learningGoal());
            pinned.put("micro_goals", goals);
            var approvalReference =
                    "scenario-context/v1:" + digest(json.writeValueAsString(pinned));
            readBudget(deadline);
            var assessments =
                    jdbc.queryForList(
                            "select scenario_id, scenario_version, checkpoint_version, approval_reference, analysis_contract_version, evaluation_contract_version from roleplay_goal_assessment where session_id=?",
                            key.sessionId());
            for (var assessment : assessments) {
                if (!key.scenarioId().equals(assessment.get("scenario_id"))
                        || key.scenarioVersion()
                                != ((Number) assessment.get("scenario_version")).intValue()
                        || key.checkpointVersion()
                                < ((Number) assessment.get("checkpoint_version")).longValue()
                        || !approvalReference.equals(assessment.get("approval_reference"))
                        || !GoalObservationOutput.ANALYSIS_VERSION.equals(
                                assessment.get("analysis_contract_version"))
                        || !GoalObservationOutput.EVALUATION_VERSION.equals(
                                assessment.get("evaluation_contract_version"))) {
                    return Optional.empty();
                }
            }
            readBudget(deadline);
            var history =
                    jdbc.query(
                            """
                    select o.turn_id, o.micro_goal_id, o.criterion_reference, o.observation_status
                    from roleplay_goal_observation o join roleplay_goal_assessment a using(session_id, turn_id)
                    where o.session_id=? order by a.checkpoint_version, o.micro_goal_id, o.criterion_reference
                    """,
                            (rs, n) ->
                                    new GoalObservationOutput.Observation(
                                            new GoalObservationOutput.Reference(
                                                    rs.getObject("turn_id", UUID.class),
                                                    rs.getObject("micro_goal_id", UUID.class),
                                                    rs.getString("criterion_reference")),
                                            GoalObservationOutput.Status.valueOf(
                                                    rs.getString("observation_status"))),
                            key.sessionId());
            var criteria =
                    goals.stream()
                            .flatMap(
                                    g ->
                                            g.evidence().stream()
                                                    .map(
                                                            e ->
                                                                    new GoalObservationInput
                                                                            .Criterion(
                                                                            g.id(),
                                                                            "evidence/v1:"
                                                                                    + digest(
                                                                                            g.id()
                                                                                                    + "\n"
                                                                                                    + e),
                                                                            e)))
                            .toList();
            var observations =
                    new GoalObservationInput(
                            criteria,
                            history,
                            RoleplayGoalObservationHistory.link(history, responseHistory),
                            RoleplayGoalObservationHistory.beforeNextInput(responseHistory));
            var snapshot =
                    new Snapshot(
                            key,
                            approvalReference,
                            scenario,
                            state,
                            dialogue(key.sessionId(), deadline),
                            observations,
                            responseHistory);
            deadline.requireActive();
            return Optional.of(snapshot);
        } catch (IllegalArgumentException invalidStoredContext) {
            return Optional.empty();
        }
    }

    private List<RoleplayTurnInput.DialogueLine> dialogue(
            UUID sessionId, RoleplayTurnDeadline deadline) {
        readBudget(deadline);
        var rows =
                new ArrayList<>(
                        jdbc.queryForList(
                                "select canonical_utterance, response_text from roleplay_turn where session_id=? order by turn_number desc limit ?",
                                sessionId,
                                RoleplayTurnInput.MAX_DIALOGUE_LINES));
        Collections.reverse(rows);
        var lines = new ArrayList<RoleplayTurnInput.DialogueLine>();
        for (var row : rows) {
            var canonical = (String) row.get("canonical_utterance");
            if (canonical != null && !canonical.isBlank())
                lines.add(
                        new RoleplayTurnInput.DialogueLine(
                                RoleplayTurnInput.Speaker.LEARNER, canonical));
            lines.add(
                    new RoleplayTurnInput.DialogueLine(
                            RoleplayTurnInput.Speaker.AI, (String) row.get("response_text")));
        }
        return List.copyOf(
                lines.subList(
                        Math.max(0, lines.size() - RoleplayTurnInput.MAX_DIALOGUE_LINES),
                        lines.size()));
    }

    private RoleplayGoalResponseHistory responseHistory(
            Key key,
            Owned owner,
            java.util.Set<UUID> approvedGoals,
            RoleplayTurnDeadline deadline) {
        readBudget(deadline);
        var responses =
                jdbc.query(
                        """
                select turn_id, candidate_id, turn_number, checkpoint_version, micro_goal_id,
                    support_level, response_type, strategy_used,
                    input_micro_goal_id, input_support_level, input_support_turn, input_prompt_count, input_answer_revealed
                from roleplay_turn where session_id=? order by turn_number
                """,
                        (rs, n) ->
                                new RoleplayGoalResponseHistory.Response(
                                        rs.getObject("turn_id", UUID.class),
                                        rs.getObject("candidate_id", UUID.class),
                                        rs.getInt("turn_number"),
                                        rs.getLong("checkpoint_version"),
                                        rs.getObject("micro_goal_id", UUID.class),
                                        rs.getString("support_level"),
                                        rs.getString("response_type"),
                                        rs.getString("strategy_used"),
                                        inputContext(rs)),
                        key.sessionId());
        deadline.requireActive();
        return RoleplayGoalResponseHistory.restore(
                approvedGoals,
                new RoleplayGoalResponseHistory.Checkpoint(
                        owner.lastTurn(),
                        key.checkpointVersion(),
                        owner.lastTurnId(),
                        owner.currentGoal(),
                        owner.support()),
                responses);
    }

    private List<String> strings(String value) {
        var node = json.readTree(value);
        if (!node.isArray()) throw new IllegalArgumentException("승인 콘텐츠의 배열 형식이 올바르지 않습니다.");
        var result = new ArrayList<String>();
        for (var element : node) {
            if (!element.isString() || element.asString().isBlank())
                throw new IllegalArgumentException("승인 콘텐츠의 학습 증거와 사실은 빈 문자열일 수 없습니다.");
            result.add(element.asString());
        }
        return List.copyOf(result);
    }

    private RoleplayInputContext inputContext(ResultSet row) throws SQLException {
        var goal = row.getObject("input_micro_goal_id", UUID.class);
        var support = row.getString("input_support_level");
        var turn = row.getObject("input_support_turn", Integer.class);
        var questions = row.getObject("input_prompt_count", Integer.class);
        var revealed = row.getObject("input_answer_revealed", Boolean.class);
        if (goal == null
                && support == null
                && turn == null
                && questions == null
                && revealed == null) return null;
        if (goal == null
                || support == null
                || turn == null
                || questions == null
                || revealed == null)
            throw new IllegalArgumentException("입력 당시 목표·지원 상태가 일부 누락됐습니다.");
        return new RoleplayInputContext(goal, support, turn, questions, revealed);
    }

    private void readBudget(RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        long millis = Math.max(1, (deadline.remainingNanos() + 999999) / 1000000);
        jdbc.queryForObject(
                "select set_config('lock_timeout', ?, true)", String.class, millis + "ms");
        jdbc.queryForObject(
                "select set_config('statement_timeout', ?, true)", String.class, millis + "ms");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("승인 콘텐츠의 지문을 계산할 수 없습니다.", impossible);
        }
    }

    private record Owned(
            String learningGoal, int lastTurn, UUID lastTurnId, UUID currentGoal, String support) {}

    private record Goal(
            UUID id, int order, String description, List<String> evidence, String example) {}
}
