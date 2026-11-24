package com.example.fausto_importados_api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Corpos das operações de conta do próprio administrador. */
public final class AccountRequests {

    private AccountRequests() {
    }

    /** Código 2FA (TOTP ou de recuperação) que confirma a identidade antes de uma operação sensível. */
    public record Reauth(@NotBlank @Size(max = 64) String code) {
    }

    public record ReauthResponse(String reauthToken, long expiresIn) {
    }

    public record ChangePassword(
            @NotBlank @Size(max = 200) String currentPassword,
            @NotBlank @Size(max = 200) String newPassword
    ) {
    }

    /** Novo conjunto de códigos de recuperação, mostrado uma única vez. */
    public record RecoveryCodes(List<String> recoveryCodes) {
    }
}
