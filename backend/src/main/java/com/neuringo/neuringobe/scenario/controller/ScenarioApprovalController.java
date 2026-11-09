package com.neuringo.neuringobe.scenario.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.scenario.dto.ScenarioApprovalItem;
import com.neuringo.neuringobe.scenario.service.ScenarioApprovalService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 콘텐츠·승인 → 승인 대기함. 내 학급의 활동만 모으므로 경로·쿼리에 남의 ID 가 없다. */
@RestController
public class ScenarioApprovalController {
    private final ScenarioApprovalService approvals;
    private final SensitiveReadLog sensitiveReads;

    public ScenarioApprovalController(
            ScenarioApprovalService approvals, SensitiveReadLog sensitiveReads) {
        this.approvals = approvals;
        this.sensitiveReads = sensitiveReads;
    }

    @GetMapping("/api/v1/scenario-approvals")
    public ApiResponse<List<ScenarioApprovalItem>> list(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.of(
                sensitiveReads.read(
                        user.userId(),
                        SensitiveReadLog.Target.SCENARIO_APPROVALS,
                        user.userId(),
                        () -> approvals.list(user.userId())));
    }
}
