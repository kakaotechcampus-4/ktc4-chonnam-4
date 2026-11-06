package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor.Accepted;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor.Invalid;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor.Unsafe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleBasedRoleplayInputProcessorTest {
    private final RuleBasedRoleplayInputProcessor processor = new RuleBasedRoleplayInputProcessor();

    private Accepted accepted(String input) {
        RoleplayInputProcessor.Result result = processor.process(input);
        assertThat(result).isInstanceOf(Accepted.class);
        return (Accepted) result;
    }

    @Test
    void passesPlainSpeechTidiedWithEveryAssessmentFilled() {
        Accepted result = accepted("  친구가   미끄럼틀에서\t넘어져서 아플 것 같아 ");

        assertThat(result.canonicalUtterance()).isEqualTo("친구가 미끄럼틀에서 넘어져서 아플 것 같아");
        assertThat(result.assessment())
                .isEqualTo(new RoleplayInputProcessor.Assessment(1.0, true, true, false));
    }

    @Test
    void doesNotTreatEverydayInjuryWordsAsUnsafe() {
        assertThat(processor.process("무릎에서 피가 나서 많이 아프겠다")).isInstanceOf(Accepted.class);
        assertThat(processor.process("넘어져서 울었어")).isInstanceOf(Accepted.class);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "우리 엄마 번호는 010-1234-5678 이야|우리 엄마 번호는 [전화번호] 이야",
                "01012345678로 전화해|[전화번호]로 전화해",
                "이메일은 kid@example.com 이야|이메일은 [이메일] 이야",
                "주민번호 120101-3123456|주민번호 [주민등록번호]",
                "우리 집은 101동 202호야|우리 집은 [주소]야",
                "용봉로 77 에 살아|[주소] 에 살아",
                "카드번호 12345678 이야|카드번호 [번호] 이야"
            })
    void masksPersonalInformationShapesAndReportsIt(String input, String expected) {
        Accepted result = accepted(input);

        assertThat(result.canonicalUtterance()).isEqualTo(expected);
        assertThat(result.assessment().personalInformationMasked()).isTrue();
        assertThat(result.assessment().normalizationConfidence()).isEqualTo(0.9);
        assertThat(result.assessment().safe()).isTrue();
        assertThat(result.assessment().meaningPreserved()).isTrue();
    }

    @Test
    void doesNotMistakeParticleRoFollowedByNumberForAddress() {
        Accepted result = accepted("친구로 3명이 같이 놀았어");

        assertThat(result.canonicalUtterance()).isEqualTo("친구로 3명이 같이 놀았어");
        assertThat(result.assessment().personalInformationMasked()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"나 죽고 싶어", "자 해 하고 싶어", "집에서 학대 당해", "죽어버리고 싶어"})
    void blocksSelfHarmAndAbuseEvenWhenSpacedOut(String input) {
        assertThat(processor.process(input)).isInstanceOf(Unsafe.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "​​", "010-1234-5678"})
    void asksAgainWhenNothingMeaningfulRemains(String input) {
        assertThat(processor.process(input)).isInstanceOf(Invalid.class);
    }

    @Test
    void asksAgainForNullInput() {
        assertThat(processor.process(null)).isInstanceOf(Invalid.class);
    }
}
