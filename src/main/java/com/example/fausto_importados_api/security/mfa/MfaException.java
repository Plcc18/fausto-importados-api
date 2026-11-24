package com.example.fausto_importados_api.security.mfa;

/** Falha previsível do fluxo de 2FA. O tipo decide a resposta HTTP (ver AuthExceptionHandler). */
public class MfaException extends RuntimeException {

    public enum Reason {
        /** Código TOTP/recuperação errado, expirado ou já usado. */
        INVALID_CODE,
        /** A conta já tem 2FA ativo: o cadastro não pode ser refeito por este caminho. */
        ALREADY_ENROLLED,
        /** Confirmação sem ter iniciado o cadastro (não há segredo pendente). */
        SETUP_NOT_STARTED,
        /** Senha nova fora da política. */
        WEAK_PASSWORD
    }

    private final Reason reason;

    public MfaException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
