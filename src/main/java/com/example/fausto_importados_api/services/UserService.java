package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.services.exception.DuplicateUserException;
import com.example.fausto_importados_api.services.exception.UserNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
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
                        new UserNotFoundException("Admin Not Found")
                );
    }

    // criação do admin — só pode existir um admin no sistema
    public User createAdmin(User user) {

        if (userRepository.existsByRole(Role.ADMIN)) {
            throw new DuplicateUserException("Admin already exists");
        }

        if (userRepository.existsByEmail(user.getEmail())) {
            throw new DuplicateUserException("Email already registered");
        }

        User admin = buildAdmin(user);
        return userRepository.save(admin);

    }

    private User buildAdmin(User input) {
        User admin = new User();
        admin.setEmail(input.getEmail());
        admin.setPassword(passwordEncoder.encode(input.getPassword()));
        admin.setRole(Role.ADMIN);
        admin.setActive(true);

        return admin;
    }

    public User findById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("Admin not found"));
    }

    public void deleteById(UUID id) {
        User user = findById(id);
        userRepository.deleteById(id);
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public boolean existByEmail(String email) {
        return userRepository.findByEmail(email).isPresent();
    }

    public void save(User u) {
        userRepository.save(u);
    }

    public void delete(User u) {
        userRepository.delete(u);
    }
}
