package com.example.fausto_importados_api.dto;

import jakarta.validation.constraints.NotBlank;

public record UserRequestDTO(
        @NotBlank(message = "Email is mandatory!")
        String email,

        @NotBlank(message = "Password is mandatory!")
        String password
) {}
