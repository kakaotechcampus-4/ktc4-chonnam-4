package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.prompt.ReportRevision;
import com.neuringo.neuringobe.ai.application.report.LearningReportResult.FailureReason;
import com.neuringo.neuringobe.ai.application.report.ReportRound.Outcome;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewDecision;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewScores;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 리포트 초안 → 규칙 검사 → 평가 → 고쳐 쓰기를 정해진 바퀴만큼 되풀이한다.
 *
 * <ol>
 *   <li>초안을 쓴다. 다시 쓰는 바퀴면 이전 초안과 고칠 점을 같이 넘긴다.
 *   <li>규칙 검사(ReportQualityRule)에 걸리면 평가 호출 없이 바로 다음 바퀴로 간다. 평가 비용을 아낀다.
 *   <li>평가 모델이 PASS 를 주고 모든 기준 점수가 minimumScore 이상이면 끝난다. PASS 인데 점수가 낮으면 돌려보낸 것으로 본다.
 *   <li>MAX_ROUNDS 바퀴 안에 통과하지 못하면 실패다. 실패한 리포트는 강사에게 보이지 않는다.
 * </ol>
 *
 * <p>호출 하나가 재시도할 수 있는 이유로 실패하면 같은 입력으로 MAX_CALL_ATTEMPTS 번까지 다시 부른다. 최악의 호출 수는 MAX_ROUNDS ×
 * MAX_CALL_ATTEMPTS × 2 다. 카운터는 generate 호출마다 새로 만들어 요청끼리 섞이지 않는다.
 */
public final class RetryingLearningReportGenerator {

    static final int MAX_ROUNDS = 3;
    static final int MAX_CALL_ATTEMPTS = 2;
    public static final int DEFAULT_MINIMUM_SCORE = 3;

    private final List<ReportQualityRule> rules;
    private final int minimumScore;

    public RetryingLearningReportGenerator(List<ReportQualityRule> rules) {
        this(rules, DEFAULT_MINIMUM_SCORE);
    }

    public RetryingLearningReportGenerator(List<ReportQualityRule> rules, int minimumScore) {
        this.rules = List.copyOf(rules);
        if (minimumScore < 0 || minimumScore > ReportReviewScores.MAX_SCORE) {
            throw new IllegalArgumentException("minimumScore is out of range");
        }
        this.minimumScore = minimumScore;
    }

    public LearningReportResult generate(LearningReportInput input, LearningReportSteps steps) {
        return new Run(Objects.requireNonNull(input), Objects.requireNonNull(steps)).execute();
    }

    private final class Run {
        private final LearningReportInput input;
        private final LearningReportSteps steps;
        private final List<ReportRound> rounds = new ArrayList<>();
        private int draftCalls;
        private int reviewCalls;

        private Run(LearningReportInput input, LearningReportSteps steps) {
            this.input = input;
            this.steps = steps;
        }

        private LearningReportResult execute() {
            ReportRevision revision = null;
            AiOperation lastRejection = AiOperation.REPORT_REVIEW;
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                ReportRevision current = revision;
                AiCallResult<ReportDraft> drafted =
                        withRetry(
                                () -> {
                                    draftCalls++;
                                    return steps.draft(input, current, attempts(), draftCalls);
                                });
                if (drafted instanceof AiCallResult.Failure<ReportDraft> failure) {
                    return callFailed(round, failure, AiOperation.REPORT_GENERATION);
                }
                ReportDraft draft = ((AiCallResult.Success<ReportDraft>) drafted).data();

                List<ReportIssue> issues =
                        rules.stream().flatMap(rule -> rule.check(draft, input).stream()).toList();
                if (!issues.isEmpty()) {
                    List<String> codes = codesOf(issues);
                    rounds.add(new ReportRound(round, Outcome.RULES_REJECTED, codes, null));
                    revision = new ReportRevision(draft, codes, instructionFor(codes, issues));
                    lastRejection = AiOperation.REPORT_GENERATION;
                    continue;
                }

                AiCallResult<ReportReview> reviewed =
                        withRetry(
                                () -> {
                                    reviewCalls++;
                                    return steps.review(input, draft, attempts(), reviewCalls);
                                });
                if (reviewed instanceof AiCallResult.Failure<ReportReview> failure) {
                    return callFailed(round, failure, AiOperation.REPORT_REVIEW);
                }
                ReportReview review = ((AiCallResult.Success<ReportReview>) reviewed).data();
                if (passes(review)) {
                    rounds.add(new ReportRound(round, Outcome.PASSED, List.of(), review.scores()));
                    return new LearningReportResult.Completed(draft, review, rounds);
                }

                List<String> codes =
                        review.decision() == ReportReviewDecision.REVISE
                                ? review.failureCodes()
                                : lowScoreCodes(review);
                RevisionInstruction instruction =
                        review.revisionInstruction() != null
                                ? review.revisionInstruction()
                                : instructionFor(codes, List.of());
                rounds.add(new ReportRound(round, Outcome.REVIEW_REJECTED, codes, review.scores()));
                revision = new ReportRevision(draft, codes, instruction);
                lastRejection = AiOperation.REPORT_REVIEW;
            }
            return new LearningReportResult.Failed(
                    FailureReason.QUALITY_LIMIT_REACHED, lastRejection, rounds);
        }

