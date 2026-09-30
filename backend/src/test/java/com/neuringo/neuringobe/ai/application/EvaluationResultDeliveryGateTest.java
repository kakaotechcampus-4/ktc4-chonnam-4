package com.neuringo.neuringobe.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AI 평가 결과가 아동에게 전달되는 조건(AIO→RPL 게이트, backend/docs/ai-provider-contract.md "출력 계약").
 *
 * <pre>safeToSend == true AND decision == PASS AND criticalFailureCount == 0</pre>
 *
 * <p>세 조건의 8가지 조합 중 셋이 모두 참인 1가지만 전달된다. PASS 가 아닌 판정은 안전하고 치명 실패가 없어도 하나도 전달되지 않는다. 조건이 하나 빠지거나 OR
 * 로 바뀌면, 또는 전달되는 판정이 새로 생기면 이 테스트가 실패한다.
 */
class EvaluationResultDeliveryGateTest {

    private static final UUID CANDIDATE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000009");

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
