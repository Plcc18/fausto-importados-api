package com.example.fausto_importados_api.security.refresh;

import com.example.fausto_importados_api.config.AuthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Monta o cookie do refresh token: HttpOnly, Secure, SameSite=Strict e restrito à rota de refresh. */
@Component
public class RefreshCookieFactory {

    public static final String NAME = "refresh_token";
    public static final String PATH = "/auth/refresh";

    private final boolean secure;
    private final String sameSite;
    private final Duration maxAge;

    public RefreshCookieFactory(
            AuthProperties props,
            @Value("${app.cookie.secure:true}") boolean secure,
            @Value("${app.cookie.same-site:Strict}") String sameSite
    ) {
        this.secure = secure;
        this.sameSite = sameSite;
        // O cookie persiste até o limite absoluto; quem manda é o expires_at gravado no banco.
        this.maxAge = props.refreshAbsolute();
    }

    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    /** Cookie legado ("token", com o JWT de 24h): apagado no login/logout para não ficar órfão no browser. */
    public ResponseCookie clearLegacy() {
        return ResponseCookie.from("token", "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH);
    }
}
