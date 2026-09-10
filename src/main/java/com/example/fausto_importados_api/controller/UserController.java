package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.UserRequestDTO;
import com.example.fausto_importados_api.dto.UserResponseDTO;
import com.example.fausto_importados_api.dto.UserUpdateDTO;
import com.example.fausto_importados_api.mapper.UserMapper;
import com.example.fausto_importados_api.model.User;
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

    @Autowired
    private UserMapper userMapper;

    // DTO de resposta padrão
    public record ApiResponse(String timeStamp, String message) {}

    // GET por ID
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponseDTO> getUser(@PathVariable UUID id) {
        User u = userService.findById(id);
        return ResponseEntity.ok(userMapper.toResponseDTO(u));
    }

    // GET por email
    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponseDTO> getUserByEmail(@PathVariable String email) {
        User u = userService.findByEmail(email);
        return ResponseEntity.ok(userMapper.toResponseDTO(u));
    }

    // GET todos
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponseDTO>> getAllUsers() {
        List<UserResponseDTO> list = userService.findAll().stream()
                .map(userMapper::toResponseDTO)
                .toList();

        return ResponseEntity.ok(list);
    }

    // POST
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> postUser(@Valid @RequestBody UserRequestDTO dto) {

        if (userService.existByEmail(dto.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse(
                            Instant.now().toString(),
                            "User already registered"
                    ));
        }

        User u = userMapper.toEntity(dto);
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
            @Valid @RequestBody UserUpdateDTO dto
    ) {
        User existing = userService.findById(id);

        userMapper.updateEntityFromRequest(existing, dto);

        if (dto.password() != null && !dto.password().isBlank()) {
            existing.setPassword(passwordEncoder.encode(existing.getPassword()));
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

