package com.example.fausto_importados_api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Aceita uma ou mais famílias olfativas separadas por vírgula (ex: "AMADEIRADO,ORIENTAL"),
// desde que cada uma corresponda a um valor de OlfactiveFamily. Valor nulo é considerado válido
// (deixa @NotNull/ausência de constraint tratar obrigatoriedade separadamente).
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OlfactiveFamilyValidator.class)
public @interface ValidOlfactiveFamily {
    String message() default "Invalid olfactive family";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
