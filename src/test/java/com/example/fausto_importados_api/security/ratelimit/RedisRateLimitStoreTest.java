package com.example.fausto_importados_api.security.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Roda o contrato contra um Redis de verdade (container), exercitando o script Lua. Pulado se não houver Docker;
 * ou aponte para um Redis descartável com {@code -Dtest.redis.url=redis://localhost:6379}.
 */
class RedisRateLimitStoreTest extends RateLimitStoreContract {

    private static final String EXTERNAL_URL = System.getProperty("test.redis.url");

    private static GenericContainer<?> container;
    private static RedisRateLimitStore store;

    @BeforeAll
    static void start() {
        String url = EXTERNAL_URL;
        if (url == null) {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker indisponível: teste do Redis pulado");
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            container.start();
            url = "redis://" + container.getHost() + ":" + container.getMappedPort(6379);
        }
        store = new RedisRateLimitStore(url);
    }

    @AfterAll
    static void stop() {
        if (store != null) {
            store.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Override
    RateLimitStore store() {
        return store;
    }
}
