package com.example.fausto_importados_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Prazos e regras da autenticação (durações em ISO-8601: PT15M, P7D...). Sobrescreva por ambiente em
 * {@code security.auth.*} — ver application.yml.
 *
 * @param accessTtl         vida do access token (JWT)
 * @param refreshInactivity prazo do refresh token sem uso; é renovado a cada refresh
 * @param refreshAbsolute   limite absoluto da sessão; não é renovado
 * @param refreshGrace      janela em que reapresentar um token já rotacionado NÃO é tratado como roubo
 *                          (requisições concorrentes legítimas, como várias abas)
 * @param mfaRequired       exige 2FA (TOTP) de todo administrador. NUNCA desligue em produção: existe só para
 *                          os testes que não exercitam o 2FA.
 * @param mfaChallengeTtl   validade do desafio emitido depois da senha, enquanto o código TOTP não chega
 * @param throttle          proteção contra tentativas em excesso no login
 */
@ConfigurationProperties(prefix = "security.auth")
public record AuthProperties(
        @DefaultValue("PT15M") Duration accessTtl,
        @DefaultValue("P7D") Duration refreshInactivity,
        @DefaultValue("P30D") Duration refreshAbsolute,
        @DefaultValue("PT10S") Duration refreshGrace,
        @DefaultValue("true") boolean mfaRequired,
        @DefaultValue("PT5M") Duration mfaChallengeTtl,
        @DefaultValue Throttle throttle
) {

    /**
     * Bloqueio progressivo: a partir de {@code *Threshold} falhas a chave fica bloqueada por {@code *BaseLock},
     * dobrando a cada nova falha até {@code maxLock}. Falhas antigas (além de {@code failureWindow}) são esquecidas.
     */
    public record Throttle(
            @DefaultValue("5") int accountThreshold,
            @DefaultValue("PT1M") Duration accountBaseLock,
            @DefaultValue("20") int ipThreshold,
            @DefaultValue("PT5M") Duration ipBaseLock,
            @DefaultValue("PT30M") Duration maxLock,
            @DefaultValue("PT1H") Duration failureWindow
    ) {
    }

    /** Valores de produção; usado em testes unitários. */
    public static AuthProperties defaults() {
        return new AuthProperties(
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(10),
                true, Duration.ofMinutes(5),
                new Throttle(5, Duration.ofMinutes(1), 20, Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(1))
        );
    }
}
