package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.audit.Outcome;
import com.example.fausto_importados_api.config.AuthProperties;
import com.example.fausto_importados_api.dto.auth.AccessTokenResponseDTO;
import com.example.fausto_importados_api.dto.auth.ErrorResponseDTO;
import com.example.fausto_importados_api.dto.auth.JwtRequestDTO;
import com.example.fausto_importados_api.dto.auth.LoginResponseDTO;
import com.example.fausto_importados_api.dto.auth.MeResponseDTO;
import com.example.fausto_importados_api.dto.auth.MfaRequests;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.jwt.JwtAuthenticationFilter;
import com.example.fausto_importados_api.security.jwt.JwtService;
import com.example.fausto_importados_api.security.mfa.MfaException;
import com.example.fausto_importados_api.security.mfa.MfaService;
import com.example.fausto_importados_api.security.refresh.RefreshCookieFactory;
import com.example.fausto_importados_api.security.refresh.RefreshRequestGuard;
import com.example.fausto_importados_api.security.refresh.RefreshTokenService;
import com.example.fausto_importados_api.security.throttle.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieFactory cookies;
    private final RefreshRequestGuard refreshGuard;
    private final LoginThrottle throttle;
    private final MfaService mfaService;
    private final UserRepository users;
    private final AuthProperties props;
    private final AuditService audit;
    private final Clock clock;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            RefreshCookieFactory cookies,
            RefreshRequestGuard refreshGuard,
            LoginThrottle throttle,
            MfaService mfaService,
            UserRepository users,
            AuthProperties props,
            AuditService audit,
            Clock clock
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.cookies = cookies;
        this.refreshGuard = refreshGuard;
        this.throttle = throttle;
        this.mfaService = mfaService;
        this.users = users;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    // ───────────────────────────── login ─────────────────────────────

    /**
     * Confere a senha. Administradores ainda precisam do 2FA: a resposta traz um desafio ({@code MFA_REQUIRED}
     * ou {@code MFA_SETUP_REQUIRED}) e a sessão só abre depois do segundo passo. Demais usuários abrem a
     * sessão aqui: access token no corpo, refresh token no cookie HttpOnly.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody JwtRequestDTO request, HttpServletRequest http) {
        String ip = ClientInfo.ip(http);
        Optional<Duration> locked = throttle.lockedFor(request.email(), ip);
        if (locked.isPresent()) {
            auditLocked(request.email(), "password");
            return tooManyAttempts(locked.get());
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );
        } catch (AuthenticationException e) {
            throttle.recordFailure(request.email(), ip);
            auditLoginFailure(request.email(), "bad_credentials");
            throw e; // resposta genérica em AuthExceptionHandler
        }

        User user = (User) authentication.getPrincipal();

        if (user.isAdmin() && (props.mfaRequired() || user.isTotpEnabled())) {
            if (user.isTotpEnabled()) {
                return ResponseEntity.ok(LoginResponseDTO.mfaRequired(jwtService.generateMfaChallenge(user)));
            }
            if (enrollmentExpired(user)) {
                audit.recordAs(user, AuditAction.LOGIN_FAILURE, Outcome.FAILURE, Map.of("reason", "enrollment_expired"));
                // Mesma resposta de uma senha errada: não revela que a senha conferiu.
                throw new BadCredentialsException("Enrollment window expired");
            }
            return ResponseEntity.ok(LoginResponseDTO.mfaSetupRequired(
                    jwtService.generateMfaChallenge(user), user.isMustChangePassword()));
        }

        return openSession(user, http, null, "password");
    }

    /** Segundo fator do login: código do autenticador ou código de recuperação. */
    @PostMapping("/login/mfa")
    public ResponseEntity<?> loginMfa(@Valid @RequestBody MfaRequests.Verify request, HttpServletRequest http) {
        String ip = ClientInfo.ip(http);
        Optional<User> found = challengeUser(request.challenge()).filter(User::isTotpEnabled);
        if (found.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "Invalid or expired challenge");
        }
        User user = found.get();

        Optional<Duration> locked = throttle.lockedFor(user.getEmail(), ip);
        if (locked.isPresent()) {
            audit.recordDeniedAs(user, AuditAction.LOGIN_LOCKED, Map.of("step", "mfa"));
            return tooManyAttempts(locked.get());
        }
        Optional<MfaService.Method> method = mfaService.verify(user.getId(), request.code());
        if (method.isEmpty()) {
            throttle.recordFailure(user.getEmail(), ip);
            audit.recordAs(user, AuditAction.MFA_FAILURE, Outcome.FAILURE, null);
            return error(HttpStatus.UNAUTHORIZED, "Invalid verification code");
        }
        if (method.get() == MfaService.Method.RECOVERY_CODE) {
            audit.recordAs(user, AuditAction.RECOVERY_CODE_USED, Outcome.SUCCESS, null);
        }
        return openSession(user, http, null, method.get() == MfaService.Method.TOTP ? "totp" : "recovery_code");
    }

    /** Primeiro passo do cadastro do 2FA: gera o segredo (pendente) para o QR code / chave manual. */
    @PostMapping("/mfa/setup")
    public ResponseEntity<?> mfaSetup(@Valid @RequestBody MfaRequests.Setup request, HttpServletRequest http) {
        Optional<User> found = challengeUser(request.challenge()).filter(u -> !u.isTotpEnabled());
        if (found.isEmpty() || enrollmentExpired(found.get())) {
            return error(HttpStatus.UNAUTHORIZED, "Invalid or expired challenge");
        }
        User user = found.get();

        Optional<Duration> locked = throttle.lockedFor(user.getEmail(), ClientInfo.ip(http));
        if (locked.isPresent()) {
            return tooManyAttempts(locked.get());
        }
        MfaService.Setup setup = mfaService.beginSetup(user.getId());
        return ResponseEntity.ok(new MfaRequests.SetupInfo(setup.secret(), setup.otpauthUri()));
    }

    /**
     * Fim do cadastro: confere o primeiro código, ativa o 2FA, troca a senha temporária (se for o caso),
     * devolve os códigos de recuperação (única vez) e abre a sessão.
     */
    @PostMapping("/mfa/setup/confirm")
    public ResponseEntity<?> mfaSetupConfirm(@Valid @RequestBody MfaRequests.Confirm request, HttpServletRequest http) {
        String ip = ClientInfo.ip(http);
        Optional<User> found = challengeUser(request.challenge()).filter(u -> !u.isTotpEnabled());
        if (found.isEmpty() || enrollmentExpired(found.get())) {
            return error(HttpStatus.UNAUTHORIZED, "Invalid or expired challenge");
        }
        User user = found.get();

        Optional<Duration> locked = throttle.lockedFor(user.getEmail(), ip);
        if (locked.isPresent()) {
            audit.recordDeniedAs(user, AuditAction.LOGIN_LOCKED, Map.of("step", "mfa_setup"));
            return tooManyAttempts(locked.get());
        }

        List<String> recoveryCodes;
        try {
            recoveryCodes = mfaService.confirmSetup(user.getId(), request.code(), request.newPassword());
        } catch (MfaException e) {
            if (e.reason() == MfaException.Reason.INVALID_CODE) {
                throttle.recordFailure(user.getEmail(), ip);
                audit.recordAs(user, AuditAction.MFA_FAILURE, Outcome.FAILURE, Map.of("step", "setup"));
            }
            throw e;
        }
        User enrolled = users.findById(user.getId()).orElseThrow();
        audit.recordAs(enrolled, AuditAction.MFA_ENABLED, Outcome.SUCCESS, null);
        return openSession(enrolled, http, recoveryCodes, "enrollment");
    }

    // ───────────────────────────── refresh ─────────────────────────────

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

    // ───────────────────────────── sessão ─────────────────────────────

    /** Verifica a sessão com o access token atual e diz quem é (o front usa a role para mostrar ou não o painel). */
    @GetMapping("/me")
    public ResponseEntity<MeResponseDTO> me(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new MeResponseDTO(user.getEmail(), user.getRole().name()));
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
        audit.recordAs(user, AuditAction.LOGOUT_ALL, Outcome.SUCCESS, null);
        return clearedCookies();
    }

    // ───────────────────────────── internos ─────────────────────────────

    /** Abre a sessão (login completo): zera o contador de falhas da conta e entrega os tokens. */
    private ResponseEntity<LoginResponseDTO> openSession(
            User user, HttpServletRequest http, List<String> recoveryCodes, String method
    ) {
        RefreshTokenService.NewSession session = refreshTokenService.startSession(
                user, http.getHeader(HttpHeaders.USER_AGENT), ClientInfo.ip(http)
        );
        throttle.recordSuccess(user.getEmail());
        audit.recordAs(user, AuditAction.LOGIN_SUCCESS, Outcome.SUCCESS, Map.of("method", method));

        String access = jwtService.generateAccessToken(user, session.sessionId());
        long expiresIn = jwtService.accessTtl().toSeconds();
        LoginResponseDTO body = recoveryCodes == null
                ? LoginResponseDTO.authenticated(access, expiresIn)
                : LoginResponseDTO.enrolled(access, expiresIn, recoveryCodes);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearLegacy().toString())
                .body(body);
    }

    /** Usuário dono do desafio de 2FA, se o desafio é válido e a conta ainda é um administrador ativo. */
    private Optional<User> challengeUser(String challenge) {
        return jwtService.parseMfaChallenge(challenge)
                .flatMap(users::findById)
                .filter(User::isEnabled)
                .filter(User::isAdmin);
    }

    private void auditLoginFailure(String email, String reason) {
        users.findByEmailIgnoreCase(email).ifPresentOrElse(
                user -> audit.recordAs(user, AuditAction.LOGIN_FAILURE, Outcome.FAILURE, Map.of("reason", reason)),
                () -> audit.recordAnonymous(AuditAction.LOGIN_FAILURE, Outcome.FAILURE, Map.of("reason", "unknown_account"))
        );
    }

    private void auditLocked(String email, String step) {
        users.findByEmailIgnoreCase(email).ifPresentOrElse(
                user -> audit.recordDeniedAs(user, AuditAction.LOGIN_LOCKED, Map.of("step", step)),
                () -> audit.recordDeniedAs(null, AuditAction.LOGIN_LOCKED, Map.of("step", step, "reason", "unknown_account"))
        );
    }

    private boolean enrollmentExpired(User user) {
        Instant deadline = user.getEnrollmentExpiresAt();
        return deadline != null && !clock.instant().isBefore(deadline);
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

    private static ResponseEntity<ErrorResponseDTO> tooManyAttempts(Duration retryAfter) {
        long seconds = Math.max(1, retryAfter.plusMillis(999).toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new ErrorResponseDTO(Instant.now().toString(), "Too many attempts. Try again later."));
    }

    private static ResponseEntity<ErrorResponseDTO> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponseDTO(Instant.now().toString(), message));
    }
}
