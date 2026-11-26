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
 * <p>Contar tentativas em paralelo sem perder nenhuma exige dois passos (ver {@link LoginThrottle}):
 * primeiro garantir que a linha existe ({@link #ensureRow}); depois travá-la, conferir o bloqueio e incrementar
 * ({@link #reserve}). Criar e incrementar de uma vez só deixaria duas requisições "criarem" a
 * mesma linha e uma sobrescreveria a contagem da outra.
 */
@Repository
public class ThrottleStore {

    /**
     * Resultado de reservar uma tentativa numa chave.
     *
     * @param blockedUntil fim do bloqueio que impediu a tentativa; nulo se ela foi contada
     * @param lockSet      bloqueio que ESTA tentativa criou ao atingir o limite; nulo se não criou
     * @param countedAt    quando a tentativa foi contada
     * @param previous     última atividade da chave antes dela
     */
    public record Slot(
            String key, int threshold, Instant blockedUntil, Instant lockSet, Instant countedAt, Instant previous
    ) {
    }

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
     * Com a linha travada: se a chave está bloqueada, recusa; senão conta a tentativa e, ao atingir o limite,
     * bloqueia as próximas. O bloqueio dobra a cada nova falha, até {@code max}. Conferir e contar no mesmo passo
     * impede que requisições simultâneas testem mais credenciais do que o limite.
     *
     * @return vazio se a linha não existe (foi apagada por um login bem-sucedido entre {@link #ensureRow} e aqui):
     *         o chamador recria e tenta de novo
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Slot> reserve(
            String key, int threshold, Duration baseLock, Duration max, Duration window, Instant now
    ) {
        Optional<AuthThrottle> found = repository.findForUpdate(key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AuthThrottle row = found.get();

        if (row.getLockedUntil() != null && row.getLockedUntil().isAfter(now)) {
            return Optional.of(new Slot(key, threshold, row.getLockedUntil(), null, now, row.getUpdatedAt()));
        }

        Instant previous = row.getUpdatedAt();

        // Falhas antigas são esquecidas: uma senha errada hoje não soma com uma de semana passada.
        if (Duration.between(row.getUpdatedAt(), now).compareTo(window) > 0) {
            row.setFailures(0);
            row.setLockedUntil(null);
        }

        int failures = row.getFailures() + 1;
        row.setFailures(failures);
        row.setUpdatedAt(now);

        Instant lockSet = null;
        if (failures >= threshold) {
            int doublings = Math.min(failures - threshold, 20);
            Duration lock = baseLock.multipliedBy(1L << doublings);
            if (lock.compareTo(max) > 0) {
                lock = max;
            }
            lockSet = now.plus(lock);
            row.setLockedUntil(lockSet);
        }
        repository.save(row);
        return Optional.of(new Slot(key, threshold, null, lockSet, now, previous));
    }

    /** Devolve uma tentativa que se revelou legítima (a credencial conferiu ou nem chegou a ser testada). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Slot slot) {
        repository.findForUpdate(slot.key()).ifPresent(row -> {
            row.setFailures(Math.max(0, row.getFailures() - 1));
            boolean lockedByThisAttempt = slot.lockSet() != null && slot.lockSet().equals(row.getLockedUntil());
            if (lockedByThisAttempt || row.getFailures() < slot.threshold()) {
                row.setLockedUntil(null);
            }
            // uma tentativa legítima não renova a janela das falhas antigas (importa num IP compartilhado)
            if (slot.countedAt().equals(row.getUpdatedAt())) {
                row.setUpdatedAt(slot.previous());
            }
            repository.save(row);
        });
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
