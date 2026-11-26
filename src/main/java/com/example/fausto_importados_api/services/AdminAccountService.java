package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.concurrency.Transactions;
import com.example.fausto_importados_api.concurrency.Versions;
import com.example.fausto_importados_api.config.AuthProperties;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.MfaRecoveryCodeRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.jwt.JwtAuthenticationFilter;
import com.example.fausto_importados_api.security.refresh.RefreshTokenService;
import com.example.fausto_importados_api.security.throttle.LoginThrottle;
import com.example.fausto_importados_api.services.exception.AccountConflictException;
import com.example.fausto_importados_api.services.exception.UserNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ciclo de vida das contas de administrador: criar, trocar e-mail, ativar/desativar, redefinir credenciais e
 * excluir. Regras que protegem contra acidentes (e contra um admin comprometido apagar os demais):
 * <ul>
 *   <li>nunca se desativa/exclui/redefine a PRÓPRIA conta por aqui;</li>
 *   <li>nunca se remove ou desativa o ÚLTIMO administrador ativo;</li>
 *   <li>quem cria ou redefine NUNCA escolhe a senha: o servidor gera uma temporária (mostrada uma vez) que o
 *       novo admin troca ao cadastrar o 2FA, dentro de um prazo.</li>
 * </ul>
 * As rotas que chamam este serviço exigem reautenticação por código 2FA recente.
 */
@Service
public class AdminAccountService {

    // sem caracteres ambíguos (0/O, 1/l/I): a senha temporária é passada ao novo admin por outro canal
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 20;

    /** Conta recém-criada/redefinida e a senha temporária em claro (existe só nesta resposta). */
    public record Credentials(User user, String temporaryPassword) {
    }

