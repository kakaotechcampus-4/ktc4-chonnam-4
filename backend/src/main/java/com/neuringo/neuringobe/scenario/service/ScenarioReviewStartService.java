package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioReviewStartService {
    private final InitialScenarioPreparationService preparations;
    private final ScenarioCandidateQueryService candidates;
    private final ScenarioReviewTransactions transactions;

    public ScenarioReviewStartService(
            InitialScenarioPreparationService preparations,
            ScenarioCandidateQueryService candidates,
            ScenarioReviewTransactions transactions) {
        this.preparations = preparations;
        this.candidates = candidates;
        this.transactions = transactions;
    }

    @Transactional(propagation = Propagation.NEVER)
    public ScenarioReviewSnapshot startOrResume(
            UUID activityId, UUID requestId, Authentication authentication) {
        Objects.requireNonNull(requestId);
        var input = preparations.prepare(activityId, authentication);
        var existing = transactions.resume(input, requestId, authentication);
        if (existing.snapshot() != null) return existing.snapshot();
        // 후보 조회·콘텐츠 검사는 쓰기 잠금 밖에서 실행한다.
        var selected = candidates.selectPrepared(input, authentication);
        return transactions.createOrResume(
                input, requestId, authentication, selected.candidates(), existing.epoch());
    }
}
