package com.neuringo.neuringobe.common.validation;

/**
 * 사람이 입력하는 이름·이메일 문자열의 문자 규칙.
 *
 * <p>PostgreSQL 텍스트는 U+0000 을 저장·비교하지 못해 DB 까지 가면 500 이 된다. 그래서 제어 문자(Unicode 범주 Cc)는 요청 단계에서 거절한다.
 * 짝 없는 서로게이트(U+D800~U+DFFF 가 홀로 있는 것)도 거절한다. UTF-8 로 바꿀 수 없어 DB 에는 "?" 로 바뀐 다른 값이 남는다. 또 NBSP·BOM·폭
 * 없는 공백처럼 화면에 보이지 않는 문자만으로 된 이름은 {@code @NotBlank} 를 통과하므로 따로 막는다. 값을 고쳐서 저장하지는 않는다(거절만 한다).
 */
public final class TextRules {

    private TextRules() {}

    /** 제어 문자나 짝 없는 서로게이트가 있는지. 짝이 맞는 서로게이트(이모지 등)는 codePoints() 에서 한 글자로 합쳐지므로 걸리지 않는다. */
    public static boolean containsUnusableCharacter(String value) {
        return value.codePoints()
                .anyMatch(
                        cp -> {
                            int type = Character.getType(cp);
                            return type == Character.CONTROL || type == Character.SURROGATE;
                        });
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
