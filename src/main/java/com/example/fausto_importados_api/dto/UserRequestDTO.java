package com.example.fausto_importados_api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Criação de administrador: só o e-mail. A senha é gerada pelo servidor (temporária, mostrada uma vez). */
public record UserRequestDTO(
        @NotBlank(message = "Email is mandatory!")
        @Email(message = "Email is invalid!")
        @Size(max = 150)
        String email
) {}
