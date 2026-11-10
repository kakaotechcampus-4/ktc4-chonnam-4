package com.neuringo.neuringobe.ops.service;

import com.neuringo.neuringobe.ai.application.port.AgentRunRecorder.Workflow;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.ops.config.AgentRunProperties;
import com.neuringo.neuringobe.ops.domain.AgentRun;
import com.neuringo.neuringobe.ops.domain.AgentRunDiagnosis;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse.Bucket;
import com.neuringo.neuringobe.ops.dto.AgentRunResponse;
import com.neuringo.neuringobe.ops.repository.AgentRunStore;
import com.neuringo.neuringobe.user.domain.UserAccount;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 대시보드 조회. 강사 화면이 아니라 개발·운영 담당자가 보는 화면이라, 허용 목록(neuringo.agent-runs.operators)에 있는 계정만 읽는다. 목록에
 * 없는 계정에는 화면이 있다는 사실도 알리지 않도록 404 로 답한다. public-read 를 켜면 로그인 없이 누구나 읽는다(SecurityConfig 도 이 주소의 GET
 * 을 연다).
 */
@Service
@ConditionalOnProperty(name = "neuringo.agent-runs.enabled", havingValue = "true")
public class AgentRunQueryService {
    static final int MAX_HOURS = 24 * 14;
    static final int MAX_LIST = 200;
    static final int MAX_FLOWS = 60;

    private final AgentRunStore store;
    private final UserAccountRepository users;
    private final AgentRunProperties properties;
    private final Clock clock;
    private final Duration turnBudget;

    public AgentRunQueryService(
            AgentRunStore store,
            UserAccountRepository users,
            AgentRunProperties properties,
            Clock clock,
            @Value("${roleplay.http.turn-budget:60s}") Duration turnBudget) {
        this.store = store;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
        this.turnBudget = turnBudget;
    }

    @Transactional(readOnly = true)
    public AgentRunOverviewResponse overview(AuthenticatedUser user, int hours, String workflow) {
        requireOperator(user);
        int span = hours(hours);
        int bucketMinutes = bucketMinutes(span);
        // 끝을 분 단위로 맞춰, 같은 분 안에 다시 조회해도 구간 경계가 흔들리지 않게 한다.
        Instant to = clock.instant().truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        Instant from = to.minus(Duration.ofHours(span));
        var window = new AgentRunStore.Window(from, to, workflow(workflow));
        var now = snapshot(window);
        return new AgentRunOverviewResponse(
                new AgentRunOverviewResponse.Window(from, to, span, bucketMinutes),
                now.totals(),
                filled(store.series(window, bucketMinutes), from, to, bucketMinutes),
                now.workflows(),
                now.steps(),
                store.transitions(window, 200),
                store.codes(window, 30),
                now.endings().subList(0, Math.min(30, now.endings().size())),
                store.stages(window),
                now.flows().subList(0, Math.min(MAX_FLOWS, now.flows().size())),
                diagnosis(window, now, Duration.ofHours(span)));
    }

    private AgentRunDiagnosis.Snapshot snapshot(AgentRunStore.Window window) {
        return new AgentRunDiagnosis.Snapshot(
                store.totals(window),
                store.workflows(window),
                store.steps(window),
                store.endings(window, 200),
                store.flows(window, 200));
    }

    /** 바로 앞 같은 길이 기간과 비교한 자동 진단. 같은 워크플로우 조건으로 앞 기간을 읽는다. */
    private AgentRunOverviewResponse.Diagnosis diagnosis(
            AgentRunStore.Window window, AgentRunDiagnosis.Snapshot now, Duration span) {
        var earlierWindow =
                new AgentRunStore.Window(
                        window.from().minus(span), window.from(), window.workflow());
        var before = snapshot(earlierWindow);
        var slowest = store.slowest(window, Workflow.ROLEPLAY_TURN.name(), 3);
        return new AgentRunOverviewResponse.Diagnosis(
                earlierWindow.from(),
                earlierWindow.to(),
                before.totals().runs(),
                AgentRunDiagnosis.failureRate(now.totals()),
                AgentRunDiagnosis.failureRate(before.totals()),
                AgentRunDiagnosis.diagnose(now, before, turnBudget.toMillis(), slowest));
    }

