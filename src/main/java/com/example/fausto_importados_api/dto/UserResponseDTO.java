package com.example.fausto_importados_api.dto;

import com.example.fausto_importados_api.model.enums.Role;

import java.util.UUID;

public record UserResponseDTO(UUID id, String email, Role role) {}
