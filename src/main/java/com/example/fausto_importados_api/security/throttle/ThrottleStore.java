package com.example.fausto_importados_api.security.throttle;

import com.example.fausto_importados_api.model.AuthThrottle;
import com.example.fausto_importados_api.repository.AuthThrottleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Acesso transacional aos contadores. Cada operação de escrita roda numa transação própria: a contagem
 * precisa ser confirmada mesmo que a requisição que falhou termine em erro.
 *
 * <p>Contar falhas em paralelo sem perder nenhuma exige dois passos (ver {@link LoginThrottle}):
 * primeiro garantir que a linha existe ({@link #ensureRow}); depois travá-la e incrementar
 * ({@link #registerFailure}). Criar e incrementar de uma vez só deixaria duas requisições "criarem" a
 * mesma linha e uma sobrescreveria a contagem da outra.
 */
@Repository
public class ThrottleStore {

    private final AuthThrottleRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    public ThrottleStore(AuthThrottleRepository repository) {
        this.repository = repository;
    }

    /** Fim do bloqueio da chave, se ainda estiver bloqueada. */
    @Transactional(readOnly = true)
    public Optional<Instant> lockedUntil(String key, Instant now) {
        return repository.findById(key)
                .map(AuthThrottle::getLockedUntil)
                .filter(until -> until != null && until.isAfter(now));
    }

    /**
     * Cria a linha (zerada) se ainda não existir. Sempre um INSERT de verdade ({@code persist}, nunca
     * {@code merge}). Se outra requisição a criou no mesmo instante, lança
     * {@link org.springframework.dao.DataIntegrityViolationException}: o chamador ignora, a linha já existe.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureRow(String key, Instant now) {
        if (repository.existsById(key)) {
            return;
        }
        entityManager.persist(new AuthThrottle(key, now));
        entityManager.flush();
    }

    /**
     * Soma uma falha à chave (com a linha travada) e, ao atingir o limite, a bloqueia. O bloqueio dobra a cada
     * nova falha, até {@code max}.
     *
     * @return {@code false} se a linha não existe (foi apagada por um login bem-sucedido entre
     *         {@link #ensureRow} e aqui): o chamador recria e tenta de novo
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerFailure(
            String key, int threshold, Duration baseLock, Duration max, Duration window, Instant now
    ) {
        Optional<AuthThrottle> found = repository.findForUpdate(key);
        if (found.isEmpty()) {
            return false;
        }
        AuthThrottle row = found.get();

        // Falhas antigas são esquecidas: uma senha errada hoje não soma com uma de semana passada.
        if (Duration.between(row.getUpdatedAt(), now).compareTo(window) > 0) {
            row.setFailures(0);
            row.setLockedUntil(null);
        }

        int failures = row.getFailures() + 1;
        row.setFailures(failures);
        row.setUpdatedAt(now);

        if (failures >= threshold) {
            int doublings = Math.min(failures - threshold, 20);
            Duration lock = baseLock.multipliedBy(1L << doublings);
            if (lock.compareTo(max) > 0) {
                lock = max;
            }
            row.setLockedUntil(now.plus(lock));
        }
        repository.save(row);
        return true;
    }

    @Transactional
    public void clear(String key) {
        repository.deleteById(key);
    }

    @Transactional
    public int purgeStale(Instant before, Instant now) {
        return repository.deleteStale(before, now);
    }
}
