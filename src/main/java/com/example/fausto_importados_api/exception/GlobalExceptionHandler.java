package com.example.fausto_importados_api.exception;

import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.concurrency.DbErrors;
import com.example.fausto_importados_api.concurrency.Versions;
import com.example.fausto_importados_api.services.exception.BusinessException;
import com.example.fausto_importados_api.services.exception.DuplicateProductException;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusiness(BusinessException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "timestamp", Instant.now().toString(),
                        "error", ex.getMessage()
                )
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                Map.of(
                        "timestamp", Instant.now().toString(),
                        "error", ex.getMessage()
                )
        );
    }

    @ExceptionHandler(DuplicateProductException.class)
    public ResponseEntity<?> handleDuplicateProduct(DuplicateProductException ex) {
        return problem(HttpStatus.CONFLICT, "duplicate_product", ex.getMessage());
    }

    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<?> handleProblem(ApiProblemException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    /** O registro mudou entre a leitura e a gravação (lock otimista). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> handleStale(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, Versions.CONFLICT_CODE, Versions.CONFLICT_MESSAGE);
    }

    /** Deadlock ou lock timeout que persistiu depois das novas tentativas. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<?> handleContention(ConcurrencyFailureException ex) {
        log.warn("Operação desistiu por contenção no banco: SQLSTATE {}", DbErrors.sqlState(ex).orElse("?"));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body("concurrent_operation", "Operação concorrente em andamento. Tente novamente."));
    }

    /** Uma constraint do banco recusou a escrita. A mensagem do banco vai só para o log. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleIntegrity(DataIntegrityViolationException ex) {
        String state = DbErrors.sqlState(ex).orElse("");
        log.warn("Escrita recusada pelo banco (SQLSTATE {}): {}", state, ex.getMostSpecificCause().getMessage());
        // 23505 = valor único repetido; 23503 = referência inexistente ou em uso
        return state.equals("23505") || state.equals("23503")
                ? problem(HttpStatus.CONFLICT, "conflict", "O registro conflita com um já existente.")
                : problem(HttpStatus.BAD_REQUEST, "invalid_data", "Dados inválidos.");
    }

    private static ResponseEntity<?> problem(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }

    // "error" e "message" repetem o texto: o front lê um ou outro
    private static Map<String, String> body(String code, String message) {
        return Map.of("timestamp", Instant.now().toString(), "code", code, "error", message, "message", message);
    }
}
