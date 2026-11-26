package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A repetição automática de transações, contra erros reais do PostgreSQL. */
class TransactionsTest extends ConcurrencyTestSupport {

    @Autowired Transactions transactions;

    @Test
    void aRealDeadlockIsRetried_andBothTransactionsFinish() {
        Product a = newProduct(10);
        Product b = newProduct(10);
        CyclicBarrier bothHoldTheirFirstLock = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();

        // ordem de locks invertida DE PROPÓSITO (o código da aplicação nunca faz isto)
        concurrently(2, i -> () -> {
            Product first = i == 0 ? a : b;
            Product second = i == 0 ? b : a;
            AtomicInteger own = new AtomicInteger();
            transactions.execute(() -> {
                attempts.incrementAndGet();
                decrease(first);
                if (own.incrementAndGet() == 1) {
                    await(bothHoldTheirFirstLock);
                }
                decrease(second);
                transactions.afterCommit(committed::incrementAndGet);
            });
            return null;
        });

        assertThat(attempts.get()).isGreaterThanOrEqualTo(3); // uma das duas precisou repetir
        assertThat(committed.get()).isEqualTo(2);             // a ação pós-commit não roda na tentativa desfeita
        assertThat(stockOf(a)).isEqualTo(8);
        assertThat(stockOf(b)).isEqualTo(8);
    }

    @Test
    void aPersistentSerializationErrorGivesUpAfterThreeAttempts_andNothingIsKept() {
        Product product = newProduct(10);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();

        assertThatThrownBy(() -> transactions.execute(() -> {
            attempts.incrementAndGet();
            decrease(product);
            transactions.afterCommit(committed::incrementAndGet);
            jdbc.execute("do $$ begin raise exception using errcode = '40001'; end $$");
        })).isInstanceOf(ConcurrencyFailureException.class);

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(committed.get()).isZero();
        assertThat(stockOf(product)).isEqualTo(10);
    }

    @Test
    void aVersionConflictIsNeverRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> transactions.execute(() -> {
            attempts.incrementAndGet();
            throw new OptimisticLockingFailureException("stale");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void theConnectionsHaveALockTimeout() {
        assertThat(List.of(jdbc.queryForObject("show lock_timeout", String.class))).containsExactly("5s");
    }

    private void decrease(Product product) {
        jdbc.update("update products set stock_quantity = stock_quantity - 1 where id = ?", product.getId());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
