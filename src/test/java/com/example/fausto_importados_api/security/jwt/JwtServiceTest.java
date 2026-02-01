package com.example.fausto_importados_api.security.jwt;

import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Base64;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private JwtService jwtService;

    @Mock
    private UserDetails userDetails;

    private final String SECRET =
            Base64.getEncoder().encodeToString(
                    "minha-chave-secreta-super-segura-256bits".getBytes()
            );

    private final long EXPIRATION = 1000 * 60 * 60; //1 hora

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION);
    }

    //gerar token com sucesso
    @Test
    void shouldGenerateTokenSuccesfully() {
        when(userDetails.getUsername()).thenReturn("pedro");
        when(userDetails.getAuthorities())
                .thenAnswer(invocation ->
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        String token = jwtService.generateToken(userDetails);

        assertThat(token).isNotNull();
        assertThat(token).isNotBlank();
    }

    //extrair subject corretamente
    @Test
    void shouldExtractSubjectFromToken() {
        when(userDetails.getUsername()).thenReturn("pedro");
        when(userDetails.getAuthorities())
                .thenAnswer(invocation ->
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        String token = jwtService.generateToken(userDetails);
        String subject = jwtService.getSubject(token);

        assertThat(subject).isEqualTo("pedro");
    }

    //token válido
    @Test
    void shoudValidTokenSuccesfully() {
        when(userDetails.getUsername()).thenReturn("pedro");
        when(userDetails.getAuthorities())
                .thenAnswer(invocation ->
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        String token = jwtService.generateToken(userDetails);

        boolean valid = jwtService.isTokenValid(token);

        assertThat(valid).isTrue();
    }

    //token expirado é inválido
    @Test
    void shouldReturnFalseForExpiredToken() {
        JwtService expiredJwtService =
                new JwtService(SECRET, -1000);

        when(userDetails.getUsername()).thenReturn("pedro");
        when(userDetails.getAuthorities())
                .thenAnswer(invocation ->
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        String token = expiredJwtService.generateToken(userDetails);

        boolean valid = expiredJwtService.isTokenValid(token);

        assertThat(valid).isFalse();
    }

    //token alterado pe inválido
    @Test
    void shouldReturnFalseForInvalidToken() {
        when(userDetails.getUsername()).thenReturn("pedro");
        when(userDetails.getAuthorities())
                .thenAnswer(invocation ->
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );

        String token = jwtService.generateToken(userDetails);

        String invalidToken = token + "abc";

        boolean valid = jwtService.isTokenValid(invalidToken);

        assertThat(valid).isFalse();
    }
}
