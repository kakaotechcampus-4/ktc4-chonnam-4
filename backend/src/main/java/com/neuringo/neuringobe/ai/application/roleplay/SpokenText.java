package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.SpeechEmotion;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 음성(TTS)으로 읽을 부분만 남긴다. 캐릭터 대사의 괄호 속 행동 묘사("(훌쩍이며)", "(무릎을 감싸 쥐며)")는 화면에만 보이는 지문이라 소리로 읽지 않는다. 화면에
 * 보여 주는 대사는 바꾸지 않는다.
 *
 * <p>지문은 읽지 않는 대신 목소리의 감정을 고르는 데 쓴다. 예) "(훌쩍이며)" → 슬픈 목소리.
 *
 * <p>괄호 밖의 울음소리 낱말("흐윽…", "훌쩍")도 TTS 는 소리가 아니라 글자로 읽는다. 그래서 이 낱말도 읽지 않고, 대신 슬픈 목소리로 읽는다.
 */
public final class SpokenText {

    /** 반각·전각 괄호 한 쌍. 괄호 안에 다른 괄호는 없다고 본다. */
    private static final Pattern STAGE_DIRECTION = Pattern.compile("[(（][^()（）]*[)）]");

    /**
     * 괄호 밖의 울음소리 낱말과 뒤따르는 문장부호. 앞뒤가 다른 한글 글자와 붙어 있으면(예: "훌쩍이며", "흑백") 낱말로 보지 않는다.
     *
     * <p>낱말 묶음은 possessive(++)로 둔다. "(흑+)+"처럼 반복 안에 반복이 있으면 "흑흑흑…" 뒤에 한글이 붙었을 때 나누는 방법을 모두 되짚을 수 있다
     * (CodeQL java/redos). 묶음 안 글자는 모두 한글이고 바로 뒤에 한글이 오면 어차피 실패하므로, 되짚지 않아도 결과는 같다. 문장부호는 되짚어야
     * "흑.가"처럼 끝의 마침표만 빼고 맞출 수 있어 그대로 둔다. 바깥 반복이 "흑" 한 글자씩 돌면 Java 정규식이 재귀가 깊어져 StackOverflowError 가
     * 나므로 안쪽은 "흑++"로 한 번에 먹는다.
     */
    private static final Pattern CRYING_SOUND =
            Pattern.compile("(?<![가-힣])(?:흐++으*+윽++|흑++|훌쩍|엉엉|으++아*+앙++)++[…~.,!]*(?![가-힣])");

    /** 지운 뒤 문장 앞에 남은 문장부호와, 문장 끝에 남은 쉼표. */
    private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^[\\s…~.,!]+");

    private static final Pattern TRAILING_COMMA = Pattern.compile("[\\s,]+$");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 괄호 앞에 남는 공백(" ,", " .")을 정리할 때 쓴다. */
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([,.!?…])");

    /** 지문 낱말과 감정. 위에서부터 먼저 맞는 것을 쓴다. 정해 둔 낱말만 알아듣고, 나머지는 보통 목소리다. */
    private static final List<Cue> CUES =
            List.of(
                    new Cue(SpeechEmotion.SAD, "훌쩍", "흐윽", "흑흑", "울", "눈물", "시무룩", "속상", "풀이 죽"),
                    new Cue(SpeechEmotion.HAPPY, "웃", "미소", "방긋", "신나", "기뻐", "활짝"),
                    new Cue(SpeechEmotion.ANGRY, "화가 나", "화난", "씩씩", "버럭", "발을 구르"),
                    new Cue(SpeechEmotion.WHISPER, "속삭", "소곤", "작은 목소리"));

    private record Cue(SpeechEmotion emotion, String... words) {
        boolean matches(String direction) {
            for (String word : words) {
                if (direction.contains(word)) return true;
            }
            return false;
        }
    }

    private SpokenText() {}

    /** 괄호 속 지문과 울음소리 낱말을 뺀 문장. 읽을 말이 남지 않으면 빈 문자열이다. */
    public static String of(String text) {
        if (text == null) return "";
        String withoutDirections = STAGE_DIRECTION.matcher(text).replaceAll(" ");
        String withoutSounds = CRYING_SOUND.matcher(withoutDirections).replaceAll(" ");
        String collapsed = SPACES.matcher(withoutSounds).replaceAll(" ").strip();
        String tidy = SPACE_BEFORE_PUNCTUATION.matcher(collapsed).replaceAll("$1");
        tidy = LEADING_PUNCTUATION.matcher(tidy).replaceAll("");
        return TRAILING_COMMA.matcher(tidy).replaceAll("");
    }

    /**
     * 지문으로 고른 목소리 감정. 지문이 여럿이면 앞에서부터 처음 맞는 것을 쓴다. 지문으로 정하지 못하면 괄호 밖 울음소리 낱말이 있을 때 SAD, 없으면 NORMAL
     * 이다.
     */
    public static SpeechEmotion emotionOf(String text) {
        if (text == null) return SpeechEmotion.NORMAL;
        Matcher matcher = STAGE_DIRECTION.matcher(text);
        while (matcher.find()) {
            String direction = matcher.group();
            for (Cue cue : CUES) {
                if (cue.matches(direction)) return cue.emotion();
            }
        }
        // 괄호 지문으로 정하지 못했어도 울음소리 낱말이 있으면 슬픈 목소리다.
        String withoutDirections = STAGE_DIRECTION.matcher(text).replaceAll(" ");
        if (CRYING_SOUND.matcher(withoutDirections).find()) return SpeechEmotion.SAD;
        return SpeechEmotion.NORMAL;
    }
}
