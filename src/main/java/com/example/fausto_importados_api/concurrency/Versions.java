package com.example.fausto_importados_api.concurrency;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Lock otimista das edições do painel: o cliente devolve a {@code version} que leu; se o registro mudou desde então
 * (outra edição ou uma venda), a edição é recusada com 409 em vez de sobrescrever.
 */
@Component
public class Versions {

    public static final String CONFLICT_CODE = "version_conflict";
    public static final String CONFLICT_MESSAGE =
            "Este registro foi alterado por outra operação. Recarregue e tente novamente.";

    private final boolean required;

    public Versions(ConcurrencyProperties properties) {
        this.required = properties.requireVersion();
    }

    /** Chamar com a linha já travada ou recém-lida. */
    public void check(Long expected, Long actual) {
        if (expected == null) {
            if (required) {
                throw new ApiProblemException(HttpStatus.PRECONDITION_REQUIRED, "version_required",
                        "Informe a versão do registro que está sendo editado.");
            }
            return;
        }
        if (!Objects.equals(expected, actual)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, CONFLICT_CODE, CONFLICT_MESSAGE);
        }
    }
}
