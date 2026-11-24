package com.example.fausto_importados_api.security.mfa;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.PasswordPolicy;
import com.example.fausto_importados_api.security.refresh.RefreshTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Cadastro e verificação do 2FA (TOTP) e dos códigos de recuperação. */
@Service
public class MfaService {

    /** Dados para o administrador cadastrar o autenticador: chave digitável e URI do QR code. */
    public record Setup(String secret, String otpauthUri) {
    }

    public enum Method { TOTP, RECOVERY_CODE }

    private final UserRepository users;
    private final TotpService totp;
    private final SecretCipher cipher;
    private final RecoveryCodeService recoveryCodes;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService sessions;

    public MfaService(
            UserRepository users,
            TotpService totp,
            SecretCipher cipher,
            RecoveryCodeService recoveryCodes,
            PasswordEncoder passwordEncoder,
            RefreshTokenService sessions
    ) {
        this.users = users;
        this.totp = totp;
        this.cipher = cipher;
        this.recoveryCodes = recoveryCodes;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
    }

    /**
     * Gera um segredo novo e o guarda cifrado como PENDENTE (não vale para login até ser confirmado).
     * Chamar de novo troca o segredo pendente. Recusado se o 2FA já está ativo.
     */
    @Transactional
    public Setup beginSetup(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        if (user.isTotpEnabled()) {
            throw new MfaException(MfaException.Reason.ALREADY_ENROLLED, "MFA is already enabled");
        }
        byte[] secret = totp.newSecret();
        user.setTotpSecretEnc(cipher.encrypt(secret, userId));
        user.setTotpLastStep(null);
        users.save(user);
        return new Setup(totp.toBase32(secret), totp.otpauthUri(user.getEmail(), secret));
    }

    /**
     * Confirma o cadastro com um código do autenticador. Ativa o 2FA, troca a senha temporária (se houver)
     * e devolve os códigos de recuperação — a única vez em que aparecem em claro.
     */
    @Transactional
    public List<String> confirmSetup(UUID userId, String code, String newPassword) {
        User user = users.findById(userId).orElseThrow();
        if (user.isTotpEnabled()) {
            throw new MfaException(MfaException.Reason.ALREADY_ENROLLED, "MFA is already enabled");
        }
        if (user.getTotpSecretEnc() == null) {
            throw new MfaException(MfaException.Reason.SETUP_NOT_STARTED, "MFA setup was not started");
        }
        if (user.isMustChangePassword()) {
            String violation = PasswordPolicy.violation(newPassword, user.getEmail());
            if (violation != null) {
                throw new MfaException(MfaException.Reason.WEAK_PASSWORD, violation);
            }
        }

        byte[] secret = cipher.decrypt(user.getTotpSecretEnc(), userId);
        OptionalLong step = totp.match(secret, code);
        if (step.isEmpty()) {
            throw new MfaException(MfaException.Reason.INVALID_CODE, "Invalid verification code");
        }

        user.setTotpEnabled(true);
        user.setTotpLastStep(step.getAsLong());
        user.setEnrollmentExpiresAt(null);
        boolean passwordChanged = user.isMustChangePassword();
        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setMustChangePassword(false);
        }
        users.save(user);
        if (passwordChanged) {
            sessions.revokeAllSessions(userId); // a senha temporária deixa de valer em qualquer sessão
        }
        return recoveryCodes.generate(userId);
    }

    /**
     * Verifica o segundo fator de um login: código TOTP (6 dígitos) ou código de recuperação. Cada código só
     * vale uma vez. Devolve como foi aceito, ou vazio se foi recusado.
     */
    @Transactional
    public java.util.Optional<Method> verify(UUID userId, String input) {
        User user = users.findById(userId).orElse(null);
        if (user == null || !user.isTotpEnabled() || user.getTotpSecretEnc() == null || input == null) {
            return java.util.Optional.empty();
        }

        if (TotpService.looksLikeCode(input)) {
            byte[] secret = cipher.decrypt(user.getTotpSecretEnc(), userId);
            OptionalLong step = totp.match(secret, input);
            // advanceTotpStep é atômico: o mesmo código usado duas vezes (ou em paralelo) só passa uma
            if (step.isPresent() && users.advanceTotpStep(userId, step.getAsLong()) == 1) {
                return java.util.Optional.of(Method.TOTP);
            }
            return java.util.Optional.empty();
        }

        if (RecoveryCodeService.looksLikeCode(input) && recoveryCodes.consume(userId, input)) {
            return java.util.Optional.of(Method.RECOVERY_CODE);
        }
        return java.util.Optional.empty();
    }
}
