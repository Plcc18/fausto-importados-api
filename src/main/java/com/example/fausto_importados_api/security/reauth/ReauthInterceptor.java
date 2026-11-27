package com.example.fausto_importados_api.security.reauth;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.config.AuthProperties;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.security.jwt.JwtAuthenticationFilter;
import com.example.fausto_importados_api.security.jwt.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Exige a reautenticação nos métodos anotados com {@link RequiresReauth}. Roda DEPOIS da autenticação e da
 * autorização por papel: aqui só chegam administradores já autenticados.
 */
@Component
public class ReauthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Reauth";

    private final JwtService jwtService;
    private final AuthProperties props;
    private final AuditService audit;

    public ReauthInterceptor(JwtService jwtService, AuthProperties props, AuditService audit) {
        this.jwtService = jwtService;
        this.props = props;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(RequiresReauth.class)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return true; // sem autenticação quem responde é a camada de segurança (401)
        }
        if (isConfirmed(request, user)) {
            return true;
        }

        recordMissing(request);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {
                  "status": 403,
                  "error": "Forbidden",
                  "code": "reauth_required",
                  "message": "Re-authentication is required for this operation"
                }
                """);
        return false;
    }

    /**
     * A mesma exigência de {@link RequiresReauth}, para quando só se sabe durante a operação se ela é sensível (ex.:
     * editar uma campanha que está publicada).
     *
     * @throws ApiProblemException 403 {@code reauth_required}
     */
    public void require(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user
                && isConfirmed(request, user)) {
            return;
        }
        recordMissing(request);
        throw new ApiProblemException(HttpStatus.FORBIDDEN, "reauth_required",
                "Confirme sua identidade para continuar.");
    }

    private boolean isConfirmed(HttpServletRequest request, User user) {
        // Reautenticar significa confirmar um segundo fator. Sem 2FA em vigor (só nos testes) não há o que confirmar.
        if (!props.mfaRequired() && !user.isTotpEnabled()) {
            return true;
        }
        Object sessionId = request.getAttribute(JwtAuthenticationFilter.SESSION_ATTRIBUTE);
        Optional<JwtService.ReauthClaims> claims = jwtService.parseReauthToken(request.getHeader(HEADER));
        return claims.isPresent()
                && sessionId instanceof UUID sid
                && claims.get().userId().equals(user.getId())
                && claims.get().sessionId().equals(sid);
    }

    private void recordMissing(HttpServletRequest request) {
        audit.recordDenied(AuditAction.REAUTH_REQUIRED, "ENDPOINT",
                request.getMethod() + " " + request.getRequestURI(), Map.of("reason", "missing_or_invalid_reauth"));
    }
}
