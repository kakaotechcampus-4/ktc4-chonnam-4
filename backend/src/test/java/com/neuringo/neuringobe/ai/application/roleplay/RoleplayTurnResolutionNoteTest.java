package com.neuringo.neuringobe.ai.application.roleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.observation.RoleplayTiming;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.RecoveryReason;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 턴이 왜 그 결과로 끝났는지가 실행 기록용 판정 사건(DECISION)으로 나오는지 확인한다. 설계된 안내(입력 품질·안전 판정·모델이 요청한 입력 확인)와 오류(호출
 * 실패·한도·시간 초과)가 error 값으로 갈려야 대시보드가 두 흐름을 나눠 보여 줄 수 있다.
 */
class RoleplayTurnResolutionNoteTest {
    private final RoleplayTurnOutcomeResolver resolver =
            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of()));

    @Test
    void 입력_품질로_거절되면_사유_코드가_남고_오류로_보지_않는다() {
        var note = noted(new RoleplayTurnResult.InputRejected(InputFailure.LOW_CONFIDENCE));

        assertThat(note)
                .containsEntry("event", "DECISION")
                .containsEntry("step", "TURN_RESOLUTION")
                .containsEntry("verdict", "INPUT_REJECTED")
                .containsEntry("accepted", false)
                .containsEntry("error", false)
                .containsEntry("codes", List.of("LOW_CONFIDENCE"))
                .containsEntry("next", "NOTICE_INPUT_RECOVERY");
    }

    @Test
    void 음성_인식_호출이_실패해_거절되면_오류다() {
        var note = noted(new RoleplayTurnResult.InputRejected(InputFailure.TRANSCRIPTION_FAILED));

        assertThat(note).containsEntry("verdict", "INPUT_REJECTED").containsEntry("error", true);
    }

    @Test
    void 안전하지_않은_입력은_설계된_중단이라_오류가_아니다() {
        var note = noted(new RoleplayTurnResult.InputUnsafe());

        assertThat(note).containsEntry("verdict", "INPUT_UNSAFE").containsEntry("error", false);
    }

    @Test
    void 입력_판정을_못_했거나_시간이_넘으면_오류다() {
        assertThat(noted(new RoleplayTurnResult.InputCheckUnavailable()))
                .containsEntry("verdict", "INPUT_CHECK_UNAVAILABLE")
                .containsEntry("error", true);
        assertThat(noted(new RoleplayTurnResult.TimedOut()))
                .containsEntry("verdict", "TIMED_OUT")
                .containsEntry("error", true)
                .containsEntry("next", "NOTICE_TIMEOUT");
    }

    @Test
    void 재시도_한도에_걸리면_어느_단계에서_걸렸는지와_함께_오류로_남는다() {
        var note =
                noted(
                        new RoleplayTurnResult.RecoveryRequired(
                                RetryTarget.SAFE_FALLBACK,
                                AiOperation.RESPONSE_GENERATION,
                                RecoveryReason.LIMIT_REACHED));

        assertThat(note)
                .containsEntry("verdict", "LIMIT_REACHED")
                .containsEntry("error", true)
                .containsEntry("operation", AiOperation.RESPONSE_GENERATION)
                .containsEntry("codes", List.of("SAFE_FALLBACK"));
    }

    @Test
    void 검수_모델이_입력_확인을_요청한_것은_오류가_아니다() {
        var note =
                noted(
                        new RoleplayTurnResult.RecoveryRequired(
                                RetryTarget.INPUT_CONFIRMATION,
                                AiOperation.RESPONSE_EVALUATION,
                                RecoveryReason.MODEL_REQUESTED));

        assertThat(note).containsEntry("verdict", "MODEL_REQUESTED").containsEntry("error", false);
    }

    @Test
    void 판정_사건에는_안내_문구_글이_들어가지_않는다() {
        var note = noted(new RoleplayTurnResult.TimedOut());

        assertThat(note.values()).noneMatch(value -> String.valueOf(value).contains("말해줄래"));
    }

    @Test
    void 관측_중이_아니면_아무것도_남기지_않고_결과만_돌려준다() {
        assertThatCode(() -> resolver.resolve(new RoleplayTurnResult.TimedOut()))
                .doesNotThrowAnyException();
    }

    private Map<String, Object> noted(RoleplayTurnResult result) {
        var events = new ArrayList<Map<String, Object>>();
        try (var session = RoleplayTiming.observe(UUID.randomUUID(), events::add)) {
            resolver.resolve(result);
        }
        return events.stream()
                .filter(event -> "DECISION".equals(event.get("event")))
                .findFirst()
                .orElseThrow();
    }
}
