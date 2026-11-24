package com.example.fausto_importados_api.security.mfa;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Cifra os segredos TOTP em repouso com AES-256-GCM (JCE). Formato guardado: {@code v1:<iv>:<cifrado+tag>}, em base64.
 *
 * <ul>
 *   <li>IV aleatório de 96 bits a cada cifragem (nunca reaproveitado).</li>
 *   <li>O id do usuário entra como dado autenticado adicional (AAD): copiar o segredo cifrado de uma conta
 *       para outra faz a decifragem falhar.</li>
 *   <li>A chave vem de {@code TOTP_ENCRYPTION_KEY}. Perdê-la torna todos os segredos ilegíveis: todos os
 *       administradores precisariam refazer o 2FA.</li>
 * </ul>
 */
@Component
public class SecretCipher {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${security.totp.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "TOTP_ENCRYPTION_KEY não definida. Gere uma com: openssl rand -base64 32");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY precisa estar em base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "TOTP_ENCRYPTION_KEY precisa ter 32 bytes (AES-256). Gere uma com: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(byte[] plaintext, UUID userId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId));
            byte[] encrypted = cipher.doFinal(plaintext);
            Base64.Encoder b64 = Base64.getEncoder();
            return VERSION + ":" + b64.encodeToString(iv) + ":" + b64.encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar o segredo TOTP", e);
        }
    }

    /** @throws IllegalStateException formato desconhecido, chave errada, dado adulterado ou de outra conta */
    public byte[] decrypt(String stored, UUID userId) {
        try {
            String[] parts = stored.split(":");
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalStateException("Formato de segredo TOTP desconhecido");
            }
            Base64.Decoder b64 = Base64.getDecoder();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, b64.decode(parts[1])));
            cipher.updateAAD(aad(userId));
            return cipher.doFinal(b64.decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Não foi possível decifrar o segredo TOTP", e);
        }
    }

    private static byte[] aad(UUID userId) {
        return (VERSION + ":" + userId).getBytes(StandardCharsets.UTF_8);
    }
}
