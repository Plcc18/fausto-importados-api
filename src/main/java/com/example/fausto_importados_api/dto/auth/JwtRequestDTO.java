package com.example.fausto_importados_api.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record JwtRequestDTO(
        @NotBlank String email,
        @NotBlank String password
) {
}
