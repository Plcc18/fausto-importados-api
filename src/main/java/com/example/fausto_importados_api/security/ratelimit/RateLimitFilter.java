package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.controller.ClientInfo;
import com.example.fausto_importados_api.dto.auth.ErrorResponseDTO;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.security.ratelimit.RateLimitProperties.Rule;
import com.example.fausto_importados_api.security.refresh.RefreshCookieFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rate limiting das rotas, depois do filtro JWT (precisa saber quem é o usuário).
 *
 * <ul>
 *   <li>Resolve o IP real do cliente (ver {@link ClientInfo#resolve}) e o publica para o resto da requisição.</li>
 *   <li><b>Área admin oculta</b>: para um usuário autenticado que não é admin, {@code /api/admin/**} responde 404 e
 *       nunca chega ao limitador, então um 429 não revela que a rota existe. Quem não está autenticado recebe o 401
 *       comum, igual a qualquer outra rota protegida (ou inexistente).</li>
 *   <li>Aplica as regras da rota e a regra geral; ao negar, responde 429 com {@code Retry-After}, cabeçalhos
 *       {@code RateLimit-*} e mensagem genérica.</li>
 * </ul>
 *
 * Não é um bean de propósito: um {@code Filter} bean também seria registrado fora da cadeia do Spring Security.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String MESSAGE = "Too many requests. Try again later.";

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final ObjectMapper json;
    private final ProxyGate proxyGate;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties props, ObjectMapper json, ProxyGate proxyGate) {
        this.limiter = limiter;
        this.props = props;
        this.json = json;
        this.proxyGate = proxyGate;
    }

    private record Check(String name, Rule rule, String subject) {
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        String path = path(request);

        // /auth/** só pelo proxy do front (quando exigido): 404, como se a rota não existisse
        if (!"OPTIONS".equals(request.getMethod()) && proxyGate.mustBlock(request, path)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // IP informado pelo proxy do front (segredo conferido) ou, na falta dele, o que a cadeia de proxies mostra
        String ip = proxyGate.clientIp(request)
                .orElseGet(() -> ClientInfo.resolve(request, props.trustedProxyHops()));
        request.setAttribute(ClientInfo.ATTRIBUTE, ip);

        if (!props.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        User user = currentUser();

        if (isAdminArea(path) && user != null && !user.isAdmin()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if ("OPTIONS".equals(request.getMethod()) || path.equals("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }

        Decision tightest = null;
        for (Check check : checks(request, path, user, RateLimitKeys.ip(ip))) {
            Decision decision = limiter.check(check.name(), check.rule(), check.subject(), ip);
            if (!decision.allowed()) {
                reject(response, decision);
                return;
            }
            tightest = Decision.tighter(tightest, decision);
        }
        if (tightest != null) {
            tightest.writeHeaders(response::setHeader);
        }
        chain.doFilter(request, response);
    }

    /** Regras da rota primeiro (mais específicas), depois a regra geral. */
    private List<Check> checks(HttpServletRequest request, String path, User user, String ipKey) {
        List<Check> checks = new ArrayList<>();
        boolean post = "POST".equals(request.getMethod());

        if (post && (path.equals("/auth/login") || path.equals("/auth/login/mfa"))) {
            checks.add(new Check("login-ip", props.loginIp(), ipKey));
        }
        if (path.equals("/auth/refresh")) {
            // por sessão: o hash do cookie. Sem cookie (ou com valor aleatório) cai no IP, que a regra geral também limita
            String session = refreshCookie(request);
            checks.add(new Check("refresh", props.refresh(),
                    session == null ? "ip:" + ipKey : "s:" + RateLimitKeys.sha256Short(session)));
        }
        if (post && (path.equals("/api/orders") || props.sensitivePaths().contains(path))) {
            checks.add(new Check("public-write", props.publicWrite(), ipKey));
        }
        if (user != null) {
            checks.add(new Check("api-user", props.apiUser(), user.getId().toString()));
        } else {
            checks.add(new Check("api-anon", props.apiAnon(), ipKey));
        }
        return checks;
    }

    private void reject(HttpServletResponse response, Decision decision) throws IOException {
        decision.writeHeaders(response::setHeader);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(new ErrorResponseDTO(Instant.now().toString(), MESSAGE)));
    }

    /** {@code /api/admin} e tudo abaixo dele, ignorando maiúsculas (o filtro não pode ser mais estrito que o roteamento). */
    static boolean isAdminArea(String path) {
        return path.equals("/api/admin") || path.startsWith("/api/admin/");
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        uri = uri.toLowerCase(Locale.ROOT);
        return uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private static String refreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (RefreshCookieFactory.NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }
}
