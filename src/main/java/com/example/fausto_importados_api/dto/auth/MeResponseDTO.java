package com.example.fausto_importados_api.dto.auth;

/** Quem está autenticado. O front usa {@code role} para decidir o que mostrar; a API revalida a cada requisição. */
public record MeResponseDTO(String email, String role) {
}
