package com.neuringo.neuringobe.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.JacksonLlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StructuredLlmExecutorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final FakeLlmProvider provider = new FakeLlmProvider();
    private final StructuredLlmExecutor executor = new StructuredLlmExecutor(provider);

    private LlmRequest request;
    private AiCallMetadata metadata;

    @BeforeEach
    void setUp() {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        request = request(requestId, AiOperation.CAUSE_ANALYSIS);
        metadata = metadata(requestId, AiOperation.CAUSE_ANALYSIS);
    }

    @Test
    void parsesValidAnalysisResult() {
        provider.willReturn(success(resource("valid-analysis-result.json")));

        AiCallResult<AnalysisResult> result =
                executor.execute(
                        request, new JacksonLlmOutputParser<>(objectMapper, AnalysisResult.class));

        assertThat(result).isInstanceOf(AiCallResult.Success.class);
        AnalysisResult analysis = ((AiCallResult.Success<AnalysisResult>) result).data();
        assertThat(analysis.learningState()).isEqualTo("PARTIAL_UNDERSTANDING");
        assertThat(analysis.primaryGap().code()).isEqualTo("MISSING_EMOTION");
    }

    @Test
    void parsesValidCandidateResponse() {
        request = request(request.traceContext().requestId(), AiOperation.RESPONSE_GENERATION);
        metadata = metadata(request.traceContext().requestId(), AiOperation.RESPONSE_GENERATION);
        provider.willReturn(success(resource("valid-candidate-response.json")));

        AiCallResult<CandidateResponse> result =
                executor.execute(
                        request,
                        new JacksonLlmOutputParser<>(objectMapper, CandidateResponse.class));

        assertThat(result).isInstanceOf(AiCallResult.Success.class);
        CandidateResponse candidate = ((AiCallResult.Success<CandidateResponse>) result).data();
        assertThat(candidate.text()).isEqualTo("친구가 울고 있어. 어떤 기분일까?");
        assertThat(candidate.supportLevel()).isEqualTo("S1");
    }

    @Test
    void parsesValidEvaluationAndAllowsOnlySafePass() {
        request = request(request.traceContext().requestId(), AiOperation.RESPONSE_EVALUATION);
        metadata = metadata(request.traceContext().requestId(), AiOperation.RESPONSE_EVALUATION);
        provider.willReturn(success(resource("valid-evaluation-result.json")));

        AiCallResult<EvaluationResult> result =
                executor.execute(
                        request,
                        new JacksonLlmOutputParser<>(objectMapper, EvaluationResult.class));

        assertThat(result).isInstanceOf(AiCallResult.Success.class);
        EvaluationResult evaluation = ((AiCallResult.Success<EvaluationResult>) result).data();
        assertThat(evaluation.decision()).isEqualTo(EvaluationDecision.PASS);
        assertThat(evaluation.canDeliver()).isTrue();
    }

    @Test
    void rejectsAnalysisWithMissingRequiredField() {
        provider.willReturn(success(resource("invalid-analysis-result.json")));

        AiCallResult<AnalysisResult> result =
                executor.execute(
                        request, new JacksonLlmOutputParser<>(objectMapper, AnalysisResult.class));

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        assertThat(((AiCallResult.Failure<AnalysisResult>) result).failure().type())
                .isEqualTo(AiFailureType.INVALID_OUTPUT_FORMAT);
    }

    @Test
    void rejectsEvaluationWithMissingSafeToSend() {
        request = request(request.traceContext().requestId(), AiOperation.RESPONSE_EVALUATION);
        metadata = metadata(request.traceContext().requestId(), AiOperation.RESPONSE_EVALUATION);
        provider.willReturn(success(resource("invalid-evaluation-result.json")));

        AiCallResult<EvaluationResult> result =
                executor.execute(
                        request,
                        new JacksonLlmOutputParser<>(objectMapper, EvaluationResult.class));

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        assertThat(((AiCallResult.Failure<EvaluationResult>) result).failure().type())
                .isEqualTo(AiFailureType.INVALID_OUTPUT_FORMAT);
    }

    @Test
    void deliveryRequiresSafePassWithoutCriticalFailure() {
        UUID candidateId = UUID.fromString("00000000-0000-0000-0000-000000000009");

        EvaluationResult unsafePass =
                new EvaluationResult(
                        candidateId, false, EvaluationDecision.PASS, null, 0, List.of(), null);
        EvaluationResult regenerate =
                new EvaluationResult(
                        candidateId,
                        true,
                        EvaluationDecision.REGENERATE,
                        RetryTarget.RESPONSE_GENERATION,
                        0,
                        List.of("TOO_DIFFICULT"),
                        new RevisionInstruction(
                                List.of(), List.of("질문을 줄인다."), List.of(), List.of()));
        EvaluationResult criticalFailure =
                new EvaluationResult(
                        candidateId,
                        true,
                        EvaluationDecision.PASS,
                        null,
                        1,
                        List.of("SAFETY_FAILURE"),
                        null);

        assertThat(unsafePass.canDeliver()).isFalse();
        assertThat(regenerate.canDeliver()).isFalse();
        assertThat(criticalFailure.canDeliver()).isFalse();
    }

    @Test
    void preservesProviderFailureWithoutParsing() {
        AiFailure failure = new AiFailure(AiFailureType.TIMEOUT, true, "TimeoutException");
        provider.willReturn(new AiCallResult.Failure<>(failure, metadata));

        AiCallResult<AnalysisResult> result =
                executor.execute(
                        request, new JacksonLlmOutputParser<>(objectMapper, AnalysisResult.class));

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        assertThat(((AiCallResult.Failure<AnalysisResult>) result).failure()).isEqualTo(failure);
    }

    private AiCallResult.Success<LlmCompletion> success(String content) {
        LlmCompletion completion =
                new LlmCompletion(content, "mockserver", "test-model", "stop", 10, 20);
        return new AiCallResult.Success<>(completion, metadata);
    }

    private static LlmRequest request(UUID requestId, AiOperation operation) {
        AiTraceContext traceContext =
                new AiTraceContext(
                        requestId,
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        1,
                        UUID.fromString("00000000-0000-0000-0000-000000000006"),
                        UUID.fromString("00000000-0000-0000-0000-000000000007"),
                        UUID.fromString("00000000-0000-0000-0000-000000000010"),
                        UUID.fromString("00000000-0000-0000-0000-000000000009"));
        return new LlmRequest(
                traceContext,
                new AiAttemptContext(1, 0, 0),
                operation,
                1,
                "Return only JSON.",
                "안전하게 정제된 표준 발화",
                "prompt-v1",
                "schema-v1",
                "policy-v1");
    }

    private static AiCallMetadata metadata(UUID requestId, AiOperation operation) {
        return new AiCallMetadata(
                requestId,
                operation,
                "mockserver",
                "test-model",
                "prompt-v1",
                "schema-v1",
                "policy-v1",
                10,
                1,
                10,
                20,
                "stop");
    }

    private static String resource(String name) {
        try (InputStream input =
                StructuredLlmExecutorTest.class.getResourceAsStream("/ai/mock/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read test resource: " + name, exception);
        }
    }
}
