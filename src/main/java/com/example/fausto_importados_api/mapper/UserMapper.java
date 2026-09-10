package com.example.fausto_importados_api.mapper;

import com.example.fausto_importados_api.dto.UserRequestDTO;
import com.example.fausto_importados_api.dto.UserResponseDTO;
import com.example.fausto_importados_api.dto.UserUpdateDTO;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    // Único papel existente é ADMIN — quem cria um User por aqui está criando um admin
    public User toEntity(UserRequestDTO dto) {
        User user = new User();
        user.setEmail(dto.email());
        user.setPassword(dto.password());
        user.setRole(Role.ADMIN);
        user.setActive(true);
        return user;
    }

    public void updateEntityFromRequest(User existing, UserUpdateDTO dto) {
        existing.setEmail(dto.email());

        if (dto.password() != null && !dto.password().isBlank()) {
            existing.setPassword(dto.password());
        }
    }

    public UserResponseDTO toResponseDTO(User user) {
        return new UserResponseDTO(user.getId(), user.getEmail(), user.getRole());
    }
}
