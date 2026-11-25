package com.example.fausto_importados_api.config;

import com.example.fausto_importados_api.security.jwt.JsonAccessDeniedHandler;
import com.example.fausto_importados_api.security.jwt.JwtAuthenticationEntryPoint;
import com.example.fausto_importados_api.security.jwt.JwtAuthenticationFilter;
import com.example.fausto_importados_api.security.jwt.JwtUserDetailsService;
import com.example.fausto_importados_api.security.ratelimit.RateLimitFilter;
import com.example.fausto_importados_api.security.ratelimit.RateLimitProperties;
import com.example.fausto_importados_api.security.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
// Segunda camada: as regras de URL abaixo já negam por padrão; @PreAuthorize nos controllers passa a valer também.
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter authenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;
    private final JwtUserDetailsService userDetailsService;

    public SecurityConfig(
            JwtAuthenticationFilter authenticationFilter,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            JwtUserDetailsService userDetailsService
    ) {
        this.authenticationFilter = authenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http, RateLimiter rateLimiter, RateLimitProperties rateLimitProperties, ObjectMapper json
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // 401 = sem autenticação (ou token inválido); 403 = autenticado, mas sem permissão
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        // Rotas de auth públicas: a credencial é a senha (login), o desafio de 2FA (login/mfa e
                        // mfa/setup, que ainda conferem o desafio no controller), o cookie de refresh (refresh,
                        // protegido por Origin + header customizado) ou o access token (logout)
                        .requestMatchers("/auth/login", "/auth/login/mfa", "/auth/mfa/setup", "/auth/mfa/setup/confirm",
                                "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/auth/me", "/auth/logout-all").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/product/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/image/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/user").hasRole("ADMIN")
                        .requestMatchers("/api/upload").hasRole("ADMIN")
                        // Criação de pedido é pública; gestão é ADMIN
                        .requestMatchers(HttpMethod.POST, "/api/orders").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/product/decrease-stock").hasRole("ADMIN")
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Depois do JWT (precisa saber quem é o usuário) e antes da autorização: um 429 vem antes do 401/403
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitProperties, json), JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            HttpSecurity http,
            PasswordEncoder passwordEncoder
    ) throws Exception {
        AuthenticationManagerBuilder authBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);
        authBuilder
                .userDetailsService(userDetailsService)
                .passwordEncoder(passwordEncoder);
        return authBuilder.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AllowedOrigins allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedMethod("*");
        configuration.addAllowedHeader("*");
        configuration.setAllowCredentials(true);
        // Sem isto o navegador esconde estes cabeçalhos do front (origem diferente da API)
        configuration.setExposedHeaders(List.of("Retry-After", "RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset"));
        allowedOrigins.all().forEach(configuration::addAllowedOrigin);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}