package com.neuringo.neuringobe.ai.application.roleplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.SpeechEmotion;
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
                "흐윽… 무릎이 아파.|무릎이 아파.",
                "(훌쩍이며) 흐윽… 나 넘어졌어. 훌쩍.|나 넘어졌어.",
                "응, 흐윽, 고마워.|응, 고마워.",
                "흐으윽… 엉엉!|''",
                "아야… 흑흑|아야…",
                "(훌쩍이며) 흐윽…|''",
                "나 훌쩍이고 있었어.|나 훌쩍이고 있었어.",
                "흑백 사진 같아.|흑백 사진 같아.",
            })
    void removesStageDirectionsInParentheses(String text, String spoken) {
        assertThat(SpokenText.of(text)).isEqualTo(spoken);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "(훌쩍이며) 나 넘어졌어…|SAD",
                "(울먹이며) 무릎이 아파|SAD",
                "(눈물을 닦으며) 고마워|SAD",
                "(방긋 웃으며) 고마워!|HAPPY",
                "(씩씩거리며) 싫어|ANGRY",
                "(작은 목소리로) 비밀이야|WHISPER",
                "(무릎을 감싸 쥐며) 아야…|NORMAL",
                "(무릎을 감싸 쥐며) 아야… (훌쩍)|SAD",
                "친구는 어떤 기분일까?|NORMAL",
                "흐윽… 무릎이 아파.|SAD",
                "(웃으며) 흐윽, 아니야 괜찮아.|HAPPY",
                "흑백 사진 같아.|NORMAL",
            })
    void picksVoiceEmotionFromStageDirections(String text, SpeechEmotion emotion) {
        assertThat(SpokenText.emotionOf(text)).isEqualTo(emotion);
    }
}
