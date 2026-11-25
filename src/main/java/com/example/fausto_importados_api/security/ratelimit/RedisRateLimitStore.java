package com.example.fausto_importados_api.security.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.List;

/**
 * Baldes no Redis, compartilhados por todas as instâncias da API.
 *
 * <p>Leitura, recuperação de fichas, gasto e {@code PEXPIRE} acontecem dentro de UM script Lua: o Redis executa
 * scripts um de cada vez, então não existe corrida entre "checar" e "incrementar". O relógio vem do chamador (não
 * do {@code TIME} do Redis) para que os testes controlem o tempo; as instâncias precisam estar com o relógio
 * sincronizado (NTP), como em qualquer hospedagem.
 */
public class RedisRateLimitStore implements RateLimitStore, AutoCloseable {

    // Mantém a conta em sincronia com TokenBucket.java
    static final String SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local state = redis.call('HMGET', KEYS[1], 't', 'ts')
            local tokens = tonumber(state[1])
            local last = tonumber(state[2])
            if tokens == nil or last == nil then
              tokens = capacity
              last = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - last) * capacity / window)
            local allowed = 0
            if tokens >= 1 - 1e-9 then
              tokens = math.max(0, tokens - 1)
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 't', string.format('%.12f', tokens), 'ts', string.format('%d', now))
            redis.call('PEXPIRE', KEYS[1], window + 1000)
            return {allowed, string.format('%.12f', tokens)}
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private volatile String sha;

    public RedisRateLimitStore(String url) {
        this.client = RedisClient.create(RedisURI.create(url));
        // Redis fora do ar não pode travar as requisições: falha rápido (o RateLimiter decide o que fazer)
        this.client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(300)))
                .build());
        this.client.setDefaultTimeout(Duration.ofMillis(300));
        this.connection = client.connect();
    }

    @Override
    public Outcome consume(String key, int capacity, long windowMs, long nowMs) {
        String[] keys = {key};
        String[] args = {Integer.toString(capacity), Long.toString(windowMs), Long.toString(nowMs)};
        List<Object> reply;
        try {
            reply = connection.sync().evalsha(script(), ScriptOutputType.MULTI, keys, args);
        } catch (RedisCommandExecutionException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith("NOSCRIPT")) {
                throw e;
            }
            sha = null; // o Redis reiniciou ou perdeu o cache de scripts
            reply = connection.sync().evalsha(script(), ScriptOutputType.MULTI, keys, args);
        }
        boolean allowed = ((Number) reply.get(0)).longValue() == 1L;
        return new Outcome(allowed, Double.parseDouble(String.valueOf(reply.get(1))));
    }

    private String script() {
        String current = sha;
        if (current == null) {
            current = connection.sync().scriptLoad(SCRIPT);
            sha = current;
        }
        return current;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
