package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.services.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // DTO de resposta do usuário
    public record UserDTO(UUID id, String email, Role role) {}

    // DTO de resposta padrão
    public record ApiResponse(String timeStamp, String message) {}

    // GET por ID
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserDTO> getUser(@PathVariable UUID id) {
        User u = userService.findById(id);
        return ResponseEntity.ok(
                new UserDTO(u.getId(), u.getEmail(), u.getRole())
        );
    }

    // GET por email
    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserDTO> getUserByEmail(@PathVariable String email) {
        User u = userService.findByEmail(email);
        return ResponseEntity.ok(
                new UserDTO(u.getId(), u.getEmail(), u.getRole())
        );
    }

    // GET todos
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        List<UserDTO> list = userService.findAll().stream()
                .map(u -> new UserDTO(u.getId(), u.getEmail(), u.getRole()))
                .toList();

        return ResponseEntity.ok(list);
    }

    // POST
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> postUser(@Valid @RequestBody User u) {

        if (userService.existByEmail(u.getEmail())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse(
                            Instant.now().toString(),
                            "User already registered"
                    ));
        }

        u.setPassword(passwordEncoder.encode(u.getPassword()));
        userService.save(u);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse(
                        Instant.now().toString(),
                        "User created successfully"
                ));
    }

    // PUT
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> putUser(
            @PathVariable UUID id,
            @Valid @RequestBody User u
    ) {
        User existing = userService.findById(id);

        existing.setEmail(u.getEmail());

        if (u.getPassword() != null && !u.getPassword().isBlank()) {
            existing.setPassword(passwordEncoder.encode(u.getPassword()));
        }

        userService.save(existing);

        return ResponseEntity.ok(
                new ApiResponse(
                        Instant.now().toString(),
                        "User updated successfully"
                )
        );
    }

    // DELETE
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> deleteUser(@PathVariable UUID id) {
        User u = userService.findById(id);
        userService.delete(u);

        return ResponseEntity.ok(
                new ApiResponse(
                        Instant.now().toString(),
                        "User deleted successfully"
                )
        );
    }
}

