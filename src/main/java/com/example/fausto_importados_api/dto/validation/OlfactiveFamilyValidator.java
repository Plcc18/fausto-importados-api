package com.example.fausto_importados_api.dto.validation;

import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class OlfactiveFamilyValidator implements ConstraintValidator<ValidOlfactiveFamily, String> {

    private static final Set<String> VALID_NAMES = Arrays.stream(OlfactiveFamily.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        String[] families = value.split(",", -1);

        return Arrays.stream(families)
                .allMatch(family -> VALID_NAMES.contains(family.trim().toUpperCase()));
    }
}
