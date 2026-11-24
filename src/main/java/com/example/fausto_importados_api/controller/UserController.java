package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.UserActiveDTO;
import com.example.fausto_importados_api.dto.UserCredentialsResponseDTO;
import com.example.fausto_importados_api.dto.UserRequestDTO;
import com.example.fausto_importados_api.dto.UserResponseDTO;
import com.example.fausto_importados_api.dto.UserUpdateDTO;
import com.example.fausto_importados_api.mapper.UserMapper;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.security.reauth.RequiresReauth;
import com.example.fausto_importados_api.services.AdminAccountService;
import com.example.fausto_importados_api.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gestão de contas administrativas. Leitura: qualquer ADMIN. Alterações: ADMIN com reautenticação recente
 * (código 2FA), e sempre registradas na auditoria. Não existe rota que defina a senha de OUTRA pessoa: contas
 * novas e redefinições recebem uma senha temporária gerada pelo servidor.
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final AdminAccountService accounts;
    private final UserMapper userMapper;

    public UserController(UserService userService, AdminAccountService accounts, UserMapper userMapper) {
        this.userService = userService;
        this.accounts = accounts;
        this.userMapper = userMapper;
    }

    // DTO de resposta padrão
    public record ApiResponse(String timeStamp, String message) {}

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponseDTO> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(userMapper.toResponseDTO(userService.findById(id)));
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponseDTO> getUserByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userMapper.toResponseDTO(userService.findByEmail(email)));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserResponseDTO>> getAllUsers() {
        List<UserResponseDTO> list = userService.findAll().stream()
                .map(userMapper::toResponseDTO)
                .toList();
        return ResponseEntity.ok(list);
    }

    /** Cria um administrador. Devolve a senha temporária UMA vez; o novo admin a troca ao cadastrar o 2FA. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<UserCredentialsResponseDTO> postUser(@Valid @RequestBody UserRequestDTO dto) {
        AdminAccountService.Credentials created = accounts.create(dto.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(toCredentials(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<ApiResponse> putUser(@PathVariable UUID id, @Valid @RequestBody UserUpdateDTO dto) {
        accounts.changeEmail(id, dto.email());
        return ResponseEntity.ok(new ApiResponse(Instant.now().toString(), "User updated successfully"));
    }

    /** Ativa ou desativa uma conta. Desativar encerra todas as sessões dela na hora. */
    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<UserResponseDTO> setActive(@PathVariable UUID id, @Valid @RequestBody UserActiveDTO dto) {
        return ResponseEntity.ok(userMapper.toResponseDTO(accounts.setActive(id, dto.active())));
    }

    /** Senha esquecida ou aparelho perdido: nova senha temporária e 2FA a refazer. */
    @PostMapping("/{id}/reset-credentials")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<UserCredentialsResponseDTO> resetCredentials(@PathVariable UUID id) {
        return ResponseEntity.ok(toCredentials(accounts.resetCredentials(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<ApiResponse> deleteUser(@PathVariable UUID id) {
        accounts.delete(id);
        return ResponseEntity.ok(new ApiResponse(Instant.now().toString(), "User deleted successfully"));
    }

    private UserCredentialsResponseDTO toCredentials(AdminAccountService.Credentials credentials) {
        User user = credentials.user();
        return new UserCredentialsResponseDTO(
                user.getId(), user.getEmail(), credentials.temporaryPassword(), user.getEnrollmentExpiresAt());
    }
}
