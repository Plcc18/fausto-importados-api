package com.example.fausto_importados_api.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Transação de escrita com repetição automática (limitada, com backoff) em deadlock, erro de serialização e lock
 * timeout.
 *
 * <p>Isolamento: READ COMMITTED. A consistência vem de locks explícitos ({@code SELECT ... FOR UPDATE}), de updates
 * atômicos e de constraints; nenhum fluxo daqui precisa de SERIALIZABLE.
 *
 * <p><b>Ordem de locks</b> (sempre a mesma, para não haver deadlock):
 * <ol>
 *   <li>chave de idempotência;</li>
 *   <li>pedido, depois produtos em ordem de id;</li>
 *   <li>administradores ativos em ordem de id, depois o usuário alvo, depois os tokens e códigos dele;</li>
 *   <li>campanha, depois o lock de publicação ({@code CampaignConflictGuard}).</li>
 * </ol>
 *
 * <p>Regras para o código passado a {@link #run}: pode ser executado mais de uma vez; não faz chamada externa lenta
 * (Cloudinary, e-mail); e efeitos fora do banco (auditoria) vão em {@link #afterCommit}.
 */
@Component
@EnableConfigurationProperties(ConcurrencyProperties.class)
public class Transactions {

    private static final Logger log = LoggerFactory.getLogger(Transactions.class);

    private final TransactionTemplate transaction;
    private final int maxAttempts;
    private final Duration backoff;
    private final ThreadLocal<List<Runnable>> pending = new ThreadLocal<>();

    public Transactions(PlatformTransactionManager transactionManager, ConcurrencyProperties properties) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.maxAttempts = Math.max(1, properties.maxAttempts());
        this.backoff = properties.retryBackoff();
    }

    /**
     * Executa numa transação (commit ou rollback de tudo). Se já houver uma aberta, participa dela: quem repete é
     * a de fora, porque depois de um erro do banco a transação inteira está perdida.
     */
    public <T> T run(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return work.get();
        }
        for (int attempt = 1; ; attempt++) {
            List<Runnable> actions = new ArrayList<>();
            pending.set(actions);
            T result;
            try {
                result = transaction.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts || !DbErrors.isTransient(e)) {
                    throw e;
                }
                log.warn("Transação repetida (tentativa {} de {}), SQLSTATE {}",
                        attempt + 1, maxAttempts, DbErrors.sqlState(e).orElse("?"));
                sleep(attempt);
                continue;
            } finally {
                pending.remove();
            }
            actions.forEach(Runnable::run);
            return result;
        }
    }

    public void execute(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Adia a ação para depois do commit da transação aberta por {@link #run}, já sem conexão nem locks presos. É
     * descartada se houver rollback. Fora de {@code run}, executa na hora.
     */
    public void afterCommit(Runnable action) {
        List<Runnable> actions = pending.get();
        if (actions == null) {
            action.run();
        } else {
            actions.add(action);
        }
    }

    private void sleep(int attempt) {
        long base = Math.max(1, backoff.toMillis()) << (attempt - 1);
        try {
            Thread.sleep(base + ThreadLocalRandom.current().nextLong(base + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a transaction", e);
        }
    }
}
