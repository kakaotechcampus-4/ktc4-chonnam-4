package com.neuringo.neuringobe.ai.application.report;

import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.draft;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.failure;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.goodDraft;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.input;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.item;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.pass;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.revise;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.success;
import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.prompt.ReportRevision;
import com.neuringo.neuringobe.ai.application.report.LearningReportResult.FailureReason;
import com.neuringo.neuringobe.ai.application.report.ReportRound.Outcome;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewDecision;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewScores;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 리포트 초안 → 규칙 검사 → 평가 → 고쳐 쓰기 루프. 호출은 정해 둔 결과를 차례로 돌려주는 가짜로 대신한다. */
class RetryingLearningReportGeneratorTest {

    private final RetryingLearningReportGenerator generator =
            new RetryingLearningReportGenerator(ReportQualityRules.defaults(ReportLimits.DEFAULT));

    @Test
    void completesWhenFirstDraftPassesRulesAndReview() {
        ScriptedSteps steps = new ScriptedSteps().drafts(ok(goodDraft())).reviews(ok(pass()));

        LearningReportResult result = generator.generate(input(), steps);

        assertThat(result).isInstanceOf(LearningReportResult.Completed.class);
        assertThat(((LearningReportResult.Completed) result).draft()).isEqualTo(goodDraft());
        assertThat(result.rounds())
                .extracting(ReportRound::outcome)
                .containsExactly(Outcome.PASSED);
        assertThat(steps.revisions).containsExactly((ReportRevision) null);
    }

    @Test
    void ruleViolationSkipsReviewAndFeedsTheIssueIntoTheNextDraft() {
        ReportDraft unknownEvidence =
                draft("요약이에요.", List.of(item("없는 근거예요", "E9")), List.of(), List.of());
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(ok(unknownEvidence), ok(goodDraft()))
                        .reviews(ok(pass()));

        LearningReportResult result = generator.generate(input(), steps);

        assertThat(result).isInstanceOf(LearningReportResult.Completed.class);
        assertThat(steps.reviewCalls).isEqualTo(1);
        assertThat(result.rounds())
                .extracting(ReportRound::outcome)
                .containsExactly(Outcome.RULES_REJECTED, Outcome.PASSED);
        ReportRevision revision = steps.revisions.get(1);
        assertThat(revision.previousDraft()).isEqualTo(unknownEvidence);
        assertThat(revision.failureCodes()).containsExactly(ReportCodes.UNKNOWN_EVIDENCE);
        assertThat(revision.instruction().change())
                .containsExactly(ReportCodes.guidance(ReportCodes.UNKNOWN_EVIDENCE));
        assertThat(revision.instruction().avoid()).singleElement().asString().contains("E9");
    }

    @Test
    void reviewRevisionInstructionIsPassedToTheNextDraft() {
        ReportReview revise = revise(ReportCodes.UNGROUNDED_CLAIM);
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(ok(goodDraft()), ok(goodDraft()))
                        .reviews(ok(revise), ok(pass()));

        LearningReportResult result = generator.generate(input(), steps);

        assertThat(result).isInstanceOf(LearningReportResult.Completed.class);
        assertThat(result.rounds())
                .extracting(ReportRound::outcome)
                .containsExactly(Outcome.REVIEW_REJECTED, Outcome.PASSED);
        assertThat(result.rounds().getFirst().scores()).isEqualTo(revise.scores());
        assertThat(steps.revisions.get(1).instruction()).isEqualTo(revise.revisionInstruction());
        assertThat(steps.revisions.get(1).failureCodes())
                .containsExactly(ReportCodes.UNGROUNDED_CLAIM);
    }

    @Test
    void passBelowMinimumScoreIsTreatedAsRevision() {
        ReportReview lowPass =
                new ReportReview(
                        ReportReviewDecision.PASS,
                        new ReportReviewScores(4, 4, 4, 2, 4),
                        List.of(),
                        null);
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(ok(goodDraft()), ok(goodDraft()))
                        .reviews(ok(lowPass), ok(pass()));

        LearningReportResult result = generator.generate(input(), steps);

        assertThat(result).isInstanceOf(LearningReportResult.Completed.class);
        ReportRevision revision = steps.revisions.get(1);
        assertThat(revision.failureCodes()).containsExactly(ReportCodes.NOT_ACTIONABLE);
        assertThat(revision.instruction().change())
                .containsExactly(ReportCodes.guidance(ReportCodes.NOT_ACTIONABLE));
    }

    @Test
    void passWithAFailedItemCheckIsTreatedAsRevision() {
        ReportReview checkedPass =
                new ReportReview(
                        List.of(
                                new ReportReview.ItemCheck("observations", 0, true, true),
                                new ReportReview.ItemCheck("difficult_points", 0, true, false)),
                        ReportReviewDecision.PASS,
                        ReportTestFixtures.scores(4),
                        List.of(),
                        null);
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(ok(goodDraft()), ok(goodDraft()))
                        .reviews(ok(checkedPass), ok(pass()));

        LearningReportResult result = generator.generate(input(), steps);

        assertThat(result).isInstanceOf(LearningReportResult.Completed.class);
        assertThat(steps.revisions.get(1).failureCodes())
                .containsExactly(ReportCodes.MISPLACED_ITEM);
    }

