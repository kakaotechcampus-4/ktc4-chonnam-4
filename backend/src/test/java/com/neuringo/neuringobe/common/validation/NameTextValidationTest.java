package com.neuringo.neuringobe.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.child.dto.CreateChildRequest;
import com.neuringo.neuringobe.classroom.dto.CreateClassroomRequest;
import com.neuringo.neuringobe.user.dto.SignupRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// PR #20 제보: U+0000 이 든 이름은 500, 보이지 않는 문자만으로 된 이름은 저장됐다. DB 에 닿기 전 요청 검증(422)에서 막는다.
// 보이지 않는 문자를 소스에 그대로 쓰면 읽을 수 없고 포매터가 이스케이프를 실제 문자로 바꾸므로, 코드 포인트로 만든다.
class NameTextValidationTest {

    private static final String NUL = ch(0x0000);
    private static final String BEL = ch(0x0007);
    private static final String LINE_FEED = ch(0x000A);
    private static final String NBSP = ch(0x00A0);
    private static final String NARROW_NBSP = ch(0x202F);
    private static final String BOM = ch(0xFEFF);
    private static final String ZERO_WIDTH_SPACE = ch(0x200B);
    private static final String ZERO_WIDTH_JOINER = ch(0x200D);
    private static final String IDEOGRAPHIC_SPACE = ch(0x3000);

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    static Stream<String> namesWithControlCharacters() {
        return Stream.of("햇살" + NUL + "반", "햇살" + BEL + "반", "줄" + LINE_FEED + "바꿈");
    }

    @ParameterizedTest
    @MethodSource("namesWithControlCharacters")
    void rejectsControlCharacters(String name) {
        assertThat(messages(validator.validate(new CreateClassroomRequest(name))))
                .containsExactly("사용할 수 없는 문자가 포함되어 있습니다.");
    }

    // 전각 공백을 뺀 나머지는 @NotBlank 를 통과하던 값이다.
    static Stream<String> namesWithoutVisibleCharacters() {
        return Stream.of(
                NBSP + NBSP,
                NARROW_NBSP,
                BOM,
                ZERO_WIDTH_SPACE,
                ZERO_WIDTH_JOINER,
                IDEOGRAPHIC_SPACE);
    }

    @ParameterizedTest
    @MethodSource("namesWithoutVisibleCharacters")
    void rejectsNamesWithoutVisibleCharacters(String name) {
        assertThat(messages(validator.validate(new CreateChildRequest(name))))
                .contains("보이는 글자를 1자 이상 입력해 주세요.");
    }

    // 내부 공백, 숫자·영문, 보이는 글자 앞에 BOM 이 붙은 이름도 통과한다(값을 고쳐 저장하지는 않는다).
    static Stream<String> acceptedNames() {
        return Stream.of("햇살반", "2025 겨울반", "Class A", BOM + "민준");
    }

    @ParameterizedTest
    @MethodSource("acceptedNames")
    void acceptsNamesWithVisibleCharacters(String name) {
        assertThat(validator.validate(new CreateClassroomRequest(name))).isEmpty();
        assertThat(validator.validate(new CreateChildRequest(name))).isEmpty();
    }

    @Test
    void keepsExistingLengthRule() {
        assertThat(validator.validate(new CreateClassroomRequest("가".repeat(101)))).hasSize(1);
    }

    @Test
    void appliesToSignupNameEmailAndOrgName() {
        SignupRequest request =
                new SignupRequest(
                        "a" + NUL + "@example.com", "password1", ZERO_WIDTH_SPACE, "센터" + NUL);

        Set<String> fields =
                validator.validate(request).stream()
                        .map(violation -> violation.getPropertyPath().toString())
                        .collect(Collectors.toSet());

        assertThat(fields).contains("email", "name", "orgName");
    }

    @Test
    void allowsMissingOrgName() {
        assertThat(validator.validate(new SignupRequest("a@example.com", "password1", "홍길동", null)))
                .isEmpty();
    }

    private static String ch(int codePoint) {
        return Character.toString(codePoint);
    }

    private static Set<String> messages(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
