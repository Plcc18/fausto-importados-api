package com.example.fausto_importados_api.dto.auth;

/**
 * Resposta de login/refresh. O refresh token NÃO vai no corpo: só no cookie HttpOnly.
 *
 * @param expiresIn validade do access token, em segundos
 */
public record AccessTokenResponseDTO(String accessToken, long expiresIn) {
}
