package com.neuringo.neuringobe.scenario.controller;

import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.scenario.dto.RoleplayScenarioCandidatesResponse;
import com.neuringo.neuringobe.scenario.service.RoleplayScenarioSelectionService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 역할극 단독 배정에서 강사가 고를 승인 시나리오 후보(contracts/api/v1/scenarios). 담당 강사 Bearer 전용이다. */
@RestController
public class RoleplayScenarioController {
    private final RoleplayScenarioSelectionService selection;
    private final SensitiveReadLog sensitiveReads;

    public RoleplayScenarioController(
            RoleplayScenarioSelectionService selection, SensitiveReadLog sensitiveReads) {
        this.selection = selection;
        this.sensitiveReads = sensitiveReads;
    }

    @GetMapping("/api/v1/children/{childId}/roleplay-scenarios")
    public ApiResponse<RoleplayScenarioCandidatesResponse> listCandidates(
            @PathVariable UUID childId,
            @RequestParam String goalTitle,
            @RequestParam(required = false) String goalCategory,
            Authentication authentication) {
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.ROLEPLAY_SCENARIOS,
                        childId,
                        () ->
                                selection.candidates(
                                        childId, goalTitle, goalCategory, authentication)));
    }
}
