package com.example.fausto_importados_api.services.exception;

/** Pedido que não pode ser aceito ou processado (produto indisponível, quantidade inválida). */
public class InvalidOrderException extends BusinessException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
