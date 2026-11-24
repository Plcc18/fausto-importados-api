package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.auth.AccessTokenResponseDTO;
import com.example.fausto_importados_api.dto.auth.ErrorResponseDTO;
import com.example.fausto_importados_api.dto.auth.JwtRequestDTO;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.security.jwt.JwtAuthenticationFilter;
import com.example.fausto_importados_api.security.jwt.JwtService;
import com.example.fausto_importados_api.security.refresh.RefreshCookieFactory;
import com.example.fausto_importados_api.security.refresh.RefreshRequestGuard;
import com.example.fausto_importados_api.security.refresh.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieFactory cookies;
    private final RefreshRequestGuard refreshGuard;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            RefreshCookieFactory cookies,
            RefreshRequestGuard refreshGuard
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.cookies = cookies;
        this.refreshGuard = refreshGuard;
    }

    /** Autentica e abre uma sessão: access token no corpo, refresh token no cookie HttpOnly. */
    @PostMapping("/login")
    public ResponseEntity<AccessTokenResponseDTO> login(
            @Valid @RequestBody JwtRequestDTO request,
            HttpServletRequest http
    ) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.email(),
                        request.password()
                )
        );

        User user = (User) authentication.getPrincipal();
        RefreshTokenService.NewSession session = refreshTokenService.startSession(
                user, http.getHeader(HttpHeaders.USER_AGENT), ClientInfo.ip(http)
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearLegacy().toString())
                .body(accessToken(user, session.sessionId()));
    }

    /**
     * Renova o access token e rotaciona o refresh token (sessão deslizante).
     * Respostas de erro são genéricas: não dizem se o token expirou, foi revogado ou reutilizado.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = RefreshCookieFactory.NAME, required = false) String refreshToken,
            HttpServletRequest http
    ) {
        if (!refreshGuard.isTrusted(http)) {
            return error(HttpStatus.FORBIDDEN, "Forbidden");
        }

        RefreshTokenService.RefreshResult result = refreshTokenService.refresh(
                refreshToken, http.getHeader(HttpHeaders.USER_AGENT), ClientInfo.ip(http)
        );

        if (result instanceof RefreshTokenService.RefreshResult.Rotated r) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookies.issue(r.refreshToken()).toString())
                    .body(accessToken(r.user(), r.sessionId()));
        }
        if (result instanceof RefreshTokenService.RefreshResult.Grace g) {
            // Reuso concorrente legítimo: o browser já tem o cookie novo (da requisição vencedora) — não o sobrescrevemos.
            return ResponseEntity.ok().body(accessToken(g.user(), g.sessionId()));
        }
        return error(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    // Endpoint usado pelo frontend para verificar a sessão com o access token atual.
    @GetMapping("/me")
    public ResponseEntity<Void> me(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Encerra a sessão atual. A sessão é identificada pelo {@code sid} do access token (aceito mesmo vencido,
     * desde que a assinatura seja válida), porque o cookie de refresh só trafega na rota de refresh.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        String token = JwtAuthenticationFilter.bearerToken(http);
        if (token != null) {
            jwtService.parseIgnoringExpiry(token)
                    .ifPresent(claims -> refreshTokenService.revokeSession(claims.sessionId()));
        }
        return clearedCookies();
    }

    /** "Sair de todos os dispositivos": revoga todas as sessões do usuário autenticado. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        refreshTokenService.revokeAllSessions(user.getId());
        return clearedCookies();
    }

    private ResponseEntity<Void> clearedCookies() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearLegacy().toString())
                .build();
    }

    private AccessTokenResponseDTO accessToken(User user, UUID sessionId) {
        return new AccessTokenResponseDTO(
                jwtService.generateAccessToken(user, sessionId),
                jwtService.accessTtl().toSeconds()
        );
    }

    private static ResponseEntity<ErrorResponseDTO> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponseDTO(Instant.now().toString(), message));
    }
}
