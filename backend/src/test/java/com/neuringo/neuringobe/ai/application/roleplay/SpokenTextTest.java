package com.neuringo.neuringobe.ai.application.roleplay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpokenTextTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "(훌쩍이며) 나 넘어졌어…|나 넘어졌어…",
                "(무릎을 감싸 쥐며) 아야… (훌쩍) 너무 아파.|아야… 너무 아파.",
                "응… 고마워 (작게 웃으며)|응… 고마워",
                "（전각 괄호） 괜찮아?|괜찮아?",
                "아파 (훌쩍), 정말 아파.|아파, 정말 아파.",
                "친구는 어떤 기분일까?|친구는 어떤 기분일까?",
                "(고개를 끄덕이며)|''",
                "(훌쩍이며)   (흐윽)|''",
            })
    void removesStageDirectionsInParentheses(String text, String spoken) {
        assertThat(SpokenText.of(text)).isEqualTo(spoken);
    }
}
