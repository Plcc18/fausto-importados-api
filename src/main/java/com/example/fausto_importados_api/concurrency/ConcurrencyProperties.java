package com.example.fausto_importados_api.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Ajustes de concorrência ({@code app.concurrency.*}).
 *
 * @param maxAttempts           tentativas de uma transação que falhou por deadlock, serialização ou lock timeout
 * @param retryBackoff          espera antes da 2ª tentativa (dobra a cada nova, com variação aleatória)
 * @param requireIdempotencyKey recusa (400) as rotas POST sensíveis sem {@code Idempotency-Key}. Ligar só depois
 *                              que o front enviar o cabeçalho
 * @param requireVersion        recusa (428) edições do painel sem {@code version}. Ligar só depois que o front
 *                              enviar o campo
 * @param idempotencyRetention  por quanto tempo uma chave continua devolvendo a resposta original
 */
@ConfigurationProperties("app.concurrency")
public record ConcurrencyProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("50ms") Duration retryBackoff,
        @DefaultValue("false") boolean requireIdempotencyKey,
        @DefaultValue("false") boolean requireVersion,
        @DefaultValue("P1D") Duration idempotencyRetention
) {
}
