package com.neuringo.neuringobe.common.validation;

/**
 * 사람이 입력하는 이름·이메일 문자열의 문자 규칙.
 *
 * <p>PostgreSQL 텍스트는 U+0000 을 저장·비교하지 못해 DB 까지 가면 500 이 된다. 그래서 제어 문자(Unicode 범주 Cc)는 요청 단계에서 거절한다.
 * 또 NBSP·BOM·폭 없는 공백처럼 화면에 보이지 않는 문자만으로 된 이름은 {@code @NotBlank} 를 통과하므로 따로 막는다. 값을 고쳐서 저장하지는
 * 않는다(거절만 한다).
 */
public final class TextRules {

    private TextRules() {}

    public static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(cp -> Character.getType(cp) == Character.CONTROL);
    }

    /** 공백류(Zs 포함)·서식 문자(Cf: BOM, 폭 없는 공백 등)·제어 문자가 아닌 글자가 하나라도 있는지. */
    public static boolean hasVisibleCharacter(String value) {
        return value.codePoints().anyMatch(TextRules::isVisible);
    }

    private static boolean isVisible(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)
                && type != Character.FORMAT
                && type != Character.CONTROL;
    }
}
