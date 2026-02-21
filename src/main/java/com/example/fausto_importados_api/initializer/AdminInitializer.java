package com.example.fausto_importados_api.initializer;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.services.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements CommandLineRunner {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_EMAIL}")
    private String adminEmail;

    @Value("${ADMIN_PASSWORD}")
    private String adminPassword;

    public AdminInitializer(UserService userService,
                            PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {

        // Só cria se não existir
        if (!userService.existByEmail(adminEmail)) {

            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setRole(Role.ADMIN);

            userService.save(admin);

            System.out.println("✅ Admin inicial criado: " + adminEmail);
        } else {
            System.out.println("⚠ Admin já existe: " + adminEmail);
        }
    }
}