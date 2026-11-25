package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Limite de tentativas de login por e-mail (a parte por IP fica no {@link RateLimitFilter}).
 *
 * <p>Vale o limite rígido de administrador para admins E para e-mails que não existem: assim a resposta 429 não
 * serve para descobrir quais e-mails são de administradores. Só quem é, comprovadamente, uma conta comum usa o
 * limite mais folgado.
 */
@Component
public class LoginRateLimit {

    private final RateLimiter limiter;
    private final RateLimitProperties props;
    private final UserRepository users;

    public LoginRateLimit(RateLimiter limiter, RateLimitProperties props, UserRepository users) {
        this.limiter = limiter;
        this.props = props;
        this.users = users;
    }

    /** Conta uma tentativa de login deste e-mail. */
    public Decision check(String email, String clientIp) {
        if (!props.enabled()) {
            return Decision.unknown(props.loginEmail().limit());
        }
        boolean knownRegularAccount = Optional.ofNullable(email)
                .flatMap(users::findByEmailIgnoreCase)
                .map(user -> !user.isAdmin())
                .orElse(false);
        return knownRegularAccount
                ? limiter.check("login-email", props.loginEmail(), RateLimitKeys.email(email), clientIp)
                : limiter.check("login-admin-email", props.loginAdminEmail(), RateLimitKeys.email(email), clientIp);
    }
}
