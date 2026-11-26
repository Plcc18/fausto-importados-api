package com.example.fausto_importados_api.initializer;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Cria o PRIMEIRO administrador (bootstrap) a partir de {@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD}, e só
 * quando ainda não existe nenhum administrador. Os demais são criados por um administrador já autenticado.
 *
 * <p>Depois do primeiro login (que cadastra o 2FA) as duas variáveis podem ser removidas do ambiente.
 */
@Component
public class AdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_EMAIL:}")
    private String adminEmail;

    @Value("${ADMIN_PASSWORD:}")
    private String adminPassword;

    public AdminInitializer(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (users.existsByRole(Role.ADMIN)) {
            return; // já há administrador: nada a fazer (e as variáveis de bootstrap podem sair do ambiente)
        }
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.warn("Nenhum administrador existe e ADMIN_EMAIL/ADMIN_PASSWORD não foram definidos: "
                    + "ninguém conseguirá entrar no painel.");
            return;
        }

        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);
        try {
            users.saveAndFlush(admin);
        } catch (DataIntegrityViolationException createdByAnotherInstance) {
            log.info("Administrador inicial já criado por outra instância");
            return;
        }

        log.info("Administrador inicial criado: {} (o 2FA é cadastrado no primeiro login)", adminEmail);
    }
}
