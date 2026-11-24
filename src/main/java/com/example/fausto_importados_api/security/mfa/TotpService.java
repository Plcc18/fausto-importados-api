package com.example.fausto_importados_api.security.mfa;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import org.apache.commons.codec.binary.Base32;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * TOTP (RFC 6238: HMAC-SHA1, 6 dígitos, passo de 30 s), o padrão aceito por Google Authenticator, Authy,
 * 1Password etc. O algoritmo vem da biblioteca java-otp; aqui ficam apenas segredo, janela e comparação.
 *
 * <p>Esta classe só diz "qual passo esse código representa". A proteção contra reuso (o mesmo código valendo
 * duas vezes) é feita no banco, de forma atômica — ver {@code UserRepository.advanceTotpStep}.
 */
@Service
public class TotpService {

    public static final Duration STEP = Duration.ofSeconds(30);
    private static final int SECRET_BYTES = 20; // 160 bits, o tamanho do HMAC-SHA1
    private static final int WINDOW = 1;         // aceita o passo anterior e o seguinte (relógios levemente fora)
    private static final Pattern CODE = Pattern.compile("\\d{6}");

    private final TimeBasedOneTimePasswordGenerator generator;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final String issuer;

    public TotpService(Clock clock, @Value("${security.totp.issuer:Fausto Importados}") String issuer) {
        this.clock = clock;
        this.issuer = issuer;
        try {
            this.generator = new TimeBasedOneTimePasswordGenerator(
                    STEP, 6, TimeBasedOneTimePasswordGenerator.TOTP_ALGORITHM_HMAC_SHA1);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    /** Base32 sem preenchimento, o formato digitado/escaneado nos aplicativos autenticadores. */
    public String toBase32(byte[] secret) {
        return new Base32().encodeToString(secret).replace("=", "");
    }

    /** URI {@code otpauth://} que vira o QR code (o desenho do QR é feito no navegador). */
    public String otpauthUri(String accountEmail, byte[] secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(accountEmail)
                + "?secret=" + toBase32(secret)
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=6&period=" + STEP.toSeconds();
    }

    /** O texto tem a forma de um código TOTP (6 dígitos)? */
    public static boolean looksLikeCode(String input) {
        return input != null && CODE.matcher(input.trim()).matches();
    }

    /**
     * Passo (contador de 30 s) que o código representa, dentro da janela de ±1, ou vazio se não confere.
     * A comparação é em tempo constante.
     */
    public OptionalLong match(byte[] secret, String code) {
        if (!looksLikeCode(code)) {
            return OptionalLong.empty();
        }
        byte[] given = code.trim().getBytes(StandardCharsets.UTF_8);
        SecretKey key = new SecretKeySpec(secret, "HmacSHA1");
        long current = currentStep();

        long matched = -1;
        boolean found = false;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            // não interrompe ao achar: o tempo gasto não depende de qual passo bateu
            if (MessageDigest.isEqual(given, codeFor(key, step).getBytes(StandardCharsets.UTF_8)) && !found) {
                matched = step;
                found = true;
            }
        }
        return found ? OptionalLong.of(matched) : OptionalLong.empty();
    }

    /** Código esperado para um instante (usado nos testes). */
    public String codeAt(byte[] secret, Instant instant) {
        try {
            return generator.generateOneTimePasswordString(new SecretKeySpec(secret, "HmacSHA1"), instant);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public long currentStep() {
        return clock.instant().toEpochMilli() / STEP.toMillis();
    }

    private String codeFor(SecretKey key, long step) {
        try {
            return generator.generateOneTimePasswordString(key, Instant.ofEpochMilli(step * STEP.toMillis()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
