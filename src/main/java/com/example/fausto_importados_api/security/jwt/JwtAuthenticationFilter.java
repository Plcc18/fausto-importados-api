package com.example.fausto_importados_api.security.jwt;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.security.refresh.RefreshTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Autentica pelo access token enviado em {@code Authorization: Bearer}. O token não vem mais em cookie,
 * o que também elimina o vetor de CSRF nas demais rotas da API.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JwtUserDetailsService userDetailsService;
    private final RefreshTokenService refreshTokenService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            JwtUserDetailsService userDetailsService,
            RefreshTokenService refreshTokenService
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = bearerToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtService.AccessClaims claims = jwtService.parse(token);

                // Logout / troca de senha / reuso detectado valem imediatamente, sem esperar o access token expirar.
                if (refreshTokenService.isSessionActive(claims.sessionId())) {
                    User user = userDetailsService.loadUserById(claims.userId());

                    if (user.isEnabled()) {
                        UsernamePasswordAuthenticationToken authToken =
                                new UsernamePasswordAuthenticationToken(
                                        user,
                                        null,
                                        user.getAuthorities()
                                );
                        authToken.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request)
                        );
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            } catch (Exception e) {
                // Token inválido ou expirado — segue sem autenticar (o entry point responde 401)
            }
        }

        filterChain.doFilter(request, response);
    }

    public static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
