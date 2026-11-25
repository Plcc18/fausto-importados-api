package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.controller.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Decide se uma requisição veio do proxy do front (ver {@link ProxyProperties}) e, se veio, qual é o IP do visitante.
 *
 * <p>A comparação do segredo é em tempo constante e sobre o hash SHA-256 dos dois lados, então nem o conteúdo nem o
 * tamanho do segredo vazam pelo tempo de resposta. O segredo e o IP só valem juntos: um {@code X-Client-IP} sem o
 * segredo correto é ignorado, e o IP volta a ser resolvido por {@link ClientInfo#resolve}.
 */
public class ProxyGate {

    public static final String SECRET_HEADER = "X-Proxy-Secret";
    public static final String CLIENT_IP_HEADER = "X-Client-IP";

    static final int MIN_SECRET_LENGTH = 32;

    private final byte[] secretDigest; // null = desligado
    private final boolean requireForAuth;

    public ProxyGate(ProxyProperties props) {
        String secret = props.sharedSecret() == null ? "" : props.sharedSecret().strip();
        if (secret.isEmpty()) {
            if (props.requireForAuth()) {
                throw new IllegalStateException("security.proxy.require-for-auth=true requires PROXY_SHARED_SECRET");
            }
            this.secretDigest = null;
        } else {
            if (secret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException(
                        "PROXY_SHARED_SECRET must have at least " + MIN_SECRET_LENGTH + " characters. "
                                + "Generate one with: openssl rand -base64 32");
            }
            this.secretDigest = sha256(secret);
        }
        this.requireForAuth = props.requireForAuth();
    }

    public boolean enabled() {
        return secretDigest != null;
    }

    /** A requisição traz o segredo correto? */
    public boolean fromTrustedProxy(HttpServletRequest request) {
        if (secretDigest == null) {
            return false;
        }
        String presented = request.getHeader(SECRET_HEADER);
        return presented != null && MessageDigest.isEqual(secretDigest, sha256(presented.strip()));
    }

    /** IP do visitante informado pelo proxy, só se a requisição for do proxy e o valor for um IP válido. */
    public Optional<String> clientIp(HttpServletRequest request) {
        if (!fromTrustedProxy(request)) {
            return Optional.empty();
        }
        String ip = request.getHeader(CLIENT_IP_HEADER);
        ip = ip == null ? null : ip.strip();
        return ClientInfo.isIp(ip) ? Optional.of(ip) : Optional.empty();
    }

    /** {@code /auth/**} fora do proxy, quando isso é proibido. {@code path} já em minúsculas e sem barra final. */
    public boolean mustBlock(HttpServletRequest request, String path) {
        boolean auth = path.equals("/auth") || path.startsWith("/auth/");
        return requireForAuth && auth && !fromTrustedProxy(request);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
