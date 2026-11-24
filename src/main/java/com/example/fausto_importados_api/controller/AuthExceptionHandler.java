package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.auth.ErrorResponseDTO;
import com.example.fausto_importados_api.security.mfa.MfaException;
import com.example.fausto_importados_api.services.exception.AccountConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class AuthExceptionHandler {

    // Qualquer falha de autenticação no login (senha errada, usuário inexistente, conta desativada...)
    // recebe a MESMA resposta, para não revelar se foi o e-mail ou a senha que errou.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponseDTO> handlerBadCredentials(
            AuthenticationException ex
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponseDTO(
                        Instant.now().toString(),
                        "Invalid email or password"
                ));
    }

    // Falhas previsíveis do 2FA. Mensagens fixas: nada que ajude a adivinhar códigos.
    @ExceptionHandler(MfaException.class)
    public ResponseEntity<ErrorResponseDTO> handleMfa(MfaException ex) {
        HttpStatus status = switch (ex.reason()) {
            case INVALID_CODE -> HttpStatus.UNAUTHORIZED;
            case ALREADY_ENROLLED, SETUP_NOT_STARTED -> HttpStatus.CONFLICT;
            case WEAK_PASSWORD -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponseDTO(Instant.now().toString(), ex.getMessage()));
    }

    // Conflito de estado na gestão de contas (e-mail em uso, último admin, ação sobre si mesmo)
    @ExceptionHandler(AccountConflictException.class)
    public ResponseEntity<ErrorResponseDTO> handleConflict(AccountConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponseDTO(Instant.now().toString(), ex.getMessage()));
    }
}
