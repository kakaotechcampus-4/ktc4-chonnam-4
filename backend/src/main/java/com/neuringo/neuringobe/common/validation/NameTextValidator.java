package com.neuringo.neuringobe.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NameTextValidator implements ConstraintValidator<NameText, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // 제어 문자가 섞였으면 "보이는 글자" 안내보다 이 안내가 정확하다.
        if (TextRules.containsControlCharacter(value)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(NoControlCharactersValidator.MESSAGE)
                    .addConstraintViolation();
            return false;
        }
        return TextRules.hasVisibleCharacter(value);
    }
}
