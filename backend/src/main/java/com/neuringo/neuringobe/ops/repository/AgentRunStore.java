package com.neuringo.neuringobe.ops.repository;

import com.neuringo.neuringobe.ops.domain.AgentRun;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Bucket;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.CodeCount;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Ending;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.StageTime;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.StepStat;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Totals;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Transition;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.WorkflowStat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * agent_run·agent_run_step 저장소. 쓰기는 실행이 끝날 때 한 번, 읽기는 운영 대시보드 조회뿐이다.
 *
 * <p>요약 질의는 기간(from 이상 to 미만)과 선택한 워크플로우로만 거른다. 건수가 많아도 started_at 색인으로 기간부터 좁힌다.
 */
@Repository
@ConditionalOnProperty(name = "neuringo.agent-runs.enabled", havingValue = "true")
public class AgentRunStore {
    private static final String PATH_COUNTS =
            """
            count(*) filter (where r.path = 'NORMAL') as normal,
            count(*) filter (where r.path = 'RETRIED') as retried,
            count(*) filter (where r.path = 'GUIDED') as guided,
            count(*) filter (where r.path = 'FAILED') as failed
            """;
    private static final String RUN_COLUMNS =
            """
            r.run_id, r.workflow, r.path, r.result, r.ended_by, r.reason, r.input_type, r.http_status,
            r.replayed, r.started_at, r.duration_ms, r.ai_calls, r.repeated_calls, r.input_tokens,
            r.output_tokens, r.truncated
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AgentRunStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 기간과 워크플로우 조건. 질의마다 같은 조건을 같은 순서의 인자로 붙인다. */
    public record Window(Instant from, Instant to, String workflow) {
        private String where() {
            return " r.started_at >= ? and r.started_at < ?"
                    + (workflow == null ? "" : " and r.workflow = ?");
        }

        private List<Object> args() {
            var args = new ArrayList<Object>(List.of(Timestamp.from(from), Timestamp.from(to)));
            if (workflow != null) args.add(workflow);
            return args;
        }
    }

    /** 같은 실행 번호가 이미 있으면 아무것도 넣지 않는다. 호출하는 쪽이 트랜잭션으로 묶는다. */
    public void insert(AgentRun run) {
        int inserted =
                jdbc.update(
                        """
                        insert into agent_run (run_id, workflow, path, result, ended_by, reason, input_type,
                            http_status, replayed, started_at, duration_ms, ai_calls, repeated_calls,
                            input_tokens, output_tokens, truncated, stage_ms, spans)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        on conflict (run_id) do nothing
                        """,
                        run.runId(),
                        run.workflow(),
                        run.path().name(),
                        run.result(),
                        run.endedBy(),
                        run.reason(),
                        run.inputType(),
                        run.httpStatus(),
                        run.replayed(),
                        Timestamp.from(run.startedAt()),
                        clamp(run.durationMs()),
                        Math.min(run.aiCalls(), Short.MAX_VALUE),
                        Math.min(run.repeatedCalls(), Short.MAX_VALUE),
                        run.inputTokens(),
                        run.outputTokens(),
                        run.truncated(),
                        json.writeValueAsString(run.stageMs()),
                        json.writeValueAsString(run.spans()));
        if (inserted == 0) return;
        jdbc.batchUpdate(
                """
                insert into agent_run_step (run_id, seq, kind, step, attempt, status, verdict, codes,
                    declared_next, led_to, at_ms, duration_ms, model, prompt_version, finish_reason,
                    input_tokens, output_tokens)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.steps().stream()
                        .map(
                                step ->
                                        new Object[] {
                                            run.runId(),
                                            step.seq(),
                                            step.kind().name(),
                                            step.step(),
                                            step.attempt(),
                                            step.status().name(),
                                            step.verdict(),
                                            json.writeValueAsString(step.codes()),
                                            step.declaredNext(),
                                            step.ledTo(),
                                            clamp(step.atMs()),
                                            clamp(step.durationMs()),
                                            step.model(),
                                            step.promptVersion(),
                                            step.finishReason(),
                                            step.inputTokens(),
                                            step.outputTokens()
                                        })
                        .toList());
    }

    public int deleteStartedBefore(Instant cutoff) {
        return jdbc.update("delete from agent_run where started_at < ?", Timestamp.from(cutoff));
    }

    public Totals totals(Window window) {
        return jdbc.queryForObject(
                "select count(*) as runs, "
                        + PATH_COUNTS
                        + """
                        , coalesce(percentile_cont(0.5) within group (order by r.duration_ms), 0) as p50,
                        coalesce(percentile_cont(0.95) within group (order by r.duration_ms), 0) as p95,
                        coalesce(sum(r.ai_calls), 0) as ai_calls,
                        coalesce(sum(r.input_tokens), 0) as input_tokens,
                        coalesce(sum(r.output_tokens), 0) as output_tokens
                        from agent_run r where
                        """
                        + window.where(),
                (rs, row) ->
                        new Totals(
                                rs.getLong("runs"),
                                rs.getLong("normal"),
                                rs.getLong("retried"),
                                rs.getLong("guided"),
                                rs.getLong("failed"),
                                Math.round(rs.getDouble("p50")),
                                Math.round(rs.getDouble("p95")),
                                rs.getLong("ai_calls"),
                                rs.getLong("input_tokens"),
                                rs.getLong("output_tokens")),
                window.args().toArray());
    }

    /** 실행이 있던 구간만 돌려준다. 빈 구간은 조회 서비스가 0 으로 채운다. */
    public List<Bucket> series(Window window, int bucketMinutes) {
        var args = new ArrayList<Object>(List.of(bucketMinutes, Timestamp.from(window.from())));
        args.addAll(window.args());
        return jdbc.query(
                "select date_bin(make_interval(mins => ?), r.started_at, ?::timestamptz) as bucket, "
                        + PATH_COUNTS
                        + """
                        , coalesce(percentile_cont(0.5) within group (order by r.duration_ms), 0) as p50,
                        coalesce(percentile_cont(0.95) within group (order by r.duration_ms), 0) as p95
                        from agent_run r where
                        """
                        + window.where()
                        + " group by bucket order by bucket",
                (rs, row) ->
                        new Bucket(
                                rs.getTimestamp("bucket").toInstant(),
                                rs.getLong("normal"),
                                rs.getLong("retried"),
                                rs.getLong("guided"),
                                rs.getLong("failed"),
                                Math.round(rs.getDouble("p50")),
                                Math.round(rs.getDouble("p95"))),
                args.toArray());
    }

    public List<WorkflowStat> workflows(Window window) {
        return jdbc.query(
                "select r.workflow, count(*) as runs, "
                        + PATH_COUNTS
                        + """
                        , coalesce(percentile_cont(0.5) within group (order by r.duration_ms), 0) as p50,
                        coalesce(percentile_cont(0.95) within group (order by r.duration_ms), 0) as p95
                        from agent_run r where
                        """
                        + window.where()
                        + " group by r.workflow order by r.workflow",
                (rs, row) ->
                        new WorkflowStat(
                                rs.getString("workflow"),
                                rs.getLong("runs"),
                                rs.getLong("normal"),
                                rs.getLong("retried"),
                                rs.getLong("guided"),
                                rs.getLong("failed"),
                                Math.round(rs.getDouble("p50")),
                                Math.round(rs.getDouble("p95"))),
                window.args().toArray());
    }

    /** AI·음성 호출 단계별 건수·시간·토큰. 흐름에 나오는 순서대로 준다. */
    public List<StepStat> steps(Window window) {
        return jdbc.query(
                """
                select r.workflow, s.step, count(*) as calls,
                    count(*) filter (where s.status = 'PASSED') as passed,
                    count(*) filter (where s.status = 'REJECTED') as rejected,
                    count(*) filter (where s.status = 'FAILED') as failed,
                    coalesce(avg(s.duration_ms), 0) as avg_ms,
                    coalesce(percentile_cont(0.95) within group (order by s.duration_ms), 0) as p95,
                    coalesce(sum(s.input_tokens), 0) as input_tokens,
                    coalesce(sum(s.output_tokens), 0) as output_tokens
                from agent_run_step s join agent_run r on r.run_id = s.run_id
                where s.kind = 'CALL' and
                """
                        + window.where()
                        + " group by r.workflow, s.step order by r.workflow, avg(s.seq), s.step",
                (rs, row) ->
                        new StepStat(
                                rs.getString("workflow"),
                                rs.getString("step"),
                                rs.getLong("calls"),
                                rs.getLong("passed"),
                                rs.getLong("rejected"),
                                rs.getLong("failed"),
                                Math.round(rs.getDouble("avg_ms")),
                                Math.round(rs.getDouble("p95")),
                                rs.getLong("input_tokens"),
                                rs.getLong("output_tokens")),
                window.args().toArray());
    }

    /** 단계 → (판정) → 실제로 이어진 다음 단계. 많이 일어난 순서로 준다. */
    public List<Transition> transitions(Window window, int limit) {
        var args = window.args();
        args.add(limit);
        return jdbc.query(
                """
                select r.workflow, s.step, s.status, coalesce(s.verdict, '') as verdict, s.led_to,
                    count(*) as runs
                from agent_run_step s join agent_run r on r.run_id = s.run_id
                where s.kind <> 'RESULT' and s.led_to is not null and
                """
                        + window.where()
                        + " group by r.workflow, s.step, s.status, s.verdict, s.led_to"
                        + " order by runs desc, r.workflow, s.step limit ?",
                (rs, row) ->
                        new Transition(
                                rs.getString("workflow"),
                                rs.getString("step"),
                                rs.getString("status"),
                                rs.getString("verdict"),
                                rs.getString("led_to"),
                                rs.getLong("runs")),
                args.toArray());
    }

    public List<CodeCount> codes(Window window, int limit) {
        var args = window.args();
        args.add(limit);
        return jdbc.query(
                """
                select r.workflow, s.step, c.code, count(*) as runs
                from agent_run_step s join agent_run r on r.run_id = s.run_id
                    cross join lateral jsonb_array_elements_text(s.codes) as c(code)
                where s.status <> 'PASSED' and
                """
                        + window.where()
                        + " group by r.workflow, s.step, c.code order by runs desc, c.code limit ?",
                (rs, row) ->
                        new CodeCount(
                                rs.getString("workflow"),
                                rs.getString("step"),
                                rs.getString("code"),
                                rs.getLong("runs")),
                args.toArray());
    }

    public List<Ending> endings(Window window, int limit) {
        var args = window.args();
        args.add(limit);
        return jdbc.query(
                """
                select r.workflow, r.path, r.result, coalesce(r.ended_by, '') as ended_by,
                    coalesce(r.reason, '') as reason, count(*) as runs
                from agent_run r where r.path <> 'NORMAL' and
                """
                        + window.where()
                        + " group by r.workflow, r.path, r.result, r.ended_by, r.reason"
                        + " order by runs desc, r.workflow, r.result limit ?",
                (rs, row) ->
                        new Ending(
                                rs.getString("workflow"),
                                rs.getString("path"),
                                rs.getString("result"),
                                rs.getString("ended_by"),
                                rs.getString("reason"),
                                rs.getLong("runs")),
                args.toArray());
    }

    /** 역할극 턴의 구간별 자체 시간. 다른 워크플로우는 호출 구간뿐이라 단계별 통계로 충분하다. */
    public List<StageTime> stages(Window window) {
        return jdbc.query(
                """
                select e.key as stage, avg(e.value::numeric) as avg_ms,
                    percentile_cont(0.95) within group (order by e.value::numeric) as p95
                from agent_run r cross join lateral jsonb_each_text(r.stage_ms) as e
                where r.workflow = 'ROLEPLAY_TURN' and
                """
                        + window.where()
                        + " group by e.key order by avg_ms desc",
                (rs, row) ->
                        new StageTime(
                                rs.getString("stage"),
                                Math.round(rs.getDouble("avg_ms")),
                                Math.round(rs.getDouble("p95"))),
                window.args().toArray());
    }

    /** 최근 순 목록. 단계·구간은 싣지 않는다. */
    public List<AgentRun> list(Window window, String path, int limit) {
        var args = window.args();
        if (path != null) args.add(path);
        args.add(limit);
        return jdbc.query(
                "select "
                        + RUN_COLUMNS
                        + " from agent_run r where"
                        + window.where()
                        + (path == null ? "" : " and r.path = ?")
                        + " order by r.started_at desc, r.run_id limit ?",
                (rs, row) -> run(rs, Map.of(), List.of(), List.of()),
                args.toArray());
    }

    public Optional<AgentRun> find(UUID runId) {
        var steps =
                jdbc.query(
                        """
                        select seq, kind, step, attempt, status, verdict, codes::text as codes,
                            declared_next, led_to, at_ms, duration_ms, model, prompt_version,
                            finish_reason, input_tokens, output_tokens
                        from agent_run_step where run_id = ? order by seq
                        """,
                        (rs, row) ->
                                new AgentRun.Step(
                                        rs.getInt("seq"),
                                        AgentRun.Kind.valueOf(rs.getString("kind")),
                                        rs.getString("step"),
                                        rs.getInt("attempt"),
                                        AgentRun.Status.valueOf(rs.getString("status")),
                                        rs.getString("verdict"),
                                        json.readValue(
                                                rs.getString("codes"),
                                                new TypeReference<List<String>>() {}),
                                        rs.getString("declared_next"),
                                        rs.getString("led_to"),
                                        rs.getLong("at_ms"),
                                        rs.getLong("duration_ms"),
                                        rs.getString("model"),
                                        rs.getString("prompt_version"),
                                        rs.getString("finish_reason"),
                                        (Integer) rs.getObject("input_tokens"),
                                        (Integer) rs.getObject("output_tokens")),
                        runId);
        return jdbc
                .query(
                        "select "
                                + RUN_COLUMNS
                                + ", r.stage_ms::text as stage_ms, r.spans::text as spans"
                                + " from agent_run r where r.run_id = ?",
                        (rs, row) ->
                                run(
                                        rs,
                                        json.readValue(
                                                rs.getString("stage_ms"),
                                                new TypeReference<Map<String, Long>>() {}),
                                        json.readValue(
                                                rs.getString("spans"),
                                                new TypeReference<List<AgentRun.Span>>() {}),
                                        steps),
                        runId)
                .stream()
                .findFirst();
    }

    private static AgentRun run(
            ResultSet rs,
            Map<String, Long> stageMs,
            List<AgentRun.Span> spans,
            List<AgentRun.Step> steps)
            throws SQLException {
        Object httpStatus = rs.getObject("http_status");
        return new AgentRun(
                rs.getObject("run_id", UUID.class),
                rs.getString("workflow"),
                AgentRun.Path.valueOf(rs.getString("path")),
                rs.getString("result"),
                rs.getString("ended_by"),
                rs.getString("reason"),
                rs.getString("input_type"),
                httpStatus == null ? null : ((Number) httpStatus).intValue(),
                rs.getBoolean("replayed"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getLong("duration_ms"),
                rs.getInt("ai_calls"),
                rs.getInt("repeated_calls"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getBoolean("truncated"),
                stageMs,
                spans,
                steps);
    }

    private static int clamp(long value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }
}
