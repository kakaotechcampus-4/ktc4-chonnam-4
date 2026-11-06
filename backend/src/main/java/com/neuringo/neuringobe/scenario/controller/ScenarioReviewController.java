package com.neuringo.neuringobe.scenario.controller;

import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.scenario.domain.ScenarioReviewAction;
import com.neuringo.neuringobe.scenario.dto.*;
import com.neuringo.neuringobe.scenario.service.ScenarioConfirmationService;
import com.neuringo.neuringobe.scenario.service.ScenarioPreparationService;
import com.neuringo.neuringobe.scenario.service.ScenarioReviewTransactions;
import com.neuringo.neuringobe.scenario.service.ScenarioReviewViewService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/activities/{activityId}")
public class ScenarioReviewController {
    private final ScenarioPreparationService preparations;
    private final ScenarioConfirmationService confirmations;
    private final ScenarioReviewTransactions transactions;
    private final ScenarioReviewViewService views;
    private final SensitiveReadLog sensitiveReads;

    public ScenarioReviewController(
            ScenarioPreparationService preparations,
            ScenarioConfirmationService confirmations,
            ScenarioReviewTransactions transactions,
            ScenarioReviewViewService views,
            SensitiveReadLog sensitiveReads) {
        this.preparations = preparations;
        this.confirmations = confirmations;
        this.transactions = transactions;
        this.views = views;
        this.sensitiveReads = sensitiveReads;
    }

    @PostMapping("/scenario-review")
    public ApiResponse<ScenarioReviewResponse> prepare(
            @PathVariable UUID activityId,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @RequestBody @Valid ScenarioReviewRequest request,
            Authentication authentication) {
        request.validate();
        return ApiResponse.of(
                sensitiveReads.read(
                        authentication,
                        SensitiveReadLog.Target.ACTIVITY,
                        activityId,
                        () -> {
                            var snapshot =
                                    request.action() == ScenarioReviewAction.START_OR_RESUME
                                            ? preparations.prepare(
                                                    activityId, requestId, authentication)
                                            : preparations.next(
                                                    activityId,
                                                    requestId,
                                                    request.reviewId(),
                                                    request.revision(),
                                                    authentication);
                            return views.response(snapshot, authentication);
                        }));
    }

    @PutMapping("/scenario")
    public ApiResponse<ScenarioReviewClosure> confirm(
            @PathVariable UUID activityId,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @RequestBody @Valid ScenarioConfirmationRequest request,
            Authentication authentication) {
        return ApiResponse.of(
                confirmations.confirm(
                        activityId,
                        requestId,
                        request.reviewId(),
                        request.revision(),
                        request.scenarioId(),
                        request.scenarioVersion(),
                        request.edits(),
                        authentication));
    }

    @DeleteMapping("/scenario-review")
    public ApiResponse<ScenarioReviewClosure> cancel(
            @PathVariable UUID activityId,
            @RequestHeader("Idempotency-Key") UUID requestId,
            @RequestBody @Valid ScenarioReviewCancelRequest request,
            Authentication authentication) {
        return ApiResponse.of(
                transactions.cancel(
                        activityId,
                        requestId,
                        request.reviewId(),
                        request.revision(),
                        authentication));
    }
}
