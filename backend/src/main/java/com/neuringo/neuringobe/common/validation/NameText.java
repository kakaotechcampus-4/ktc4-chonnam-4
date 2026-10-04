package com.neuringo.neuringobe.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 학급명·아동 이름·강사 이름처럼 화면에 그대로 보이는 이름. 제어 문자를 거절하고, 보이는 글자가 1자 이상이어야 한다. {@code null} 은 통과하므로 필수 여부는
 * {@code @NotBlank} 로 따로 건다.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NameTextValidator.class)
public @interface NameText {

    String message() default "보이는 글자를 1자 이상 입력해 주세요.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
