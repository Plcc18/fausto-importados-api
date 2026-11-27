package com.example.fausto_importados_api.exception;

import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.dto.validation.InvalidField;
import org.springframework.http.HttpStatus;

import java.util.List;

/** Erro que aponta os campos recusados (a resposta leva a lista {@code fields}). */
public class FieldValidationException extends ApiProblemException {

    public static final String CODE = "validation_failed";
    public static final String MESSAGE = "Há campos inválidos.";

    private final transient List<InvalidField> fields;

    /** 400 {@value #CODE}. */
    public FieldValidationException(List<InvalidField> fields) {
        this(HttpStatus.BAD_REQUEST, CODE, MESSAGE, fields);
    }

    public FieldValidationException(HttpStatus status, String code, String message, List<InvalidField> fields) {
        super(status, code, message);
        this.fields = List.copyOf(fields);
    }

    public List<InvalidField> fields() {
        return fields;
    }
}