    /**
     * 최근 순 실행 목록. path 를 주면 그 흐름 하나, outcome(SUCCESS·FAILURE)을 주면 그 묶음의 흐름들만 준다. 둘 다 주면 path 가 그
     * 묶음에 속해야 한다.
     */
    @Transactional(readOnly = true)
    public List<AgentRunResponse> list(
            AuthenticatedUser user,
            int hours,
            String workflow,
            String path,
            String outcome,
            int limit) {
        requireOperator(user);
        Instant to = clock.instant().plusSeconds(60);
        var window =
                new AgentRunStore.Window(
                        to.minus(Duration.ofHours(hours(hours))), to, workflow(workflow));
        return store
                .list(window, paths(path, outcome), Math.max(1, Math.min(MAX_LIST, limit)))
                .stream()
                .map(AgentRunResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentRunResponse detail(AuthenticatedUser user, UUID runId) {
        requireOperator(user);
        return store.find(runId)
                .map(AgentRunResponse::from)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "AGENT_RUN_NOT_FOUND", "실행 기록을 찾을 수 없습니다."));
    }

    private void requireOperator(AuthenticatedUser user) {
        if (properties.publicRead()) return;
        if (user != null && properties.everyInstructor()) return;
        boolean allowed =
                user != null
                        && users.findById(user.userId())
                                .map(UserAccount::getEmail)
                                .map(UserAccount::normalizeEmail)
                                .filter(properties.operators()::contains)
                                .isPresent();
        if (!allowed)
            throw new ResourceNotFoundException("RESOURCE_NOT_FOUND", "요청한 자원을 찾을 수 없습니다.");
    }

    private static int hours(int hours) {
        if (hours < 1 || hours > MAX_HOURS) throw invalid();
        return hours;
    }

    /** 어느 기간을 골라도 구간이 12~28개가 되게 한다. */
    static int bucketMinutes(int hours) {
        if (hours <= 1) return 5;
        if (hours <= 6) return 15;
        if (hours <= 24) return 60;
        if (hours <= 72) return 180;
        return hours <= 168 ? 360 : 720;
    }

    private static String workflow(String workflow) {
        if (workflow == null || workflow.isBlank()) return null;
        try {
            return Workflow.valueOf(workflow).name();
        } catch (IllegalArgumentException unknown) {
            throw invalid();
        }
    }

    private static List<String> paths(String path, String outcome) {
        AgentRun.Path one = null;
        AgentRun.Outcome group = null;
        try {
            if (path != null && !path.isBlank()) one = AgentRun.Path.valueOf(path);
            if (outcome != null && !outcome.isBlank()) group = AgentRun.Outcome.valueOf(outcome);
        } catch (IllegalArgumentException unknown) {
            throw invalid();
        }
        if (one != null && group != null && one.outcome() != group) throw invalid();
        if (one != null) return List.of(one.name());
        if (group != null) return group.paths().stream().map(Enum::name).toList();
        return List.of();
    }

    private static ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_AGENT_RUN_FILTER", "조회 조건이 올바르지 않습니다.");
    }

    /** 실행이 없던 구간을 0 으로 채워, 화면이 빈 시간대를 빈 칸으로 그릴 수 있게 한다. */
    static List<Bucket> filled(List<Bucket> found, Instant from, Instant to, int bucketMinutes) {
        var byStart = new HashMap<Instant, Bucket>();
        found.forEach(bucket -> byStart.put(bucket.at(), bucket));
        var series = new ArrayList<Bucket>();
        Duration step = Duration.ofMinutes(bucketMinutes);
        for (Instant at = from; at.isBefore(to); at = at.plus(step))
            series.add(byStart.getOrDefault(at, new Bucket(at, 0, 0, 0, 0, 0, 0)));
        return series;
    }
}
