package com.neuringo.neuringobe.ai.infrastructure.input;

import static org.assertj.core.api.Assertions.*;

import com.neuringo.neuringobe.ai.application.input.RoleplayPersonalInformationMasker;
import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ModelRoleplayInputProcessorTest {
    private final List<LlmRequest> requests = new ArrayList<>();
    private String output = judgment("ANALYZABLE", true, true, "0.93");
    private final LlmProvider provider =
            request -> {
                requests.add(request);
                return success(request, output);
            };
    private final ModelRoleplayInputProcessor processor =
            new ModelRoleplayInputProcessor(
                    provider, JsonMapper.builder().build(), id -> "김하늘", "합성 입력 판정 지시문");

    static AiCallResult<LlmCompletion> success(LlmRequest request, String json) {
        return new AiCallResult.Success<>(
                new LlmCompletion(json, "fake", "fake", "stop", null, null),
                new AiCallMetadata(
                        request.traceContext().requestId(),
                        request.operation(),
                        "fake",
                        "fake",
                        request.promptVersion(),
                        request.responseSchemaVersion(),
                        request.policyVersion(),
                        0,
                        1,
                        null,
                        null,
                        "stop"));
    }

    static RoleplayInputProcessor.Input input(String text) {
        return new RoleplayInputProcessor.Input(
                text,
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null),
                RoleplayTurnDeadline.start());
    }

    static String judgment(String decision, boolean preserved, boolean privacy, String confidence) {
        return "{\"decision\":\""
                + decision
                + "\",\"meaningPreserved\":"
                + preserved
                + ",\"privacyClear\":"
                + privacy
                + ",\"confidence\":"
                + confidence
                + "}";
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "모르겠어요",
                "도와주세요",
                "안 속상해요",
                "속상하지 않아요",
                "화가 나서 욕했어요",
                "우리 학교에서 친구가 넘어졌어요",
                "3번이요"
            })
    void preservesValidWordsIncludingNegationAndHelp(String text) {
        var result = (RoleplayInputProcessor.Accepted) processor.process(input(text));
        assertThat(result.canonicalUtterance()).isEqualTo(text);
        assertThat(result.assessment())
                .isEqualTo(new RoleplayInputProcessor.Assessment(0.93, true, true, false));
        assertThat(requests)
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.operation()).isEqualTo(AiOperation.INPUT_VALIDATION);
                            assertThat(r.callBudget().limit(Duration.ofSeconds(60)))
                                    .isLessThan(Duration.ofSeconds(60));
                        });
    }

    @Test
    void masksBeforeProviderAndReturnsServerTextOnly() {
        var result =
                (RoleplayInputProcessor.Accepted)
                        processor.process(
                                input("김하늘은  친구가 울어서 속상해요. 연락은 010-1234-5678, a@example.test"));
        assertThat(result.canonicalUtterance()).isEqualTo("[이름]은 친구가 울어서 속상해요. 연락은 [연락처], [이메일]");
        assertThat(result.assessment().personalInformationMasked()).isTrue();
        assertThat(requests.getFirst().userPrompt())
                .doesNotContain("김하늘", "010-1234-5678", "a@example.test");
        assertThat(result.toString()).doesNotContain("속상해요");
    }

    @ParameterizedTest
    @ValueSource(strings = {"내 이름은 민수야. 친구가 울어", "내 전화번호는 01012345678 친구가 울어", "김하늘한테 말했어요"})
    void masksExplicitPersonalInformation(String text) {
        var result = (RoleplayInputProcessor.Accepted) processor.process(input(text));
        assertThat(result.canonicalUtterance()).doesNotContain("민수", "01012345678", "김하늘");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "내 주소는 서울 어딘가야",
                "우리 학교는 비밀초야",
                "내 전화번호는 공일공이야",
                "\u200B01012345678",
                " ",
                "\uD800"
            })
    void uncertainPrivacyOrUnusableInputNeverReachesProvider(String text) {
        assertThat(processor.process(input(text)))
                .isInstanceOf(RoleplayInputProcessor.Invalid.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void nameDoesNotReplacePartOfOrdinaryWord() {
        var masked = new RoleplayPersonalInformationMasker().mask("지우개가 없어 속상해요", "지우");
        assertThat(masked.text()).isEqualTo("지우개가 없어 속상해요");
        assertThat(masked.changed()).isFalse();
    }

    @Test
    void unsafeDoesNotReturnAnyCanonicalText() {
        output = judgment("UNSAFE", true, true, "0.9");
        assertThat(processor.process(input("합성 위험 상황")))
                .isInstanceOf(RoleplayInputProcessor.Unsafe.class);
    }

    @Test
    void reinputAndUncertainMeaningAreNotAccepted() {
        output = judgment("REINPUT", false, true, "0.4");
        assertThat(processor.process(input("그게 저거")))
                .isInstanceOf(RoleplayInputProcessor.Invalid.class);
        output = judgment("ANALYZABLE", false, true, "0.9");
        assertThat(processor.process(input("그게 저거")))
                .isInstanceOf(RoleplayInputProcessor.Invalid.class);
        output = judgment("ANALYZABLE", true, false, "0.9");
        assertThat(processor.process(input("합성 개인정보")))
                .isInstanceOf(RoleplayInputProcessor.Invalid.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "not json",
                "{\"decision\":0}",
                "{\"decision\":\"ANALYZABLE\",\"meaningPreserved\":\"true\",\"privacyClear\":true,\"confidence\":0.9}",
                "{\"decision\":\"ANALYZABLE\",\"meaningPreserved\":true,\"privacyClear\":true,\"confidence\":2}"
            })
    void malformedJudgmentFailsClosedWithoutRetry(String json) {
        output = json;
        assertThat(processor.process(input("친구가 울어요")))
                .isInstanceOf(RoleplayInputProcessor.Unavailable.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void providerOrIdentityFailureNeverBecomesAnInputFailure() {
        var failing =
                new ModelRoleplayInputProcessor(
                        r -> {
                            throw new IllegalStateException("private text");
                        },
                        JsonMapper.builder().build(),
                        id -> "김하늘",
                        "지시문");
        assertThat(failing.process(input("친구가 울어요")))
                .isInstanceOf(RoleplayInputProcessor.Unavailable.class);
        var missing =
                new ModelRoleplayInputProcessor(
                        provider,
                        JsonMapper.builder().build(),
                        id -> {
                            throw new IllegalStateException("private name");
                        },
                        "지시문");
        assertThat(missing.process(input("친구가 울어요")))
                .isInstanceOf(RoleplayInputProcessor.Unavailable.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void cancelledOrLateCheckDoesNotReturnAccepted() {
        var input = input("친구가 울어요");
        input.deadline().cancel();
        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertThat(requests).isEmpty();
        var lateInput = input("친구가 울어요");
        var late =
                new ModelRoleplayInputProcessor(
                        r -> {
                            lateInput.deadline().cancel();
                            return success(r, output);
                        },
                        JsonMapper.builder().build(),
                        id -> "김하늘",
                        "지시문");
        assertThatThrownBy(() -> late.process(lateInput))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
    }

    @Test
    void contextlessProcessingNeverUsesNewUnboundedDeadline() {
        assertThat(processor.process("친구가 울어요"))
                .isInstanceOf(RoleplayInputProcessor.Unavailable.class);
        assertThat(requests).isEmpty();
    }
}
