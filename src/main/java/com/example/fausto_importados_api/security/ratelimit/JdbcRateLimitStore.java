package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.model.RateLimitBucket;
import com.example.fausto_importados_api.repository.RateLimitBucketRepository;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Baldes no Postgres: serve quando não há Redis. Funciona com várias instâncias (o estado está no banco), ao custo
 * de algumas consultas por requisição.
 *
 * <p>Mesma técnica do {@code ThrottleStore}: primeiro garante que a linha existe, depois a trava com
 * {@code SELECT ... FOR UPDATE} e gasta a ficha. A criação é um INSERT de verdade ({@code persist}, NUNCA
 * {@code save}/{@code merge}): com o merge, duas requisições "criariam" a mesma linha e uma sobrescreveria o saldo
 * da outra, devolvendo fichas que já tinham sido gastas.
 */
public class JdbcRateLimitStore implements RateLimitStore {

    private final EntityManager entityManager;
    private final RateLimitBucketRepository repository;
    private final TransactionTemplate transaction;

    public JdbcRateLimitStore(
            EntityManager entityManager,
            RateLimitBucketRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this.entityManager = entityManager;
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        // Cada operação em transação própria: não depende (nem interfere) numa transação da requisição
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Outcome consume(String key, int capacity, long windowMs, long nowMs) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Outcome outcome = transaction.execute(status -> repository.findForUpdate(key).map(row -> {
                Outcome step = TokenBucket.step(row.getTokens(), row.getUpdatedMs(), nowMs, capacity, windowMs);
                row.setTokens(step.tokens());
                row.setUpdatedMs(nowMs);
                return step; // a linha é gerenciada: o commit grava a alteração
            }).orElse(null));
            if (outcome != null) {
                return outcome;
            }
            try {
                transaction.executeWithoutResult(status -> {
                    entityManager.persist(new RateLimitBucket(key, capacity, nowMs));
                    entityManager.flush();
                });
            } catch (RuntimeException createdByAnotherRequest) {
                // sem problema: perdeu a corrida, a linha existe (é o que queríamos). Se foi outro erro, a
                // próxima volta falha ao travar e, depois de 3 voltas, o erro sobe.
            }
        }
        throw new IllegalStateException("Could not update rate limit bucket " + key);
    }

    @Override
    public int purgeIdleBefore(long idleBeforeMs) {
        Integer removed = transaction.execute(status -> repository.deleteIdleBefore(idleBeforeMs));
        return removed == null ? 0 : removed;
    }

    @Override
    public String name() {
        return "jdbc";
    }
}
