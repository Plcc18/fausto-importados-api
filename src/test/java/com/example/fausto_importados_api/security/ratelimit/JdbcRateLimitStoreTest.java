package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.repository.RateLimitBucketRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ratelimitjdbc;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class JdbcRateLimitStoreTest extends RateLimitStoreContract {

    @Autowired EntityManager entityManager;
    @Autowired RateLimitBucketRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    private JdbcRateLimitStore store;

    @Override
    RateLimitStore store() {
        if (store == null) {
            store = new JdbcRateLimitStore(entityManager, repository, transactionManager);
        }
        return store;
    }

    @Test
    void purgesIdleBucketsOnly() {
        long old = 1_000L;
        long recent = 9_000_000_000_000L;
        store().consume("purge:old", 5, 60_000, old);
        store().consume("purge:recent", 5, 60_000, recent);

        int removed = store().purgeIdleBefore(recent - 1);

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(repository.findById("purge:old")).isEmpty();
        assertThat(repository.findById("purge:recent")).isPresent();
    }
}
