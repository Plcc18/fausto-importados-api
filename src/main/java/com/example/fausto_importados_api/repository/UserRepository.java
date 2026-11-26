package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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
     * Trava o usuário. Tudo que mexe na conta, nas sessões ou nos códigos de recuperação começa por aqui, então
     * essas operações acontecem uma de cada vez por usuário.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /** Trava todas as contas ativas do papel, sempre em ordem de id (sem deadlock). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.active = true order by u.id")
    List<User> findActiveByRoleForUpdate(@Param("role") Role role);

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
