package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class ScenarioPreparationService {
    private final ScenarioReviewStartService starts;
    private final ScenarioReviewTransactions transactions;
    private final ScenarioGenerationCoordinator generation;

    public ScenarioPreparationService(
            ScenarioReviewStartService starts,
            ScenarioReviewTransactions transactions,
            ScenarioGenerationCoordinator generation) {
        this.starts = starts;
        this.transactions = transactions;
        this.generation = generation;
    }

    public ScenarioReviewSnapshot prepare(
            UUID activityId, UUID requestId, Authentication authentication) {
        return generation.ensureCandidate(
                starts.startOrResume(activityId, requestId, authentication),
                requestId,
                authentication);
    }

    public ScenarioReviewSnapshot next(
            UUID activityId,
            UUID requestId,
            UUID reviewId,
            long revision,
            Authentication authentication) {
        return generation.ensureCandidate(
                transactions.next(activityId, requestId, reviewId, revision, authentication),
                requestId,
                authentication);
    }
}
