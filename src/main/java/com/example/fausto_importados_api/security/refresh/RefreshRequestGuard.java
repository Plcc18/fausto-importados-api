package com.example.fausto_importados_api.security.refresh;

import com.example.fausto_importados_api.config.AllowedOrigins;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Proteção CSRF da rota de refresh, somada ao {@code SameSite=Strict} do cookie:
 * <ol>
 *   <li>o {@code Origin} da requisição precisa ser uma origem do front autorizada;</li>
 *   <li>o header customizado {@value #REQUIRED_HEADER} é obrigatório — um formulário de outro site não consegue
 *       enviá-lo, e via fetch ele força preflight de CORS.</li>
 * </ol>
 */
@Component
public class RefreshRequestGuard {

    public static final String REQUIRED_HEADER = "X-Requested-With";
    public static final String REQUIRED_HEADER_VALUE = "fausto-web";

    private final AllowedOrigins allowedOrigins;

    public RefreshRequestGuard(AllowedOrigins allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public boolean isTrusted(HttpServletRequest request) {
        return allowedOrigins.contains(request.getHeader("Origin"))
                && REQUIRED_HEADER_VALUE.equals(request.getHeader(REQUIRED_HEADER));
    }
}
