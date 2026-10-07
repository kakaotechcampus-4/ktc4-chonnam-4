package com.neuringo.neuringobe.ai.application.roleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.ApprovedNotice;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog.Kind;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome.Action;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome.AssessmentEligibility;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome.Retention;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.RecoveryReason;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplayTurnOutcomeResolverTest {
    private static final UUID TURN = UUID.randomUUID();
    private static final UUID CANDIDATE = UUID.randomUUID();
    private static final UUID GOAL = UUID.randomUUID();
    private final RoleplayTurnOutcomeResolver resolver = new RoleplayTurnOutcomeResolver(catalog());

    @Test
    void approvedOutputCarriesSameCandidateTextButNoAnalysisOrEvaluatorDetails() {
        var outcome = (RoleplayTurnOutcome.ApprovedResponse) resolver.resolve(ready());
        assertThat(outcome.candidateId()).isEqualTo(CANDIDATE);
        assertThat(outcome.text()).isEqualTo("친구는 어떤 기분일까?");
        assertThat(outcome.assessmentEligibility()).isEqualTo(AssessmentEligibility.ELIGIBLE);
        assertThat(JsonMapper.builder().build().writeValueAsString(outcome))
                .doesNotContain(
                        "internal-analysis", "failure_codes", "safe_to_send", TURN.toString());
    }

    @Test
    void timeoutAlwaysUsesTheConfirmedLiteralWithoutInjectedConfiguration() {
        var defaults = new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of()));
        var outcome =
                (RoleplayTurnOutcome.Guidance) defaults.resolve(new RoleplayTurnResult.TimedOut());
        assertThat(outcome.notice().text()).isEqualTo("다시 한 번만 말해줄래?");
        assertThat(outcome.notice().approvalReference()).isEqualTo("DEC-010");
        assertThat(outcome.action()).isEqualTo(Action.REQUEST_NEW_INPUT);
        assertThat(outcome.assessmentEligibility()).isEqualTo(AssessmentEligibility.NOT_ASSESSABLE);
    }

    @ParameterizedTest
    @CsvSource({
        "INPUT_CONFIRMATION,CAUSE_ANALYSIS,INPUT_CONFIRMATION,REQUEST_NEW_INPUT",
        "INPUT_CONFIRMATION,RESPONSE_EVALUATION,INPUT_CONFIRMATION,REQUEST_NEW_INPUT",
        "SAFE_FALLBACK,RESPONSE_GENERATION,SAFE_FALLBACK,REQUEST_NEW_INPUT",
        "SAFE_FALLBACK,RESPONSE_EVALUATION,SAFETY_CHECK_ERROR,RETRY_TURN"
    })
    void recoveryRoutesUseApprovedNoticeAndExcludeAssessment(
            RetryTarget target, AiOperation operation, Kind noticeKind, Action action) {
        var outcome = (RoleplayTurnOutcome.Guidance) resolver.resolve(recovery(target, operation));
        assertThat(outcome.notice().kind()).isEqualTo(noticeKind);
        assertThat(outcome.notice().version()).isEqualTo("synthetic-v1");
        assertThat(outcome.action()).isEqualTo(action);
        assertThat(outcome.assessmentEligibility()).isEqualTo(AssessmentEligibility.NOT_ASSESSABLE);
    }

    @ParameterizedTest
    @EnumSource(
            value = RecoveryReason.class,
            names = {"LIMIT_REACHED", "NON_RETRYABLE_FAILURE", "INCONSISTENT_EVALUATION"})
    void everyEvaluatorFailureRequiresErrorNoticeRatherThanLearningFallback(RecoveryReason reason) {
        var outcome =
                (RoleplayTurnOutcome.Guidance)
                        resolver.resolve(
                                new RoleplayTurnResult.RecoveryRequired(
                                        RetryTarget.SAFE_FALLBACK,
                                        AiOperation.RESPONSE_EVALUATION,
                                        reason));
        assertThat(outcome.notice().kind()).isEqualTo(Kind.SAFETY_CHECK_ERROR);
        assertThat(outcome.action()).isEqualTo(Action.RETRY_TURN);
        assertThat(outcome.assessmentEligibility()).isEqualTo(AssessmentEligibility.NOT_ASSESSABLE);
    }

    @Test
    void safetyStopRequiresBothAudiencesAndContainsNoSpeechOrEventMetadata() {
        var outcome =
                (RoleplayTurnOutcome.SafetyStop)
                        resolver.resolve(
                                recovery(
                                        RetryTarget.SAFETY_ESCALATION,
                                        AiOperation.RESPONSE_EVALUATION));
        assertThat(outcome.childNotice().kind()).isEqualTo(Kind.SAFETY_ESCALATION_CHILD);
        assertThat(outcome.teacherNotice().kind()).isEqualTo(Kind.SAFETY_ESCALATION_TEACHER);
        assertThat(outcome.action()).isEqualTo(Action.STOP_DIALOGUE);
        assertThat(outcome.retention()).isEqualTo(Retention.DO_NOT_STORE);
        assertThat(outcome.assessmentEligibility()).isEqualTo(AssessmentEligibility.NOT_ASSESSABLE);
        assertThat(JsonMapper.builder().build().writeValueAsString(outcome))
                .doesNotContain(TURN.toString(), CANDIDATE.toString(), "candidate", "analysis");
    }

    @ParameterizedTest
    @EnumSource(
            value = Kind.class,
            names = {"SAFETY_ESCALATION_CHILD", "SAFETY_ESCALATION_TEACHER"})
    void missingEitherSafetyNoticeStillStopsAndForbidsStorage(Kind missing) {
        var configured =
                Arrays.stream(Kind.values())
                        .filter(kind -> kind != missing && kind != Kind.TIMEOUT)
                        .map(this::notice)
                        .toList();
        var partial = new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(configured));
        var outcome =
                (RoleplayTurnOutcome.NoticeUnavailable)
                        partial.resolve(
                                recovery(
                                        RetryTarget.SAFETY_ESCALATION,
                                        AiOperation.RESPONSE_EVALUATION));
        assertThat(outcome.action()).isEqualTo(Action.STOP_DIALOGUE);
        assertThat(outcome.retention()).isEqualTo(Retention.DO_NOT_STORE);
        assertThat(outcome.assessmentEligibility()).isEqualTo(AssessmentEligibility.NOT_ASSESSABLE);
    }

    @ParameterizedTest
    @CsvSource({
        "INPUT_CONFIRMATION,CAUSE_ANALYSIS",
        "SAFE_FALLBACK,RESPONSE_GENERATION",
        "SAFE_FALLBACK,RESPONSE_EVALUATION"
    })
    void missingApprovedNoticeNeverInventsFallbackText(RetryTarget target, AiOperation operation) {
        var empty = new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of()));
        var outcome = empty.resolve(recovery(target, operation));
        assertThat(outcome).isInstanceOf(RoleplayTurnOutcome.NoticeUnavailable.class);
        assertThat(outcome.assessmentEligibility()).isEqualTo(AssessmentEligibility.NOT_ASSESSABLE);
    }

    @Test
    void uncoordinatedFailuresAndRetryTargetsAreNotDelivered() {
        var failed =
                new RoleplayTurnResult.CallFailed(
                        AiOperation.RESPONSE_GENERATION,
                        new AiFailure(AiFailureType.TIMEOUT, null),
                        metadata(AiOperation.RESPONSE_GENERATION));
        assertThatThrownBy(() -> resolver.resolve(failed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        recovery(
                                                RetryTarget.RESPONSE_GENERATION,
                                                AiOperation.RESPONSE_GENERATION)))
                .isInstanceOf(IllegalArgumentException.class);
        var rejected =
                new RoleplayTurnResult.Rejected(
                        new EvaluationResult(
                                CANDIDATE,
                                false,
                                EvaluationDecision.CONFIRM_INPUT,
                                RetryTarget.INPUT_CONFIRMATION,
                                0,
                                List.of("X"),
                                null));
        assertThatThrownBy(() -> resolver.resolve(rejected))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catalogCopiesConfigurationAndRejectsDuplicateKindsOrTimeoutOverride() {
        var input = new ArrayList<>(List.of(notice(Kind.INPUT_CONFIRMATION)));
        var configured = new RoleplayNoticeCatalog(input);
        input.clear();
        assertThat(configured.find(Kind.INPUT_CONFIRMATION)).isPresent();
        assertThatThrownBy(
                        () ->
                                new RoleplayNoticeCatalog(
                                        List.of(
                                                notice(Kind.INPUT_CONFIRMATION),
                                                notice(Kind.INPUT_CONFIRMATION))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoleplayNoticeCatalog(List.of(notice(Kind.TIMEOUT))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedNotice(Kind.SAFE_FALLBACK, "text", "v1", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responderConnectsGenerationRetryExhaustionToNoticeWithoutAnotherLlmCall() {
        AtomicInteger generationCalls = new AtomicInteger();
        var steps =
                new RetryableRoleplayTurnSteps() {
                    @Override
                    public AiCallResult<AnalysisResult> analyze(
                            UUID turnId, RoleplayRetryContext retry) {
                        return new AiCallResult.Success<>(
                                analysis(), metadata(AiOperation.CAUSE_ANALYSIS));
                    }

                    @Override
                    public AiCallResult<CandidateResponse> generate(
                            UUID turnId,
                            UUID candidateId,
                            AnalysisResult analysis,
                            RoleplayRetryContext retry) {
                        generationCalls.incrementAndGet();
                        return new AiCallResult.Failure<>(
                                new AiFailure(AiFailureType.TIMEOUT, null),
                                metadata(AiOperation.RESPONSE_GENERATION));
                    }

                    @Override
                    public AiCallResult<EvaluationResult> evaluate(
                            UUID turnId,
                            AnalysisResult analysis,
                            CandidateResponse candidate,
                            RoleplayRetryContext retry) {
                        throw new AssertionError("No candidate exists to evaluate");
                    }
                };
        try (var workers = Executors.newSingleThreadExecutor()) {
            var outcome =
                    (RoleplayTurnOutcome.Guidance)
                            new RoleplayTurnResponder(new RoleplayTurnRunner(workers), resolver)
                                    .execute(TURN, steps);
            assertThat(generationCalls.get()).isEqualTo(3);
            assertThat(outcome.notice().kind()).isEqualTo(Kind.SAFE_FALLBACK);
            assertThat(outcome.assessmentEligibility())
                    .isEqualTo(AssessmentEligibility.NOT_ASSESSABLE);
        }
    }

    @Test
    void responderMapsExpiredSharedBudgetWithoutStartingPipeline() {
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var outcome =
                    (RoleplayTurnOutcome.Guidance)
                            new RoleplayTurnResponder(new RoleplayTurnRunner(workers), resolver)
                                    .execute(
                                            deadline,
                                            shared -> {
                                                throw new AssertionError(
                                                        "Expired pipeline must not run");
                                            });
            assertThat(outcome.notice().kind()).isEqualTo(Kind.TIMEOUT);
        }
    }

    private RoleplayNoticeCatalog catalog() {
        return new RoleplayNoticeCatalog(
                Arrays.stream(Kind.values())
                        .filter(kind -> kind != Kind.TIMEOUT)
                        .map(this::notice)
                        .toList());
    }

    private ApprovedNotice notice(Kind kind) {
        return new ApprovedNotice(
                kind, "synthetic notice: " + kind, "synthetic-v1", "test-fixture-only");
    }

    private RoleplayTurnResult.RecoveryRequired recovery(
            RetryTarget target, AiOperation operation) {
        return new RoleplayTurnResult.RecoveryRequired(
                target, operation, RecoveryReason.LIMIT_REACHED);
    }

    private AnalysisResult analysis() {
        return new AnalysisResult(
                TURN,
                "ANSWER",
                "RELATED",
                "PARTIAL",
                new AnalysisResult.PrimaryGap("MISSING_EMOTION", "internal-analysis"),
                new AnalysisResult.NextStrategy("ASK", GOAL, null, "S1"),
                0.9);
    }

    private RoleplayTurnResult.Ready ready() {
        return new RoleplayTurnResult.Ready(
                analysis(),
                new CandidateResponse(
                        CANDIDATE, TURN, "친구는 어떤 기분일까?", "QUESTION", "ASK", GOAL, "S1", List.of()),
                new EvaluationResult(
                        CANDIDATE, true, EvaluationDecision.PASS, null, 0, List.of(), null));
    }

    private AiCallMetadata metadata(AiOperation operation) {
        return new AiCallMetadata(
                UUID.randomUUID(),
                operation,
                "fake",
                "fake-model",
                "test/v1",
                "v1",
                null,
                0,
                1,
                null,
                null,
                "stop");
    }
}
