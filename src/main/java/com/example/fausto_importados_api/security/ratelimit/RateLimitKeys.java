package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.controller.ClientInfo;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Montagem das chaves dos baldes. Nenhuma guarda e-mail ou token em texto: só hash. */
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    /**
     * Chave de um IP. Em IPv6, quem tem uma conexão residencial recebe uma faixa /64 inteira (bilhões de
     * endereços): limitar por endereço completo deixaria o atacante trocar de IP à vontade. Por isso a chave é o /64.
     */
    public static String ip(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (ip.indexOf(':') >= 0 && ClientInfo.isIp(ip)) {
            try {
                InetAddress address = InetAddress.getByName(ip);
                byte[] raw = address.getAddress();
                if (raw.length == 16) {
                    return "v6:" + HexFormat.of().formatHex(raw, 0, 8);
                }
                return address.getHostAddress(); // IPv4 escrito como IPv6 (::ffff:1.2.3.4)
            } catch (UnknownHostException e) {
                // não deve acontecer: isIp já validou
            }
        }
        return ip.length() > 45 ? ip.substring(0, 45) : ip;
    }

    public static String email(String email) {
        return sha256Short(email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    public static String sha256Short(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16); // 128 bits bastam para uma chave
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
