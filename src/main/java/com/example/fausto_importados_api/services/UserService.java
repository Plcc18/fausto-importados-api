package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.services.exception.BusinessException;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class UserService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^(?=.{1,64}@)[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*@" +
                    "[^-][A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*(\\.[A-Za-z]{2,})$"
    );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // login Spring Security
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Admin Not Found")
                );
    }

    // criação do admin (apenas umas vez)
    public User createAdmin(User user) {

        if (userRepository.existsByRole(Role.ADMIN)) {
            throw new BusinessException("Admin already registered");
        }

        User admin = buildAdmin(user);
        return userRepository.save(admin);

    }

    private User buildAdmin(User input) {
        User admin = new User();
        admin.setEmail(input.getEmail());
        admin.setPassword(input.getPassword());

        admin.setPassword(passwordEncoder.encode(input.getPassword()));

        return admin;
    }
}
