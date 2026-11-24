package com.example.fausto_importados_api.services.exception;

/** Operação de conta recusada por conflito de estado (e-mail em uso, último admin, ação sobre si mesmo). → 409 */
public class AccountConflictException extends RuntimeException {

    public AccountConflictException(String message) {
        super(message);
    }
}
