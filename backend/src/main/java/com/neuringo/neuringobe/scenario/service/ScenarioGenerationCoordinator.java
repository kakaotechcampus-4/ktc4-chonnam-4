package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class ScenarioGenerationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ScenarioGenerationCoordinator.class);

    private record Key(UUID reviewId, long revision) {}

    private final ConcurrentHashMap<Key, CompletableFuture<ScenarioReviewSnapshot>> active =
            new ConcurrentHashMap<>();
    private final ScenarioReviewTransactions transactions;
    private final ScenarioGenerationService generator;

    public ScenarioGenerationCoordinator(
            ScenarioReviewTransactions transactions, ScenarioGenerationService generator) {
        this.transactions = transactions;
        this.generator = generator;
    }

    public ScenarioReviewSnapshot ensureCandidate(
            ScenarioReviewSnapshot snapshot, UUID requestId, Authentication authentication) {
        if (snapshot.candidate() != null) return snapshot;
        var key = new Key(snapshot.reviewId(), snapshot.revision());
        var mine = new CompletableFuture<ScenarioReviewSnapshot>();
        var existing = active.putIfAbsent(key, mine);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
        try {
            var context = transactions.prepareGeneration(snapshot, authentication);
            var result =
                    context.snapshot().candidate() != null
                            ? context.snapshot()
                            : transactions.saveGenerated(
                                    context,
                                    generator.generate(context, requestId),
                                    authentication);
            log.info(
                    "event=scenario_generation_review_ready activityId={} reviewId={} revision={}",
                    result.activityId(),
                    result.reviewId(),
                    result.revision());
            mine.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            active.remove(key, mine);
        }
    }
}
