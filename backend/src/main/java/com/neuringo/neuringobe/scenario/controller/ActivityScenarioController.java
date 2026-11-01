package com.neuringo.neuringobe.scenario.controller;

import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.scenario.dto.AttachScenarioRequest;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateResponse;
import com.neuringo.neuringobe.scenario.dto.ScenarioResponse;
import com.neuringo.neuringobe.scenario.service.ScenarioReuseService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 담당 강사가 최초 결합 활동의 재사용 후보를 하나씩 보고, 승인한 후보를 활동에 붙인다. */
@RestController
public class ActivityScenarioController {
    private final ScenarioReuseService reuse;
    private final SensitiveReadLog sensitiveReads;

    public ActivityScenarioController(ScenarioReuseService reuse, SensitiveReadLog sensitiveReads) {
        this.reuse = reuse;
        this.sensitiveReads = sensitiveReads;
    }

    @GetMapping("/api/v1/activities/{activityId}/scenario-candidate")
    public ApiResponse<ScenarioCandidateResponse> candidate(
            @PathVariable UUID activityId,
            @RequestParam(required = false) UUID after,
            Authentication authentication) {
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.ACTIVITY,
                        activityId,
                        () -> reuse.candidate(activityId, after, authentication)));
    }

    @PutMapping("/api/v1/activities/{activityId}/scenario")
    public ApiResponse<ScenarioResponse> attach(
            @PathVariable UUID activityId,
            @RequestBody @Valid AttachScenarioRequest request,
            Authentication authentication) {
        return ApiResponse.of(reuse.attach(activityId, request.scenarioId(), authentication));
    }
}