    @Test
    void failsAfterMaxRoundsWithoutShowingTheReport() {
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(ok(goodDraft()), ok(goodDraft()), ok(goodDraft()))
                        .reviews(
                                ok(revise(ReportCodes.HARD_TO_READ)),
                                ok(revise(ReportCodes.HARD_TO_READ)),
                                ok(revise(ReportCodes.HARD_TO_READ)));

        LearningReportResult result = generator.generate(input(), steps);

        assertThat(result).isInstanceOf(LearningReportResult.Failed.class);
        LearningReportResult.Failed failed = (LearningReportResult.Failed) result;
        assertThat(failed.reason()).isEqualTo(FailureReason.QUALITY_LIMIT_REACHED);
        assertThat(failed.operation()).isEqualTo(AiOperation.REPORT_REVIEW);
        assertThat(result.rounds()).hasSize(RetryingLearningReportGenerator.MAX_ROUNDS);
        assertThat(steps.draftCalls).isEqualTo(3);
    }

    @Test
    void retriesARetryableCallFailureOnceWithTheSameRevision() {
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(fail(AiFailureType.TIMEOUT), ok(goodDraft()))
                        .reviews(fail(AiFailureType.INVALID_OUTPUT_FORMAT), ok(pass()));

        LearningReportResult result = generator.generate(input(), steps);

        assertThat(result).isInstanceOf(LearningReportResult.Completed.class);
        assertThat(steps.stageAttempts).containsExactly(1, 2);
        assertThat(steps.reviewStageAttempts).containsExactly(1, 2);
        assertThat(steps.attempts.getLast()).isEqualTo(new AiAttemptContext(0, 2, 2));
    }

    @Test
    void stopsAtNonRetryableFailure() {
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(fail(AiFailureType.AUTHENTICATION_ERROR), ok(goodDraft()));

        LearningReportResult result = generator.generate(input(), steps);

        LearningReportResult.Failed failed = (LearningReportResult.Failed) result;
        assertThat(failed.reason()).isEqualTo(FailureReason.NON_RETRYABLE_FAILURE);
        assertThat(failed.operation()).isEqualTo(AiOperation.REPORT_GENERATION);
        assertThat(steps.draftCalls).isEqualTo(1);
        assertThat(result.rounds().getFirst().failureCodes())
                .containsExactly("AUTHENTICATION_ERROR");
    }

    @Test
    void failsWhenRetryableReviewFailuresReachTheCallLimit() {
        ScriptedSteps steps =
                new ScriptedSteps()
                        .drafts(ok(goodDraft()))
                        .reviews(fail(AiFailureType.TIMEOUT), fail(AiFailureType.TIMEOUT));

        LearningReportResult.Failed failed =
                (LearningReportResult.Failed) generator.generate(input(), steps);

        assertThat(failed.reason()).isEqualTo(FailureReason.CALL_LIMIT_REACHED);
        assertThat(failed.operation()).isEqualTo(AiOperation.REPORT_REVIEW);
        assertThat(steps.reviewCalls).isEqualTo(RetryingLearningReportGenerator.MAX_CALL_ATTEMPTS);
    }

    private static AiCallResult<ReportDraft> ok(ReportDraft draft) {
        return success(draft, AiOperation.REPORT_GENERATION);
    }

    private static AiCallResult<ReportReview> ok(ReportReview review) {
        return success(review, AiOperation.REPORT_REVIEW);
    }

    private static <T> AiCallResult<T> fail(AiFailureType type) {
        return failure(type, AiOperation.REPORT_GENERATION);
    }

    private static final class ScriptedSteps implements LearningReportSteps {
        private final Deque<AiCallResult<ReportDraft>> drafts = new ArrayDeque<>();
        private final Deque<AiCallResult<ReportReview>> reviews = new ArrayDeque<>();
        private final List<ReportRevision> revisions = new ArrayList<>();
        private final List<Integer> stageAttempts = new ArrayList<>();
        private final List<Integer> reviewStageAttempts = new ArrayList<>();
        private final List<AiAttemptContext> attempts = new ArrayList<>();
        private int draftCalls;
        private int reviewCalls;

        @SafeVarargs
        final ScriptedSteps drafts(AiCallResult<ReportDraft>... results) {
            drafts.addAll(List.of(results));
            return this;
        }

        @SafeVarargs
        final ScriptedSteps reviews(AiCallResult<ReportReview>... results) {
            reviews.addAll(List.of(results));
            return this;
        }

        @Override
        public AiCallResult<ReportDraft> draft(
                LearningReportInput input,
                ReportRevision revision,
                AiAttemptContext attempts,
                int stageAttempt) {
            draftCalls++;
            revisions.add(revision);
            stageAttempts.add(stageAttempt);
            this.attempts.add(attempts);
            return next(drafts);
        }

        @Override
        public AiCallResult<ReportReview> review(
                LearningReportInput input,
                ReportDraft draft,
                AiAttemptContext attempts,
                int stageAttempt) {
            reviewCalls++;
            reviewStageAttempts.add(stageAttempt);
            this.attempts.add(attempts);
            return next(reviews);
        }

        private static <T> T next(Deque<T> queue) {
            if (queue.isEmpty()) throw new IllegalStateException("no scripted result left");
            return queue.removeFirst();
        }
    }
}
