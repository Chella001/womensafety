package com.womensafety.validation;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PersonNameValidator implements ConstraintValidator<PersonName, String> {

    private static final Pattern ALLOWED = Pattern.compile("^\\p{L}[\\p{L}\\p{M} .'’-]*$");

    private int min;
    private int max;

    @Override
    public void initialize(PersonName annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String name = PersonNames.normalise(value);
        int length = name.codePointCount(0, name.length());
        return length >= min && length <= max && ALLOWED.matcher(name).matches();
    }
}
