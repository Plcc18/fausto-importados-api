package com.example.fausto_importados_api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Resposta do login e das etapas do 2FA. O {@code status} diz o que o cliente deve fazer a seguir:
 * <ul>
 *   <li>{@code AUTHENTICATED}: sessão aberta; vêm {@code accessToken} e {@code expiresIn} (e, no fim do
 *       cadastro do 2FA, os {@code recoveryCodes}, mostrados uma única vez).</li>
 *   <li>{@code MFA_REQUIRED}: a senha conferiu; falta o código do autenticador ({@code challenge}).</li>
 *   <li>{@code MFA_SETUP_REQUIRED}: a conta ainda não tem 2FA; é preciso cadastrá-lo ({@code challenge}).
 *       {@code passwordChangeRequired} indica que a senha atual é temporária e deve ser trocada.</li>
 * </ul>
 * O refresh token nunca vai no corpo: só no cookie HttpOnly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponseDTO(
        String status,
        String accessToken,
        Long expiresIn,
        String challenge,
        Boolean passwordChangeRequired,
        List<String> recoveryCodes
) {
    public static final String AUTHENTICATED = "AUTHENTICATED";
    public static final String MFA_REQUIRED = "MFA_REQUIRED";
    public static final String MFA_SETUP_REQUIRED = "MFA_SETUP_REQUIRED";

    public static LoginResponseDTO authenticated(String accessToken, long expiresIn) {
        return new LoginResponseDTO(AUTHENTICATED, accessToken, expiresIn, null, null, null);
    }

    public static LoginResponseDTO enrolled(String accessToken, long expiresIn, List<String> recoveryCodes) {
        return new LoginResponseDTO(AUTHENTICATED, accessToken, expiresIn, null, null, recoveryCodes);
    }

    public static LoginResponseDTO mfaRequired(String challenge) {
        return new LoginResponseDTO(MFA_REQUIRED, null, null, challenge, null, null);
    }

    public static LoginResponseDTO mfaSetupRequired(String challenge, boolean passwordChangeRequired) {
        return new LoginResponseDTO(MFA_SETUP_REQUIRED, null, null, challenge, passwordChangeRequired, null);
    }
}
