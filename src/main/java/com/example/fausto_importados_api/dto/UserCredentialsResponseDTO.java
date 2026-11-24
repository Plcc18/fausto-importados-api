package com.example.fausto_importados_api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Conta criada ou redefinida. {@code temporaryPassword} aparece SÓ nesta resposta (não é guardada em claro):
 * entregue-a ao administrador por um canal seguro. Ele a troca ao cadastrar o 2FA, até {@code enrollmentExpiresAt}.
 */
public record UserCredentialsResponseDTO(UUID id, String email, String temporaryPassword, Instant enrollmentExpiresAt) {}
