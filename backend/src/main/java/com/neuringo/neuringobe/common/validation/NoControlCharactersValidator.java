package com.neuringo.neuringobe.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NoControlCharactersValidator
        implements ConstraintValidator<NoControlCharacters, String> {

    static final String MESSAGE = "사용할 수 없는 문자가 포함되어 있습니다.";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || !TextRules.containsUnusableCharacter(value);
    }
}
