package com.example.fausto_importados_api.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Autenticado, mas sem permissão (ex.: usuário comum numa rota de administrador) → 403 em JSON.
 * (Sem autenticação é 401, tratado por {@link JwtAuthenticationEntryPoint}.)
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {
                  "status": 403,
                  "error": "Forbidden",
                  "message": "You do not have permission to access this resource"
                }
                """);
    }
}
