package com.example.fausto_importados_api;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.audit.Outcome;
import com.example.fausto_importados_api.model.AuditLog;
import com.example.fausto_importados_api.model.MfaRecoveryCode;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.AuditLogRepository;
import com.example.fausto_importados_api.repository.MfaRecoveryCodeRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.throttle.LoginThrottle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Aplica TODAS as migrations do Flyway num PostgreSQL de verdade e deixa o Hibernate validar as entidades
 * contra o schema ({@code ddl-auto: validate}, como em produção). Os demais testes usam H2 com o schema
 * gerado pelo Hibernate e nunca exercitam os scripts SQL.
 *
 * <p>Também confere, no banco real, as garantias de concorrência do 2FA (código de uso único, contador de
 * falhas).
 *
 * <p>De onde vem o banco:
 * <ul>
 *   <li>por padrão, um container (Testcontainers) — pulado se não houver Docker;</li>
 *   <li>ou um Postgres VAZIO já existente, informado por
 *       {@code -Dtest.postgres.url=jdbc:postgresql://localhost:5432/banco_vazio}
 *       (+ {@code -Dtest.postgres.user} e {@code -Dtest.postgres.password}). O Flyway cria as tabelas nele.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class PostgresMigrationTest {

    private static final String EXTERNAL_URL = System.getProperty("test.postgres.url");
    private static PostgreSQLContainer<?> container;

    @BeforeAll
    static void startDatabase() {
        if (EXTERNAL_URL != null) {
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sem Docker e sem -Dtest.postgres.url: teste de migrations pulado");
        container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_URL != null ? EXTERNAL_URL : container.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_URL != null
                ? System.getProperty("test.postgres.user", "postgres") : container.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_URL != null
                ? System.getProperty("test.postgres.password", "") : container.getPassword());
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired MfaRecoveryCodeRepository recoveryCodes;
    @Autowired LoginThrottle throttle;
    @Autowired AuditService audit;
    @Autowired AuditLogRepository auditRepo;
    @Autowired TransactionTemplate tx; // as queries @Modifying exigem transação (em produção vêm do @Transactional dos serviços)

    @Test
    void everyMigrationAppliesAndHibernateValidatesTheSchema() {
        // se o contexto subiu, o Flyway aplicou os scripts e o `validate` aceitou o resultado
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void theBootstrapAdminWasCreated_andHasNoMfaYet() {
        User admin = users.findByEmail("admin@test.com").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isTotpEnabled()).isFalse();
        assertThat(admin.getTotpSecretEnc()).isNull();
        assertThat(admin.isMustChangePassword()).isFalse();
    }

    @Test
    void theNewUserColumnsRoundTrip() {
        User u = newUser();
        u.setTotpSecretEnc("v1:aaaa:bbbb");
        u.setTotpEnabled(true);
        u.setTotpLastStep(1234567L);
        u.setMustChangePassword(true);
        u.setEnrollmentExpiresAt(Instant.parse("2026-03-02T00:00:00Z"));
        users.saveAndFlush(u);

        User read = users.findById(u.getId()).orElseThrow();

        assertThat(read.getTotpSecretEnc()).isEqualTo("v1:aaaa:bbbb");
        assertThat(read.isTotpEnabled()).isTrue();
        assertThat(read.getTotpLastStep()).isEqualTo(1234567L);
        assertThat(read.isMustChangePassword()).isTrue();
        assertThat(read.getEnrollmentExpiresAt()).isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
    }

    @Test
    void auditRowsAreWrittenAndReadBackWithRedactedDetails() {
        String marker = "pg-audit-" + UUID.randomUUID();

        audit.recordSuccess(AuditAction.PRODUCT_UPDATED, "PRODUCT", marker,
                java.util.Map.of("changes", java.util.Map.of("price", java.util.Map.of("from", "10", "to", "9")),
                        "password", "nao-deve-aparecer"));

        AuditLog row = auditRepo.findByEntityTypeOrderByOccurredAtDesc("PRODUCT",
                        org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .filter(r -> marker.equals(r.getEntityId())).findFirst().orElseThrow();
        assertThat(row.getAction()).isEqualTo("PRODUCT_UPDATED");
        assertThat(row.getOutcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(row.getOccurredAt()).isNotNull();
        assertThat(row.getDetails()).contains("\"price\"").contains("[redacted]").doesNotContain("nao-deve-aparecer");
    }

    @Test
    void aTotpStepCanBeAcceptedByOnlyOneOfManyConcurrentRequests() throws Exception {
        User u = users.saveAndFlush(newUser());

        List<Integer> results = runConcurrently(8, () -> advance(u, 100L));

        assertThat(results.stream().filter(r -> r == 1)).hasSize(1);
        assertThat(advance(u, 100L)).isZero();  // o mesmo passo de novo
        assertThat(advance(u, 99L)).isZero();   // um passo mais antigo
        assertThat(advance(u, 101L)).isEqualTo(1);
    }

    @Test
    void aRecoveryCodeCanBeConsumedByOnlyOneOfManyConcurrentRequests() throws Exception {
        User u = users.saveAndFlush(newUser());
        MfaRecoveryCode code = new MfaRecoveryCode();
        code.setId(UUID.randomUUID());
        code.setUserId(u.getId());
        code.setCodeHash("a".repeat(63) + UUID.randomUUID().toString().charAt(0));
        code.setCreatedAt(Instant.now());
        recoveryCodes.saveAndFlush(code);

        List<Integer> results = runConcurrently(8, () -> tx.execute(status -> recoveryCodes.consume(u.getId(), code.getCodeHash(), Instant.now())));

        assertThat(results.stream().filter(r -> r == 1)).hasSize(1);
    }

    @Test
    void concurrentFailuresAreAllCounted_evenWhenTheyCreateTheRowAtTheSameTime() throws Exception {
        String email = "paralelo-" + UUID.randomUUID() + "@test.com";

        runConcurrently(12, () -> {
            throttle.recordFailure(email, null);
            return 0;
        });

        // 12 falhas contadas, sem perder nenhuma por corrida: bloqueio ativo (limite de 5)
        assertThat(throttle.lockedFor(email, null)).isPresent();
        Integer failures = jdbc.queryForObject(
                "select failures from auth_throttle where throttle_key like 'a:%' order by updated_at desc limit 1", Integer.class);
        assertThat(failures).isEqualTo(12);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private int advance(User user, long step) {
        Integer accepted = tx.execute(status -> users.advanceTotpStep(user.getId(), step));
        return accepted == null ? 0 : accepted;
    }

    private <T> List<T> runConcurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private User newUser() {
        User u = new User();
        u.setEmail("pg-" + UUID.randomUUID() + "@test.com");
        u.setPassword("hash");
        u.setRole(Role.ADMIN);
        u.setActive(true);
        return u;
    }
}
