package com.example.fausto_importados_api.services.exception;

public class InvalidOrderStatusException extends BusinessException {

    public InvalidOrderStatusException(String message) {
        super(message);
    }
}
