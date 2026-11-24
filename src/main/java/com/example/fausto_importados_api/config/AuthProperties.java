package com.example.fausto_importados_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Prazos da autenticação (formato ISO-8601: PT15M, P7D...). Sobrescreva por ambiente em
 * {@code security.auth.*} — ver application.yml.
 *
 * @param accessTtl         vida do access token (JWT)
 * @param refreshInactivity prazo do refresh token sem uso; é renovado a cada refresh
 * @param refreshAbsolute   limite absoluto da sessão; não é renovado
 * @param refreshGrace      janela em que reapresentar um token já rotacionado NÃO é tratado como roubo
 *                          (requisições concorrentes legítimas, como várias abas)
 */
@ConfigurationProperties(prefix = "security.auth")
public record AuthProperties(
        @DefaultValue("PT15M") Duration accessTtl,
        @DefaultValue("P7D") Duration refreshInactivity,
        @DefaultValue("P30D") Duration refreshAbsolute,
        @DefaultValue("PT10S") Duration refreshGrace
) {
}
