package com.example.fausto_importados_api.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record JwtRequestDTO(String email, String password) {
}
