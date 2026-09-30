package com.neuringo.neuringobe.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.JacksonLlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 평가 결과가 아동에게 전달되는 조건(AIO→RPL 게이트, backend/docs/ai-provider-contract.md "출력 계약").
 *
 * <pre>safeToSend == true AND decision == PASS AND criticalFailureCount == 0</pre>
 *
 * <p>세 조건의 8가지 조합 중 셋이 모두 참인 1가지만 전달된다. PASS 가 아닌 판정은 안전하고 치명 실패가 없어도 하나도 전달되지 않는다. 조건이 하나 빠지거나 OR
 * 로 바뀌면, 또는 전달되는 판정이 새로 생기면 이 테스트가 실패한다.
 *
 * <p>게이트는 평가 결과 JSON 을 읽은 값으로 판단하므로, 형식이 틀린 결과는 읽는 단계에서 형식 오류(INVALID_OUTPUT_FORMAT)가 되어야 한다. 문자열
 * "true"·숫자 1 을 참으로, 숫자 0 을 첫 판정(PASS)으로, 0.9 를 0 으로 바꿔 읽으면 형식이 틀린 결과가 게이트를 통과한다(#22 의 2번). 테스트는
 * Spring 기본 설정과 같은 결과를 내는 {@code JsonMapper.builder().findAndAddModules()} 로 읽는다.
 */
class EvaluationResultDeliveryGateTest {

    private static final UUID CANDIDATE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000009");

    private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private static final String VALID_PASS =
            """
            {"candidate_id":"00000000-0000-0000-0000-000000000009","safe_to_send":true,
             "decision":"PASS","retry_target":null,"critical_failure_count":0,
             "failure_codes":[],"revision_instruction":null}""";

    @ParameterizedTest(name = "safeToSend={0}, PASS={1}, 치명 실패={2} → 전달 {3}")
    @CsvSource({
        "true,  true,  0, true",
        "true,  true,  1, false",
        "true,  false, 0, false",
        "true,  false, 1, false",
        "false, true,  0, false",
        "false, true,  1, false",
        "false, false, 0, false",
        "false, false, 1, false"
    })
    void deliversOnlyWhenAllThreeConditionsHold(
            boolean safeToSend, boolean pass, int criticalFailureCount, boolean deliverable) {
        EvaluationDecision decision =
                pass ? EvaluationDecision.PASS : EvaluationDecision.REGENERATE;

        assertThat(evaluation(safeToSend, decision, criticalFailureCount).canDeliver())
                .isEqualTo(deliverable);
    }

    @ParameterizedTest
    @EnumSource(value = EvaluationDecision.class, mode = EnumSource.Mode.EXCLUDE, names = "PASS")
    void neverDeliversNonPassDecision(EvaluationDecision decision) {
        assertThat(evaluation(true, decision, 0).canDeliver()).isFalse();
    }

    /** 위 조합표는 치명 실패를 0·1 로만 본다. 2건 이상, 아주 큰 값에서도 막히는지 본다. */
    @ParameterizedTest(name = "치명 실패 {0}건")
    @ValueSource(ints = {2, 3, 10, Integer.MAX_VALUE})
    void neverDeliversWithAnyCriticalFailure(int criticalFailureCount) {
        assertThat(evaluation(true, EvaluationDecision.PASS, criticalFailureCount).canDeliver())
                .isFalse();
    }

    @Test
    void readsCorrectlyTypedPassAsDeliverable() {
        assertThat(parser().parse(VALID_PASS).canDeliver()).isTrue();
    }

    /** 올바른 PASS 결과에서 값 하나만 틀린 형식으로 바꾼다. 모두 전달되면 안 되고, 형식 오류여야 한다. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "safe_to_send 가 문자열 \"true\" | \"safe_to_send\":true | \"safe_to_send\":\"true\"",
                "safe_to_send 가 숫자 1        | \"safe_to_send\":true | \"safe_to_send\":1",
                "decision 이 숫자 0            | \"decision\":\"PASS\" | \"decision\":0",
                "critical_failure_count 가 0.9 | \"critical_failure_count\":0 | \"critical_failure_count\":0.9"
            })
    void rejectsLooselyTypedEvaluationAsInvalidFormat(String label, String valid, String loose) {
        String json = VALID_PASS.replace(valid, loose);
        assertThat(json).as("바꿀 값이 원본에 있어야 한다").isNotEqualTo(VALID_PASS);

        assertThatThrownBy(() -> parser().parse(json))
                .as(label)
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void treatsNullCompletionAsInvalidFormat() {
        AiCallMetadata metadata =
                new AiCallMetadata(
                        UUID.randomUUID(),
                        AiOperation.RESPONSE_EVALUATION,
                        "fake",
                        "fake-model",
                        "prompt-v1",
                        "schema-v1",
                        "policy-v1",
                        1,
                        1,
                        1,
                        1,
                        "stop");
        StructuredLlmExecutor executor =
                new StructuredLlmExecutor(
                        request ->
                                new AiCallResult.Success<>(
                                        new LlmCompletion(
                                                "null", "fake", "fake-model", "stop", 1, 1),
                                        metadata));

        AiCallResult<EvaluationResult> result = executor.execute(null, parser());

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        assertThat(((AiCallResult.Failure<EvaluationResult>) result).failure().type())
                .isEqualTo(AiFailureType.INVALID_OUTPUT_FORMAT);
    }

    private static JacksonLlmOutputParser<EvaluationResult> parser() {
        return new JacksonLlmOutputParser<>(MAPPER, EvaluationResult.class);
    }

    /** canDeliver 는 retryTarget·revisionInstruction 을 보지 않는다. 생성자 검증을 통과할 값만 채운다. */
    private static EvaluationResult evaluation(
            boolean safeToSend, EvaluationDecision decision, int criticalFailureCount) {
        boolean pass = decision == EvaluationDecision.PASS;
        boolean regenerate =
                decision == EvaluationDecision.REGENERATE
                        || decision == EvaluationDecision.SAFETY_REGENERATE;
        return new EvaluationResult(
                CANDIDATE_ID,
                safeToSend,
                decision,
                pass ? null : RetryTarget.RESPONSE_GENERATION,
                criticalFailureCount,
                criticalFailureCount == 0 ? List.of() : List.of("SAFETY_FAILURE"),
                regenerate
                        ? new RevisionInstruction(
                                List.of(), List.of("질문을 줄인다."), List.of(), List.of())
                        : null);
    }
}
