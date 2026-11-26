package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.audit.Outcome;
import com.example.fausto_importados_api.dto.auth.AccountRequests;
import com.example.fausto_importados_api.dto.auth.ErrorResponseDTO;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.PasswordPolicy;
import com.example.fausto_importados_api.security.jwt.JwtAuthenticationFilter;
import com.example.fausto_importados_api.security.jwt.JwtService;
import com.example.fausto_importados_api.security.mfa.MfaService;
import com.example.fausto_importados_api.security.mfa.RecoveryCodeService;
import com.example.fausto_importados_api.security.reauth.RequiresReauth;
import com.example.fausto_importados_api.security.throttle.LoginThrottle;
import com.example.fausto_importados_api.services.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operações do próprio administrador sobre a conta: reautenticar, trocar a senha e gerar novos códigos de
 * recuperação. Todas exigem estar autenticado como ADMIN (regra padrão de {@code SecurityConfig}).
 *
 * <p>Falhas de confirmação respondem 403 (e não 401): o 401 faria o front tentar renovar o token e repetir a
 * requisição, gastando uma segunda tentativa do usuário.
 */
@RestController
@RequestMapping("/auth")
public class AccountController {

    private final MfaService mfaService;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accounts;
    private final RecoveryCodeService recoveryCodes;

    public AccountController(
            MfaService mfaService,
            JwtService jwtService,
            LoginThrottle throttle,
            AuditService audit,
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AccountService accounts,
            RecoveryCodeService recoveryCodes
    ) {
        this.mfaService = mfaService;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.audit = audit;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accounts = accounts;
        this.recoveryCodes = recoveryCodes;
    }

    /** Confirma a identidade com um código 2FA e devolve a prova (válida por poucos minutos, presa a esta sessão). */
    @PostMapping("/reauth")
    public ResponseEntity<?> reauth(
            @Valid @RequestBody AccountRequests.Reauth request,
            @AuthenticationPrincipal User user,
            HttpServletRequest http
    ) {
        UUID sessionId = (UUID) http.getAttribute(JwtAuthenticationFilter.SESSION_ATTRIBUTE);
        String ip = ClientInfo.ip(http);

        LoginThrottle.Attempt attempt = throttle.reserve(user.getEmail(), ip);
        if (attempt.blockedFor().isPresent()) {
            audit.recordDeniedAs(user, AuditAction.LOGIN_LOCKED, Map.of("step", "reauth"));
            return tooManyAttempts(attempt.blockedFor().get());
        }
        if (sessionId == null || mfaService.verify(user.getId(), request.code()).isEmpty()) {
            audit.recordAs(user, AuditAction.REAUTH_FAILURE, Outcome.FAILURE, null);
            return error(HttpStatus.FORBIDDEN, "invalid_code", "Invalid verification code");
        }
        attempt.release();

        audit.recordAs(user, AuditAction.REAUTH_SUCCESS, Outcome.SUCCESS, null);
        return ResponseEntity.ok(new AccountRequests.ReauthResponse(
                jwtService.generateReauthToken(user, sessionId), jwtService.reauthTtl().toSeconds()));
    }

    /**
     * Troca a PRÓPRIA senha. Exige a senha atual (além da reautenticação) e derruba as outras sessões, mantendo
     * a atual. A senha nova segue a política (mínimo de 12 caracteres).
     */
    @PostMapping("/password")
    @RequiresReauth
    public ResponseEntity<?> changePassword(
            @Valid @RequestBody AccountRequests.ChangePassword request,
            @AuthenticationPrincipal User principal,
            HttpServletRequest http
    ) {
        String ip = ClientInfo.ip(http);
        LoginThrottle.Attempt attempt = throttle.reserve(principal.getEmail(), ip);
        if (attempt.blockedFor().isPresent()) {
            return tooManyAttempts(attempt.blockedFor().get());
        }

        User user = users.findById(principal.getId()).orElseThrow();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            audit.recordAs(user, AuditAction.PASSWORD_CHANGED, Outcome.FAILURE, Map.of("reason", "wrong_current_password"));
            return error(HttpStatus.FORBIDDEN, "invalid_password", "Current password is incorrect");
        }
        attempt.release();
        String violation = PasswordPolicy.violation(request.newPassword(), user.getEmail());
        if (violation != null) {
            return error(HttpStatus.BAD_REQUEST, "weak_password", violation);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            return error(HttpStatus.BAD_REQUEST, "weak_password", "New password must differ from the current one");
        }

        // O BCrypt roda fora da transação; a gravação confere, com a conta travada, que nada mudou no meio
        UUID sessionId = (UUID) http.getAttribute(JwtAuthenticationFilter.SESSION_ATTRIBUTE);
        accounts.changePassword(
                user.getId(), user.getPassword(), passwordEncoder.encode(request.newPassword()), sessionId);
        audit.recordAs(user, AuditAction.PASSWORD_CHANGED, Outcome.SUCCESS, Map.of("otherSessionsRevoked", true));
        return ResponseEntity.noContent().build();
    }

    /** Gera um novo conjunto de códigos de recuperação (os anteriores deixam de valer). Mostrados uma única vez. */
    @PostMapping("/mfa/recovery-codes")
    @RequiresReauth
    public ResponseEntity<?> regenerateRecoveryCodes(@AuthenticationPrincipal User principal) {
        User user = users.findById(principal.getId()).orElseThrow();
        if (!user.isTotpEnabled()) {
            return error(HttpStatus.CONFLICT, "mfa_not_enabled", "MFA is not enabled");
        }
        audit.recordAs(user, AuditAction.RECOVERY_CODES_REGENERATED, Outcome.SUCCESS, null);
        return ResponseEntity.ok(new AccountRequests.RecoveryCodes(recoveryCodes.generate(user.getId())));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(), "code", code, "message", message));
    }

    private static ResponseEntity<ErrorResponseDTO> tooManyAttempts(Duration retryAfter) {
        long seconds = Math.max(1, retryAfter.plusMillis(999).toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new ErrorResponseDTO(Instant.now().toString(), "Too many attempts. Try again later."));
    }
}
