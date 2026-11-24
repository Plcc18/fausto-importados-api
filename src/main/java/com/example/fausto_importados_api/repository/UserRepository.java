package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    public Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByRole(Role role);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);

    long countByRoleAndActiveTrue(Role role);

    /**
     * Registra o passo TOTP aceito SOMENTE se for mais novo que o último (atômico no banco): duas
     * requisições simultâneas com o mesmo código não passam as duas.
     *
     * @return 1 se o passo foi aceito; 0 se já tinha sido usado (reuso)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update User u set u.totpLastStep = :step
            where u.id = :id and (u.totpLastStep is null or u.totpLastStep < :step)
            """)
    int advanceTotpStep(@Param("id") UUID id, @Param("step") long step);
}
