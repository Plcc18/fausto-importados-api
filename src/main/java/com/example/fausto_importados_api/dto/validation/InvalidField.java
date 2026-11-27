package com.example.fausto_importados_api.dto.validation;

/**
 * Um campo recusado.
 *
 * @param field   caminho no JSON enviado, com {@code [índice]} em listas (ex.: {@code content.hero.buttons[0].label})
 * @param message o que corrigir; o painel mostra junto do campo
 */
public record InvalidField(String field, String message) {
}
