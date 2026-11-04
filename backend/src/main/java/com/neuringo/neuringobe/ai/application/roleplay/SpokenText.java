package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.regex.Pattern;

/**
 * 음성(TTS)으로 읽을 부분만 남긴다. 캐릭터 대사의 괄호 속 행동 묘사("(훌쩍이며)", "(무릎을 감싸 쥐며)")는 화면에만 보이는 지문이라 소리로 읽지 않는다. 화면에
 * 보여 주는 대사는 바꾸지 않는다.
 */
public final class SpokenText {

    /** 반각·전각 괄호 한 쌍. 괄호 안에 다른 괄호는 없다고 본다. */
    private static final Pattern STAGE_DIRECTION = Pattern.compile("[(（][^()（）]*[)）]");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 괄호 앞에 남는 공백(" ,", " .")을 정리할 때 쓴다. */
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([,.!?…])");

    private SpokenText() {}

    /** 괄호 속 지문을 뺀 문장. 읽을 말이 남지 않으면 빈 문자열이다. */
    public static String of(String text) {
        if (text == null) return "";
        String withoutDirections = STAGE_DIRECTION.matcher(text).replaceAll(" ");
        String collapsed = SPACES.matcher(withoutDirections).replaceAll(" ").strip();
        return SPACE_BEFORE_PUNCTUATION.matcher(collapsed).replaceAll("$1");
    }
}
