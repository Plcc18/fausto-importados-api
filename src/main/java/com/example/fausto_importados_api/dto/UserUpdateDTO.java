package com.example.fausto_importados_api.dto;

import jakarta.validation.constraints.NotBlank;

public record UserUpdateDTO(
        @NotBlank(message = "Email is mandatory!")
        String email,

        // Opcional — se vazio, a senha atual é mantida
        String password
) {}
