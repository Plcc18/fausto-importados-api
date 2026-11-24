package com.example.fausto_importados_api.dto;

import jakarta.validation.constraints.NotNull;

public record UserActiveDTO(@NotNull(message = "active is mandatory!") Boolean active) {}