    private final UserRepository users;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService sessions;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final Transactions transactions;
    private final Versions versions;
    private final AuthProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminAccountService(
            UserRepository users,
            MfaRecoveryCodeRepository recoveryCodes,
            PasswordEncoder passwordEncoder,
            RefreshTokenService sessions,
            LoginThrottle throttle,
            AuditService audit,
            Transactions transactions,
            Versions versions,
            AuthProperties props,
            Clock clock
    ) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.throttle = throttle;
        this.audit = audit;
        this.transactions = transactions;
        this.versions = versions;
        this.props = props;
        this.clock = clock;
    }

    public Credentials create(String email) {
        String normalized = normalize(email);
        return transactions.run(() -> {
            if (users.existsByEmailIgnoreCase(normalized)) {
                throw emailTaken();
            }

            String temporaryPassword = newTemporaryPassword();
            User admin = new User();
            admin.setEmail(normalized);
            admin.setPassword(passwordEncoder.encode(temporaryPassword));
            admin.setRole(Role.ADMIN);
            admin.setActive(true);
            admin.setMustChangePassword(true);
            admin.setEnrollmentExpiresAt(clock.instant().plus(props.enrollmentWindow()));
            saveUnique(admin);

            transactions.afterCommit(() -> audit.recordSuccess(
                    AuditAction.ADMIN_CREATED, "USER", admin.getId(), Map.of("email", normalized)));
            return new Credentials(admin, temporaryPassword);
        });
    }

    public User changeEmail(UUID id, String email, Long expectedVersion) {
        String normalized = normalize(email);
        return transactions.run(() -> {
            User user = lock(id);
            versions.check(expectedVersion, user.getVersion());
            if (user.getEmail().equalsIgnoreCase(normalized)) {
                return user;
            }
            if (users.existsByEmailIgnoreCase(normalized)) {
                throw emailTaken();
            }
            String previous = user.getEmail();
            user.setEmail(normalized);
            saveUnique(user);

            transactions.afterCommit(() -> audit.recordSuccess(AuditAction.ADMIN_EMAIL_CHANGED, "USER", id,
                    Map.of("from", previous, "to", normalized)));
            return user;
        });
    }

    public User setActive(UUID id, boolean active, Long expectedVersion) {
        return transactions.run(() -> {
            List<User> admins = lockAdminsAsActor();
            User user = lock(id);
            versions.check(expectedVersion, user.getVersion());
            User actor = actor();
            if (!active) {
                if (actor != null && actor.getId().equals(id)) {
                    throw new AccountConflictException("You cannot deactivate your own account");
                }
                ensureNotLastActiveAdmin(user, admins);
            }
            if (user.getActive() == active) {
                return user;
            }
            user.setActive(active);
            users.saveAndFlush(user);
            if (!active) {
                sessions.revokeAllSessions(id); // desativar vale na hora: nenhuma sessão continua aberta
            }

            transactions.afterCommit(() -> audit.recordSuccess(
                    active ? AuditAction.ADMIN_ACTIVATED : AuditAction.ADMIN_DEACTIVATED,
                    "USER", id, Map.of("email", user.getEmail())));
            return user;
        });
    }

    /**
     * Esquecimento de senha ou perda do aparelho: gera senha temporária, apaga o 2FA e os códigos de recuperação,
     * encerra as sessões e abre um novo prazo de cadastro. O admin refaz o 2FA no próximo login.
     */
    public Credentials resetCredentials(UUID id) {
        return transactions.run(() -> {
            lockAdminsAsActor();
            User user = lock(id);
            User actor = actor();
            if (actor != null && actor.getId().equals(id)) {
                throw new AccountConflictException(
                        "You cannot reset your own credentials; change your password or use a recovery code");
            }
            if (user.getRole() != Role.ADMIN) {
                throw new AccountConflictException("Only administrator accounts use this flow");
            }

            String temporaryPassword = newTemporaryPassword();
            user.setPassword(passwordEncoder.encode(temporaryPassword));
            user.setMustChangePassword(true);
            user.setTotpSecretEnc(null);
            user.setTotpEnabled(false);
            user.setTotpLastStep(null);
            user.setEnrollmentExpiresAt(clock.instant().plus(props.enrollmentWindow()));
            users.saveAndFlush(user);
            recoveryCodes.deleteAllByUser(id);
            sessions.revokeAllSessions(id);
            throttle.recordSuccess(user.getEmail()); // de quebra, destrava a conta se estava bloqueada

            transactions.afterCommit(() -> audit.recordSuccess(
                    AuditAction.ADMIN_CREDENTIALS_RESET, "USER", id, Map.of("email", user.getEmail())));
            return new Credentials(user, temporaryPassword);
        });
    }

    public void delete(UUID id) {
        transactions.execute(() -> {
            List<User> admins = lockAdminsAsActor();
            User user = lock(id);
            User actor = actor();
            if (actor != null && actor.getId().equals(id)) {
                throw new AccountConflictException("You cannot delete your own account");
            }
            ensureNotLastActiveAdmin(user, admins);

            users.delete(user); // os refresh tokens e códigos de recuperação saem junto (ON DELETE CASCADE)
            users.flush();
            transactions.afterCommit(() -> audit.recordSuccess(
                    AuditAction.ADMIN_DELETED, "USER", id, Map.of("email", user.getEmail())));
        });
    }

    // ───────────────────────────── internos ─────────────────────────────

    private User lock(UUID id) {
        return users.findByIdForUpdate(id).orElseThrow(() -> new UserNotFoundException("Admin not found"));
    }

    /**
     * Trava todos os administradores ativos (primeiro lock das operações que podem tirar alguém do painel) e
     * confere que quem pediu continua sendo um deles, com a sessão viva. Sem isso, dois admins poderiam se
     * desativar (ou redefinir) mutuamente ao mesmo tempo.
     */
    private List<User> lockAdminsAsActor() {
        List<User> admins = users.findActiveByRoleForUpdate(Role.ADMIN);
        User actor = actor();
        if (actor != null) {
            UUID sessionId = currentSessionId();
            boolean stillAllowed = admins.stream().anyMatch(admin -> admin.getId().equals(actor.getId()))
                    && (sessionId == null || sessions.isSessionActive(sessionId));
            if (!stillAllowed) {
                throw new AccountConflictException("Your account or session is no longer active");
            }
        }
        return admins;
    }

    private static void ensureNotLastActiveAdmin(User target, List<User> activeAdmins) {
        if (target.getRole() == Role.ADMIN && target.getActive() && activeAdmins.size() <= 1) {
            throw new AccountConflictException("Cannot remove the last active administrator");
        }
    }

    /** Grava na hora, para que o índice único do e-mail responda aqui (409) e não no commit. */
    private void saveUnique(User user) {
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException duplicate) {
            throw emailTaken();
        }
    }

    private static AccountConflictException emailTaken() {
        return new AccountConflictException("Email already registered");
    }

    private static UUID currentSessionId() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request != null && request.getAttribute(
                JwtAuthenticationFilter.SESSION_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof UUID id
                ? id : null;
    }

    private static User actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String newTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
