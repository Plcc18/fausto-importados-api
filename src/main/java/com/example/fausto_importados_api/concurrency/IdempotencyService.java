package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.IdempotencyRecord;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key}: a mesma chave repetida devolve a resposta original e NÃO executa de novo.
 *
 * <p>A chave é gravada na MESMA transação da operação. Assim:
 * <ul>
 *   <li>uma repetição simultânea fica parada no INSERT (chave primária) até a primeira confirmar, e então recebe a
 *       resposta dela;</li>
 *   <li>se a operação falha, o rollback libera a chave: tentar de novo executa de novo;</li>
 *   <li>não existe estado "pela metade" se o servidor cair.</li>
 * </ul>
 *
 * <p>A chave vale por operação (rota e recurso) e por autor. A mesma chave com outro corpo é recusada (422).
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{16,100}");

    private static final class KeyAlreadyUsed extends RuntimeException {
        private KeyAlreadyUsed() {
            super(null, null, false, false);
        }
    }

    private final IdempotencyRepository repository;
    private final Transactions transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean required;
    private final Duration retention;

    public IdempotencyService(
            IdempotencyRepository repository,
            Transactions transactions,
            ObjectMapper json,
            Clock clock,
            ConcurrencyProperties properties
    ) {
        this.repository = repository;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.required = properties.requireIdempotencyKey();
        this.retention = properties.idempotencyRetention();
    }

    public <T> ResponseEntity<?> execute(
            String key, String operation, Object request, Supplier<ResponseEntity<T>> action
    ) {
        return execute(key, operation, request, action, UnaryOperator.identity());
    }

    /**
     * @param key       valor do cabeçalho; nulo executa sem proteção (a menos que a chave seja obrigatória)
     * @param operation identifica a rota e o recurso, p.ex. {@code orders.complete:<id>}
     * @param request   corpo recebido (nulo se não houver); outra chamada com a mesma chave precisa trazer o mesmo
     * @param action    a operação; roda dentro da transação e pode ser repetida em caso de deadlock
     * @param storable  o que do corpo pode ser guardado (remova segredos que só podem aparecer uma vez)
     */
    public <T> ResponseEntity<?> execute(
            String key, String operation, Object request, Supplier<ResponseEntity<T>> action, UnaryOperator<T> storable
    ) {
        if (key == null || key.isBlank()) {
            if (required) {
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, "idempotency_key_required",
                        "O cabeçalho " + HEADER + " é obrigatório nesta operação.");
            }
            return transactions.run(action);
        }
        if (!KEY.matcher(key).matches()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "idempotency_key_invalid",
                    HEADER + " deve ter de 16 a 100 caracteres (letras, números, . _ : -). Use um UUID.");
        }

        String scope = operation + "|" + actor();
        String hash = sha256(serialize(request));

        // 2 voltas: se a chave for apagada pela retenção entre o conflito e a leitura, executa normalmente
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return transactions.<ResponseEntity<?>>run(() -> {
                    claim(scope, key, hash);
                    ResponseEntity<T> response = action.get();
                    T body = response.getBody() == null ? null : storable.apply(response.getBody());
                    repository.complete(scope, key, response.getStatusCode().value(),
                            body == null ? null : serialize(body));
                    return response;
                });
            } catch (KeyAlreadyUsed used) {
                Optional<IdempotencyRecord> stored = transactions.run(
                        () -> repository.findById(new IdempotencyRecord.Key(scope, key)));
                if (stored.isPresent()) {
                    return replay(stored.get(), hash);
                }
            }
        }
        throw new ApiProblemException(HttpStatus.CONFLICT, "concurrent_operation",
                "Operação em andamento. Tente novamente.");
    }

    public int purgeExpired() {
        return transactions.run(() -> repository.deleteOlderThan(clock.instant().minus(retention)));
    }

    private void claim(String scope, String key, String hash) {
        try {
            repository.claim(scope, key, hash, clock.instant());
        } catch (DataIntegrityViolationException duplicate) {
            throw new KeyAlreadyUsed();
        }
    }

    private ResponseEntity<?> replay(IdempotencyRecord stored, String hash) {
        if (!stored.getRequestHash().equals(hash)) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                    "Esta " + HEADER + " já foi usada com outro conteúdo.");
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stored.getResponseStatus())
                .header(REPLAY_HEADER, "true");
        if (stored.getResponseBody() == null) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_JSON)
                .body(stored.getResponseBody().getBytes(StandardCharsets.UTF_8));
    }

    private String serialize(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize an idempotent payload", e);
        }
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof User user
                ? user.getId().toString() : "anon";
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
