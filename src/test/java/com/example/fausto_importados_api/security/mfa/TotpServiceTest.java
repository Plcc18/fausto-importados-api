package com.example.fausto_importados_api.security.mfa;

import com.example.fausto_importados_api.support.MutableClock;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    // Segredo dos vetores de teste do Apêndice B da RFC 6238 (SHA-1): "12345678901234567890"
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private MutableClock clock;
    private TotpService totp;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.ofEpochSecond(59));
        totp = new TotpService(clock, "Fausto Importados");
    }

    @Test
    void matchesTheRfc6238TestVectors() {
        // A RFC lista 8 dígitos (ex.: 94287082); com 6 dígitos valem os 6 últimos (287082).
        Map<Long, String> vectors = Map.of(
                59L, "287082",
                1111111109L, "081804",
                1111111111L, "050471",
                1234567890L, "005924",
                2000000000L, "279037",
                20000000000L, "353130"
        );

        vectors.forEach((epochSecond, expected) ->
                assertThat(totp.codeAt(RFC_SECRET, Instant.ofEpochSecond(epochSecond)))
                        .as("T=%d", epochSecond).isEqualTo(expected));
    }

    @Test
    void verifiesTheCodeOfTheCurrentStep_andReturnsItsStep() {
        clock.set(Instant.ofEpochSecond(1111111109L));

        assertThat(totp.match(RFC_SECRET, "081804")).hasValue(1111111109L / 30);
    }

    @Test
    void acceptsOneStepOfClockDriftInEitherDirection_butNotTwo() {
        clock.set(Instant.ofEpochSecond(2000000000L));
        Instant now = clock.instant();

        String previous = totp.codeAt(RFC_SECRET, now.minus(Duration.ofSeconds(30)));
        String next = totp.codeAt(RFC_SECRET, now.plus(Duration.ofSeconds(30)));
        String twoBefore = totp.codeAt(RFC_SECRET, now.minus(Duration.ofSeconds(60)));
        String twoAfter = totp.codeAt(RFC_SECRET, now.plus(Duration.ofSeconds(60)));

        assertThat(totp.match(RFC_SECRET, previous)).hasValue(totp.currentStep() - 1);
        assertThat(totp.match(RFC_SECRET, next)).hasValue(totp.currentStep() + 1);
        assertThat(totp.match(RFC_SECRET, twoBefore)).isEmpty();
        assertThat(totp.match(RFC_SECRET, twoAfter)).isEmpty();
    }

    @Test
    void rejectsWrongOrMalformedCodes() {
        clock.set(Instant.ofEpochSecond(1111111109L)); // código certo: 081804

        assertThat(totp.match(RFC_SECRET, "081805")).isEmpty();
        assertThat(totp.match(RFC_SECRET, "81804")).isEmpty();      // faltou o zero à esquerda
        assertThat(totp.match(RFC_SECRET, "0818040")).isEmpty();
        assertThat(totp.match(RFC_SECRET, "abcdef")).isEmpty();
        assertThat(totp.match(RFC_SECRET, "")).isEmpty();
        assertThat(totp.match(RFC_SECRET, null)).isEmpty();
        assertThat(totp.match(totp.newSecret(), "081804")).isEmpty(); // outro segredo
    }

    @Test
    void generatesIndependent160BitSecrets() {
        byte[] a = totp.newSecret();
        byte[] b = totp.newSecret();

        assertThat(a).hasSize(20);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void base32SecretRoundTripsAndHasNoPadding() {
        byte[] secret = totp.newSecret();

        String encoded = totp.toBase32(secret);

        assertThat(encoded).matches("[A-Z2-7]{32}");
        assertThat(new Base32().decode(encoded)).isEqualTo(secret);
    }

    @Test
    void otpauthUriHasTheStandardFormat() {
        String uri = totp.otpauthUri("admin@fausto.com", RFC_SECRET);

        assertThat(uri).startsWith("otpauth://totp/Fausto%20Importados:admin%40fausto.com?");
        assertThat(uri).contains("secret=" + totp.toBase32(RFC_SECRET));
        assertThat(uri).contains("issuer=Fausto%20Importados", "algorithm=SHA1", "digits=6", "period=30");
    }
}
