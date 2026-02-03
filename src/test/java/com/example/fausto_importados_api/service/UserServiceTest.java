package com.example.fausto_importados_api.service;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.services.UserService;
import com.example.fausto_importados_api.services.exception.BusinessException;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void shouldReturnUserWhenEmailExists() {
        String email = "admin@example.com";
        User user = new User();
        user.setEmail(email);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        User result = userService.findByEmail(email);

        assertNotNull(result);
        assertEquals(email, result.getEmail());

        verify(userRepository).findByEmail(email);
    }

    @Test
    void shouldThrowExceptionWhenEmailNotFound() {
        String email = "admin@example";

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                userService.findByEmail(email)
        );

        verify(userRepository).findByEmail(email);
    }

    @Test
    void shouldThrowExceptionWhenEmailAlreadyExists() {
        User input = new User();
        input.setEmail("admin@example.com");
        input.setPassword("123456");

        when(userRepository.existsByEmail(input.getEmail()))
                .thenReturn(true);

        assertThrows(BusinessException.class, () ->
                userService.createAdmin(input)
        );

        verify(userRepository).existsByEmail("admin@example.com");
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldCreateAdminSuccessfully() {
        User input = new User();
        input.setEmail("admin@example.com");
        input.setPassword("123456");

        when(userRepository.existsByRole(Role.ADMIN))
                .thenReturn(false);

        when(passwordEncoder.encode("123456"))
                .thenReturn("senhaCriptografada");

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocationOnMock -> invocationOnMock.getArguments()[0]);

        User admin = userService.createAdmin(input);

        assertNotNull(admin);
        assertEquals("admin@example.com", admin.getEmail());
        assertEquals("senhaCriptografada", admin.getPassword());

        verify(userRepository).existsByRole(Role.ADMIN);
        verify(passwordEncoder).encode("123456");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldCreateAdminDuplicatedSuccessfully() {
        User input = new User();
        input.setEmail("admin2@email.com");
        input.setPassword("123456");

        when(userRepository.existsByEmail(input.getEmail()))
                .thenReturn(false);

        when(passwordEncoder.encode("123456"))
                .thenReturn("senhaCriptografada");

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        User saved = userService.createAdmin(input);

        assertThat(saved).isNotNull();
        assertThat(saved.getEmail()).isEqualTo("admin2@email.com");
        assertThat(saved.getPassword()).isEqualTo("senhaCriptografada");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getActive()).isTrue();

        verify(userRepository).existsByEmail("admin2@email.com");
        verify(userRepository).save(any(User.class));
    }

}
