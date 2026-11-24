package com.example.fausto_importados_api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corpos das requisições das etapas do 2FA. */
public final class MfaRequests {

    private MfaRequests() {
    }

    /** Passo de verificação do login: o desafio recebido após a senha + o código (TOTP ou de recuperação). */
    public record Verify(
            @NotBlank @Size(max = 2000) String challenge,
            @NotBlank @Size(max = 64) String code
    ) {
    }

    /** Início do cadastro do 2FA. */
    public record Setup(@NotBlank @Size(max = 2000) String challenge) {
    }

    /** Fim do cadastro: o primeiro código do autenticador (e a senha nova, se a atual era temporária). */
    public record Confirm(
            @NotBlank @Size(max = 2000) String challenge,
            @NotBlank @Size(max = 64) String code,
            @Size(max = 200) String newPassword
    ) {
    }

    /** Dados para o autenticador: chave digitável (Base32) e a URI otpauth que vira o QR code. */
    public record SetupInfo(String secret, String otpauthUri) {
    }
}
