package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.concurrency.IdempotencyService;
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
    private final IdempotencyService idempotency;

    public UserController(
            UserService userService,
            AdminAccountService accounts,
            UserMapper userMapper,
            IdempotencyService idempotency
    ) {
        this.userService = userService;
        this.accounts = accounts;
        this.userMapper = userMapper;
        this.idempotency = idempotency;
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
    public ResponseEntity<?> postUser(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody UserRequestDTO dto
    ) {
        return idempotency.execute(idempotencyKey, "users.create", dto,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(toCredentials(accounts.create(dto.email()))),
                UserController::withoutPassword);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<ApiResponse> putUser(@PathVariable UUID id, @Valid @RequestBody UserUpdateDTO dto) {
        accounts.changeEmail(id, dto.email(), dto.version());
        return ResponseEntity.ok(new ApiResponse(Instant.now().toString(), "User updated successfully"));
    }

    /** Ativa ou desativa uma conta. Desativar encerra todas as sessões dela na hora. */
    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<UserResponseDTO> setActive(@PathVariable UUID id, @Valid @RequestBody UserActiveDTO dto) {
        return ResponseEntity.ok(userMapper.toResponseDTO(accounts.setActive(id, dto.active(), dto.version())));
    }

    /** Senha esquecida ou aparelho perdido: nova senha temporária e 2FA a refazer. */
    @PostMapping("/{id}/reset-credentials")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<?> resetCredentials(
            @PathVariable UUID id,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey
    ) {
        return idempotency.execute(idempotencyKey, "users.reset-credentials:" + id, null,
                () -> ResponseEntity.ok(toCredentials(accounts.resetCredentials(id))),
                UserController::withoutPassword);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<ApiResponse> deleteUser(@PathVariable UUID id) {
        accounts.delete(id);
        return ResponseEntity.ok(new ApiResponse(Instant.now().toString(), "User deleted successfully"));
    }

    /** A senha temporária aparece uma única vez: uma repetição idempotente devolve o resto, sem ela. */
    private static UserCredentialsResponseDTO withoutPassword(UserCredentialsResponseDTO response) {
        return new UserCredentialsResponseDTO(
                response.id(), response.email(), null, response.enrollmentExpiresAt());
    }

    private UserCredentialsResponseDTO toCredentials(AdminAccountService.Credentials credentials) {
        User user = credentials.user();
        return new UserCredentialsResponseDTO(
                user.getId(), user.getEmail(), credentials.temporaryPassword(), user.getEnrollmentExpiresAt());
    }
}
