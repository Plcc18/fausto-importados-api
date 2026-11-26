package com.example.fausto_importados_api.concurrency;

import org.springframework.http.HttpStatus;

/** Erro com status e código estável para o front (ver {@code GlobalExceptionHandler}). */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiProblemException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
