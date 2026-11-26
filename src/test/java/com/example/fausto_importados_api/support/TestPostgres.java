package com.example.fausto_importados_api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL de verdade para os testes que dependem dele (migrations, constraints e concorrência: o H2 não
 * reproduz os locks do Postgres).
 *
 * <ul>
 *   <li>por padrão, um container (Testcontainers), compartilhado por todas as classes e encerrado com a JVM —
 *       os testes são pulados se não houver Docker;</li>
 *   <li>ou um Postgres já existente e DESCARTÁVEL, informado por
 *       {@code -Dtest.postgres.url=jdbc:postgresql://localhost:5432/banco_de_teste}
 *       (+ {@code -Dtest.postgres.user} e {@code -Dtest.postgres.password}). Os testes gravam nele.</li>
 * </ul>
 */
public final class TestPostgres {

    private static final String EXTERNAL_URL = System.getProperty("test.postgres.url");
    private static PostgreSQLContainer<?> container;

    private TestPostgres() {
    }

    /** Chamar num {@code @BeforeAll}: sobe o container (uma vez) ou pula a classe. */
    public static synchronized void startOrSkip() {
        if (EXTERNAL_URL != null || container != null) {
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sem Docker e sem -Dtest.postgres.url: teste com PostgreSQL pulado");
        container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
    }

    /** Chamar num {@code @DynamicPropertySource}. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_URL != null ? EXTERNAL_URL : container.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_URL != null
                ? System.getProperty("test.postgres.user", "postgres") : container.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_URL != null
                ? System.getProperty("test.postgres.password", "") : container.getPassword());
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '5s'");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
