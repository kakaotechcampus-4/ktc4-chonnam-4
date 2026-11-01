package com.neuringo.neuringobe.scenario.controller;

import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.scenario.dto.CreateScenarioVersionRequest;
import com.neuringo.neuringobe.scenario.dto.ReviewScenarioRequest;
import com.neuringo.neuringobe.scenario.dto.ScenarioResponse;
import com.neuringo.neuringobe.scenario.service.ScenarioDraftService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 시나리오 조회와 [수정하기] 초안의 새 판 만들기·고치기·판정·폐기. 계약은 contracts/api/v1/scenarios 에 있다. */
@RestController
public class ScenarioController {
    private final ScenarioDraftService drafts;

    public ScenarioController(ScenarioDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping("/api/v1/scenarios/{scenarioId}")
    public ApiResponse<ScenarioResponse> get(
            @PathVariable UUID scenarioId, Authentication authentication) {
        return ApiResponse.of(drafts.get(scenarioId, authentication));
    }

    @PostMapping("/api/v1/scenarios/{scenarioId}/versions")
    public ResponseEntity<ApiResponse<ScenarioResponse>> createVersion(
            @PathVariable UUID scenarioId,
            @RequestBody @Valid CreateScenarioVersionRequest request,
            Authentication authentication) {
        var result = drafts.createVersion(scenarioId, request, authentication);
        if (!result.created()) {
            // 같은 목표·같은 원본의 초안이 이미 있으면 다시 만들지 않고 그것을 준다.
            return ResponseEntity.ok(ApiResponse.of(result.scenario()));
        }
        return ResponseEntity.created(
                        URI.create("/api/v1/scenarios/" + result.scenario().scenarioId()))
                .body(ApiResponse.of(result.scenario()));
    }

    @PatchMapping("/api/v1/scenarios/{scenarioId}")
    public ApiResponse<ScenarioResponse> review(
            @PathVariable UUID scenarioId,
            @RequestBody @Valid ReviewScenarioRequest request,
            Authentication authentication) {
        return ApiResponse.of(drafts.review(scenarioId, request, authentication));
    }

    @DeleteMapping("/api/v1/scenarios/{scenarioId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID scenarioId, Authentication authentication) {
        drafts.delete(scenarioId, authentication);
        return ResponseEntity.noContent().build();
    }
}
