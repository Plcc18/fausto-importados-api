package com.example.fausto_importados_api.dto;

import jakarta.validation.constraints.NotNull;

/** {@code version}: a versão lida pelo painel antes de editar. */
public record UserActiveDTO(@NotNull(message = "active is mandatory!") Boolean active, Long version) {}
