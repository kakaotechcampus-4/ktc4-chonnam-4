package com.neuringo.neuringobe.ops.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.ops.dto.AgentRunOverviewResponse;
import com.neuringo.neuringobe.ops.dto.AgentRunResponse;
import com.neuringo.neuringobe.ops.service.AgentRunQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에이전트 워크플로우 실행 기록 조회(운영 대시보드 /ops/agent-runs). 강사 Bearer 로 로그인한 계정 가운데 허용 목록에 있는 계정만 읽는다. 기능을
 * 끄면(neuringo.agent-runs.enabled=false) 이 주소들은 없는 주소가 된다.
 */
@RestController
@ConditionalOnProperty(name = "neuringo.agent-runs.enabled", havingValue = "true")
public class AgentRunController {
    private final AgentRunQueryService queries;

    public AgentRunController(AgentRunQueryService queries) {
        this.queries = queries;
    }

    /** 최근 hours 시간의 요약. workflow 를 주면 그 워크플로우만 센다. */
    @GetMapping("/api/v1/ops/agent-runs/overview")
    public ApiResponse<AgentRunOverviewResponse> overview(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String workflow,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.of(queries.overview(user, hours, workflow));
    }

    /**
     * 최근 순 실행 목록. path 는 NORMAL·RETRIED·GUIDED·FAILED 가운데 하나, outcome 은 SUCCESS(NORMAL·RETRIED)·
     * FAILURE(GUIDED·FAILED)다.
     */
    @GetMapping("/api/v1/ops/agent-runs")
    public ApiResponse<List<AgentRunResponse>> list(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String workflow,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String outcome,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.of(queries.list(user, hours, workflow, path, outcome, limit));
    }

    /** 실행 한 건의 단계별 판정과 시간 구간. 역할극 턴은 X-Trace-Id 가 실행 번호다. */
    @GetMapping("/api/v1/ops/agent-runs/{runId}")
    public ApiResponse<AgentRunResponse> detail(
            @PathVariable UUID runId, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.of(queries.detail(user, runId));
    }
}
