package com.example.fausto_importados_api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Troca de e-mail. A senha NÃO muda por aqui: cada admin troca a própria em POST /auth/password. */
public record UserUpdateDTO(
        @NotBlank(message = "Email is mandatory!")
        @Email(message = "Email is invalid!")
        @Size(max = 150)
        String email
) {}
