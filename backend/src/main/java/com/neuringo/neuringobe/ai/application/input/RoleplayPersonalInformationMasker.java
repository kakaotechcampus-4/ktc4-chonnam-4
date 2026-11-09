package com.neuringo.neuringobe.ai.application.input;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** 외부 호출 전에 명확한 개인정보를 가리고, 범위를 확정할 수 없으면 재입력시킨다. */
public final class RoleplayPersonalInformationMasker {
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE =
            Pattern.compile(
                    "(?<![0-9])(?:\\+82[ -]?)?0?1[016789][ -]?[0-9]{3,4}[ -]?[0-9]{4}(?![0-9])");
    private static final Pattern IDENTIFIER =
            Pattern.compile("(?<![0-9])[0-9]{6}[ -]?[1-8][0-9]{6}(?![0-9])");
    private static final Pattern NAMED =
            Pattern.compile(
                    "((?:내|제)\\s*(?:이름|실명)(?:은|는|이)?\\s*)[가-힣]{2,4}(?=이야|이에요|예요|야|입니다|라고|[\\s.,!?]|$)");
    private static final Pattern PRIVACY_CLUE =
            Pattern.compile(
                    "(?:내|제|우리)\\s*(?:주소|전화번호|휴대폰|연락처|주민번호|이메일)|(?:내|제|우리)\\s*학교(?:는|이름은|이름이)|[가-힣]+(?:로|길)\\s*[0-9]+|[0-9]+동\\s*[0-9]+호");

    public Masked mask(String input, String learnerName) {
        String text = Normalizer.normalize(input, Normalizer.Form.NFC).strip();
        if (text.isBlank()
                || text.codePoints()
                        .anyMatch(
                                c ->
                                        (Character.isISOControl(c) && !Character.isWhitespace(c))
                                                || Character.getType(c) == Character.FORMAT
                                                || Character.getType(c) == Character.SURROGATE))
            return new Masked("", false, true);
        text = text.replaceAll("\\s+", " ");
        String normalized = text;
        text = EMAIL.matcher(text).replaceAll("[이메일]");
        text = PHONE.matcher(text).replaceAll("[연락처]");
        text = IDENTIFIER.matcher(text).replaceAll("[식별번호]");
        text = NAMED.matcher(text).replaceAll("$1[이름]");
        if (learnerName != null && !learnerName.isBlank()) {
            String name = Normalizer.normalize(learnerName, Normalizer.Form.NFC).strip();
            text =
                    Pattern.compile(
                                    "(?<![가-힣A-Za-z0-9])"
                                            + Pattern.quote(name)
                                            + "(?=$|[\\s.,!?]|(?:은|는|이|가|을|를|야|아|에게|한테|랑|와|과|의)(?:$|\\s))")
                            .matcher(text)
                            .replaceAll("[이름]");
        }
        // 학교·주소·구어체 연락처는 문장 전체를 지워 의미를 손상시키지 않고 확인을 요청한다.
        String privacyCheck =
                text.replaceAll("(?:내|제|우리)\\s*(?:전화번호|휴대폰|연락처)(?:는|은|이|가)?\\s*\\[연락처\\]", "")
                        .replaceAll("(?:내|제|우리)\\s*이메일(?:은|는|이|가)?\\s*\\[이메일\\]", "")
                        .replaceAll("(?:내|제|우리)\\s*주민번호(?:는|은|이|가)?\\s*\\[식별번호\\]", "");
        boolean uncertain = PRIVACY_CLUE.matcher(privacyCheck).find();
        return new Masked(text, !text.equals(normalized), uncertain);
    }

    public record Masked(String text, boolean changed, boolean uncertain) {
        @Override
        public String toString() {
            return "Masked[redacted]";
        }
    }
}
