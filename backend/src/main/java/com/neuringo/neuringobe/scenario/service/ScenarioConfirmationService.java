package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.scenario.dto.ScenarioEdits;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewClosure;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class ScenarioConfirmationService {
    private final ScenarioConfirmationPreparationService preparations;
    private final ScenarioReviewTransactions transactions;

    public ScenarioConfirmationService(
            ScenarioConfirmationPreparationService preparations,
            ScenarioReviewTransactions transactions) {
        this.preparations = preparations;
        this.transactions = transactions;
    }

    public ScenarioReviewClosure confirm(
            UUID activityId,
            UUID requestId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            Authentication authentication) {
        return confirm(
                activityId,
                requestId,
                reviewId,
                revision,
                scenarioId,
                version,
                ScenarioEdits.none(),
                authentication);
    }

    public ScenarioReviewClosure confirm(
            UUID activityId,
            UUID requestId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            ScenarioEdits requestedEdits,
            Authentication authentication) {
        var edits = requestedEdits == null ? ScenarioEdits.none() : requestedEdits.normalized();
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(reviewId);
        Objects.requireNonNull(scenarioId);
        if (revision < 1 || version < 1)
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_SCENARIO_CONFIRMATION",
                    "유효한 검토·시나리오 버전이 필요합니다.");
        var replay =
                transactions.replayConfirmation(
                        activityId,
                        requestId,
                        reviewId,
                        revision,
                        scenarioId,
                        version,
                        edits,
                        authentication);
        if (replay.isPresent()) return replay.get();
        com.neuringo.neuringobe.scenario.dto.ScenarioConfirmationPreparation prepared;
        try {
            prepared =
                    preparations.prepare(
                            activityId,
                            reviewId,
                            revision,
                            scenarioId,
                            version,
                            edits,
                            authentication);
        } catch (ApiException preflightFailure) {
            // 같은 요청이 검수 도중 먼저 확정돼 목록이 삭제된 경우 최초 결과를 복원한다.
            var committed =
                    transactions.replayConfirmation(
                            activityId,
                            requestId,
                            reviewId,
                            revision,
                            scenarioId,
                            version,
                            edits,
                            authentication);
            if (committed.isPresent()) return committed.get();
            throw preflightFailure;
        }
        return transactions.confirm(
                prepared, requestId, reviewId, revision, scenarioId, version, authentication);
    }
}
