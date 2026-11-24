package com.example.fausto_importados_api.security;

import java.nio.charset.StandardCharsets;

/**
 * Política de senha das contas administrativas. Comprimento importa mais que "símbolos obrigatórios":
 * mínimo de 12 caracteres. O teto de 72 bytes é o limite do BCrypt (acima disso o excedente seria ignorado).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @return mensagem do problema, ou {@code null} se a senha é aceitável */
    public static String violation(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Password must have at least " + MIN_LENGTH + " characters";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "Password must have at most " + MAX_BYTES + " bytes";
        }
        if (password.isBlank()) {
            return "Password must not be blank";
        }
        if (email != null && password.equalsIgnoreCase(email)) {
            return "Password must differ from the e-mail";
        }
        return null;
    }
}
