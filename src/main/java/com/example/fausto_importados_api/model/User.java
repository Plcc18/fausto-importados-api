package com.example.fausto_importados_api.model;

import com.example.fausto_importados_api.model.enums.Role;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
// Grava só as colunas alteradas: um update não desfaz o que outra operação mudou (ex.: totp_last_step)
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank(message = "Email is mandatory!")
    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @NotBlank(message = "Password is mandatory!")
    @Column(nullable = false, length = 150)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private Boolean active = true;

    // ─── 2FA (TOTP) ─────────────────────────────────────────────────────────────

    /** Segredo TOTP cifrado (AES-GCM). Pendente enquanto {@link #totpEnabled} for falso. */
    @Column(name = "totp_secret_enc", length = 255)
    private String totpSecretEnc;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    /** Último passo de 30 s aceito; um código não pode ser reutilizado. */
    @Column(name = "totp_last_step")
    private Long totpLastStep;

    /** Senha temporária: precisa ser trocada ao ativar a conta. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /** Prazo para concluir o cadastro do 2FA (conta nova ou redefinida). Nulo = sem prazo. */
    @Column(name = "enrollment_expires_at")
    private Instant enrollmentExpiresAt;

    /** Lock otimista. */
    @Version
    @Column(nullable = false)
    private Long version;

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
