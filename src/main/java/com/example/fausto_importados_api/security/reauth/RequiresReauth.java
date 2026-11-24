package com.example.fausto_importados_api.security.reauth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Operação especialmente sensível (apagar histórico, gerir administradores...): além de estar autenticado,
 * o administrador precisa ter confirmado a identidade com um código 2FA nos últimos minutos
 * ({@code POST /auth/reauth}) e enviar o resultado no header {@code X-Reauth}.
 * Sem isso a resposta é 403 com {@code "code": "reauth_required"}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresReauth {
}
