package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.auth.ErrorResponseDTO;
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
}
