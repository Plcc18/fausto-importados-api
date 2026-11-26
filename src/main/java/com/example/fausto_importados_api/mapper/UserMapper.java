package com.example.fausto_importados_api.mapper;

import com.example.fausto_importados_api.dto.UserResponseDTO;
import com.example.fausto_importados_api.model.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponseDTO toResponseDTO(User user) {
        return new UserResponseDTO(
                user.getId(), user.getEmail(), user.getRole(), user.getActive(), user.isTotpEnabled(), user.getVersion());
    }
}
