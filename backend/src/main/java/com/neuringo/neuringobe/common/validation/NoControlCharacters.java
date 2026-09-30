package com.neuringo.neuringobe.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 제어 문자(U+0000 등)를 거절한다. 이메일·소속 기관처럼 비어 보이는 것까지 막을 필요는 없는 값에 쓴다. {@code null} 은 통과한다. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoControlCharactersValidator.class)
public @interface NoControlCharacters {

    String message() default NoControlCharactersValidator.MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
