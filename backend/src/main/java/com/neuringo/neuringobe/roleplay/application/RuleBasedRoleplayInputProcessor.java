package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 규칙 기반 아이 입력 처리(첫 버전). 외부 LLM 에 보내기 전에 입력을 정리하고, 개인정보 형태를 가리고, 위험 입력을 막는다.
 *
 * <ul>
 *   <li>정리: 제어·서식 문자를 지우고 공백을 하나로 줄인다.
 *   <li>개인정보: 주민등록번호·이메일·전화번호·긴 숫자(계좌·카드)·주소(도로명·동호수) 형태를 자리표시로 바꾼다. 이름은 이 규칙으로 알 수 없어 가리지 않는다.
 *   <li>위험 입력: 자해·자살·학대처럼 대화를 멈추고 선생님께 알려야 하는 말이 있으면 {@link Unsafe} 다. 목록은 첫 버전이며 최종 목록은 안전
 *       정책(SAFE) 담당과 정한다. 시나리오에 흔한 말(넘어졌어, 피가 났어 등)은 막지 않도록 좁게 잡았다.
 * </ul>
 *
 * <p>판정값은 이 규칙이 실제로 확인한 만큼만 채운다. 규칙으로 바꾸는 것은 개인정보 자리표시뿐이라 뜻은 보존된 것으로 보고, 가린 것이 있으면 정제 신뢰도를 조금 낮춘다.
 * 가리고 나서 남는 말이 없으면 {@link Invalid} 다.
 */
public final class RuleBasedRoleplayInputProcessor implements RoleplayInputProcessor {

    /** 가린 것이 없을 때와 있을 때의 정제 신뢰도. 규칙 치환이라 높게 두되, 가린 경우 말의 일부가 빠졌음을 반영한다. */
    static final double UNCHANGED_CONFIDENCE = 1.0;

    static final double MASKED_CONFIDENCE = 0.9;

    /** 공백을 지우고 소문자로 바꾼 입력에서 찾는다. 띄어 쓰거나 붙여 써도 걸린다. */
    static final List<String> UNSAFE_TERMS =
            List.of(
                    "자살", "자해", "죽고싶", "죽을래", "죽어버리", "죽어버릴", "죽여버", "죽일거", "죽이고싶", "손목을긋", "목을매",
                    "뛰어내리고싶", "학대", "성폭", "성추행");

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern MEANINGFUL = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(주민등록번호|이메일|전화번호|번호|주소)\\]");

    private static final List<Mask> MASKS =
            List.of(
                    new Mask("(?<!\\d)\\d{6}\\s?-?\\s?[1-4]\\d{6}(?!\\d)", "[주민등록번호]"),
                    new Mask("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+", "[이메일]"),
                    new Mask("(?<!\\d)0\\d{1,2}[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}(?!\\d)", "[전화번호]"),
                    new Mask("(?<!\\d)\\d{7,}(?!\\d)", "[번호]"),
                    new Mask("(?<!\\d)\\d{1,4}\\s?동\\s?\\d{1,4}\\s?호", "[주소]"),
                    // 도로명 주소. "친구로 3명"처럼 조사 '로' 뒤 숫자가 걸리지 않게 숫자 뒤에 글자가 붙으면 주소로 보지 않는다.
                    new Mask(
                            "[가-힣0-9]{2,}(?:로|길)\\s?\\d{1,5}(?:-\\d{1,5})?(?:번길)?(?![\\d가-힣])",
                            "[주소]"));

    @Override
    public Result process(String input) {
        if (input == null) return new Invalid();
        // 탭·줄바꿈도 공백이므로 공백을 먼저 하나로 줄이고, 남은 제어·서식 문자(폭 없는 공백 등)를 지운다.
        String text =
                INVISIBLE.matcher(SPACES.matcher(input).replaceAll(" ")).replaceAll("").strip();
        if (text.isEmpty()) return new Invalid();

        String compact = SPACES.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
        if (UNSAFE_TERMS.stream().anyMatch(compact::contains)) return new Unsafe();

        String masked = text;
        for (Mask mask : MASKS)
            masked = mask.pattern().matcher(masked).replaceAll(mask.replacement());
        boolean maskedAny = !masked.equals(text);
        if (!MEANINGFUL.matcher(PLACEHOLDER.matcher(masked).replaceAll("")).find())
            return new Invalid();

        return new Accepted(
                masked,
                new Assessment(
                        maskedAny ? MASKED_CONFIDENCE : UNCHANGED_CONFIDENCE,
                        true,
                        true,
                        maskedAny));
    }

    private record Mask(Pattern pattern, String replacement) {
        Mask(String regex, String replacement) {
            this(Pattern.compile(regex), replacement);
        }
    }
}
