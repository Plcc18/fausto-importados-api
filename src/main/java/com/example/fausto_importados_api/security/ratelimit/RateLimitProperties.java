package com.example.fausto_importados_api.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Limites de requisições. Cada regra é escrita como {@code <máximo>/<janela>}, por exemplo {@code 5/15m},
 * {@code 100/1m} ou {@code 3/1h} (janela no formato de duração do Spring: ms, s, m, h, d).
 *
 * <p>O algoritmo é <i>token bucket</i>: o balde começa cheio (o máximo de requisições de uma vez) e recupera
 * {@code máximo / janela} fichas por unidade de tempo. Depois de uma janela inteira sem uso, está cheio de novo.
 *
 * @param enabled           liga/desliga todo o rate limiting
 * @param store             {@code auto} (Redis se {@code redisUrl} existir; senão memória em dev/teste e Postgres nos
 *                          demais casos), {@code redis}, {@code jdbc} ou {@code memory}
 * @param redisUrl          {@code redis://} ou {@code rediss://}; vazio = sem Redis
 * @param trustedProxyHops  quantos proxies seus ficam na frente da API (0 = ignora X-Forwarded-For).
 *                          Vercel + Render = 2. Ver {@link com.example.fausto_importados_api.controller.ClientInfo}
 * @param loginIp           tentativas de login por IP
 * @param loginEmail        tentativas de login por e-mail (contas comuns)
 * @param loginAdminEmail   tentativas de login por e-mail de administrador; também vale para e-mails desconhecidos,
 *                          para que o limite não revele quais contas existem
 * @param refresh           renovações de sessão, por sessão (hash do cookie de refresh)
 * @param publicWrite       escritas públicas sensíveis, por IP (criação de pedido, cadastro, recuperação de senha)
 * @param apiUser           API em geral, por usuário autenticado
 * @param apiAnon           API em geral, por IP, sem login
 * @param sensitivePaths    caminhos (além de POST /api/orders) que usam a regra {@code publicWrite}
 */
@ConfigurationProperties(prefix = "security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("auto") String store,
        @DefaultValue("") String redisUrl,
        @DefaultValue("1") int trustedProxyHops,
        @DefaultValue("5/15m") Rule loginIp,
        @DefaultValue("5/15m") Rule loginEmail,
        @DefaultValue("3/15m") Rule loginAdminEmail,
        @DefaultValue("30/1m") Rule refresh,
        @DefaultValue("3/1h") Rule publicWrite,
        @DefaultValue("100/1m") Rule apiUser,
        @DefaultValue("60/1m") Rule apiAnon,
        @DefaultValue({"/auth/register", "/auth/password/forgot", "/auth/password/reset"}) List<String> sensitivePaths
) {

    public RateLimitProperties {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("security.rate-limit.trusted-proxy-hops must be >= 0");
        }
    }

    /** Maior janela configurada: um balde parado por esse tempo já voltou ao máximo e pode ser apagado. */
    public Duration maxWindow() {
        return Stream.of(loginIp, loginEmail, loginAdminEmail, refresh, publicWrite, apiUser, apiAnon)
                .map(Rule::window)
                .max(Duration::compareTo)
                .orElse(Duration.ofHours(1));
    }

    /** {@code limit} requisições por {@code window}. */
    public record Rule(int limit, Duration window) {

        public Rule {
            if (limit < 1) {
                throw new IllegalArgumentException("rate limit must allow at least 1 request: " + limit);
            }
            if (window == null || window.toMillis() < 1) {
                throw new IllegalArgumentException("rate limit window must be at least 1ms");
            }
        }

        /** Usado pelo Spring para converter textos como {@code 5/15m}. */
        public static Rule valueOf(String text) {
            String[] parts = text.trim().split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid rate limit '" + text + "' (expected <max>/<window>, e.g. 5/15m)");
            }
            return new Rule(Integer.parseInt(parts[0].trim()), DurationStyle.detectAndParse(parts[1].trim()));
        }

        @Override
        public String toString() {
            return limit + "/" + window;
        }
    }
}
