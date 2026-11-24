package com.example.fausto_importados_api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Origens do front autorizadas. Fonte única para o CORS e para a verificação de Origin (CSRF)
 * da rota de refresh. {@code FRONTEND_URL} aceita várias origens separadas por vírgula.
 */
@Component
public class AllowedOrigins {

    private final Set<String> origins = new LinkedHashSet<>();

    public AllowedOrigins(@Value("${FRONTEND_URL:http://localhost:5173}") String frontendUrl) {
        Arrays.stream(frontendUrl.split(","))
                .map(String::trim)
                .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o)
                .filter(o -> !o.isEmpty())
                .forEach(origins::add);
        origins.add("http://localhost:5173");
    }

    public Set<String> all() {
        return Set.copyOf(origins);
    }

    public boolean contains(String origin) {
        return origin != null && origins.contains(origin);
    }
}