        private <T> AiCallResult<T> withRetry(Supplier<AiCallResult<T>> call) {
            AiCallResult<T> result = call.get();
            for (int attempt = 2; attempt <= MAX_CALL_ATTEMPTS; attempt++) {
                if (!(result instanceof AiCallResult.Failure<T> failure)
                        || !failure.failure().retryable()) {
                    return result;
                }
                result = call.get();
            }
            return result;
        }

        private LearningReportResult callFailed(
                int round, AiCallResult.Failure<?> failure, AiOperation operation) {
            rounds.add(
                    new ReportRound(
                            round,
                            Outcome.CALL_FAILED,
                            List.of(failure.failure().type().name()),
                            null));
            FailureReason reason =
                    failure.failure().retryable()
                            ? FailureReason.CALL_LIMIT_REACHED
                            : FailureReason.NON_RETRYABLE_FAILURE;
            return new LearningReportResult.Failed(reason, operation, rounds);
        }

        private AiAttemptContext attempts() {
            return new AiAttemptContext(0, draftCalls, reviewCalls);
        }
    }

    private boolean passes(ReportReview review) {
        return review.decision() == ReportReviewDecision.PASS
                && review.failureCodes().isEmpty()
                && review.everyItemChecked()
                && review.scores().min() >= minimumScore;
    }

    /** PASS 인데 통과 기준에 못 미친 이유를 실패 코드로 바꾼다. 항목 확인 실패가 점수보다 먼저다. */
    private List<String> lowScoreCodes(ReportReview review) {
        ReportReviewScores scores = review.scores();
        List<String> codes = new ArrayList<>();
        if (review.itemChecks().stream().anyMatch(check -> !check.grounded())) {
            codes.add(ReportCodes.UNGROUNDED_CLAIM);
        }
        if (review.itemChecks().stream().anyMatch(check -> !check.rightSection())) {
            codes.add(ReportCodes.MISPLACED_ITEM);
        }
        if (scores.grounded() < minimumScore && !codes.contains(ReportCodes.UNGROUNDED_CLAIM)) {
            codes.add(ReportCodes.UNGROUNDED_CLAIM);
        }
        if (scores.nonDiagnostic() < minimumScore) codes.add(ReportCodes.DIAGNOSTIC_LANGUAGE);
        if (scores.technicalSeparation() < minimumScore) {
            codes.add(ReportCodes.TECHNICAL_ERROR_AS_DIFFICULTY);
        }
        if (scores.actionable() < minimumScore) codes.add(ReportCodes.NOT_ACTIONABLE);
        if (scores.readable() < minimumScore) codes.add(ReportCodes.HARD_TO_READ);
        return codes;
    }

    private static List<String> codesOf(List<ReportIssue> issues) {
        Set<String> codes = new LinkedHashSet<>();
        issues.forEach(issue -> codes.add(issue.code()));
        return List.copyOf(codes);
    }

    /** 코드마다 정해 둔 지시를 change 에, 규칙이 찾은 구체적인 문제를 avoid 에 넣는다. */
    private static RevisionInstruction instructionFor(
            List<String> codes, List<ReportIssue> issues) {
        List<String> change = codes.stream().map(ReportCodes::guidance).toList();
        List<String> avoid = issues.stream().map(ReportIssue::detail).distinct().toList();
        return new RevisionInstruction(List.of(), change, avoid, List.of());
    }
}
