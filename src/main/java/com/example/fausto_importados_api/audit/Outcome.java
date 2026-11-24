package com.example.fausto_importados_api.audit;

public enum Outcome {
    SUCCESS,
    /** A operação foi tentada e falhou (senha errada, código inválido...). */
    FAILURE,
    /** A operação foi recusada por regra de acesso (bloqueio, sem permissão, reautenticação exigida). */
    DENIED
}
