package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.User;

import com.example.fausto_importados_api.model.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void deveBuscarUsuarioPorEmail() {
        User user = new User();
        user.setEmail("teste@email.com");
        user.setPassword("123");
        user.setActive(true);
        user.setRole(Role.ADMIN);

        userRepository.save(user);

        Optional<User> result = userRepository.findByEmail("teste@email.com");

        assertThat(result).isPresent();
        assertThat(result.get().getEmail()).isEqualTo("teste@email.com");
    }


    @Test
    void naoDeveEncontrarEmailInexistente() {
        Optional<User> result = userRepository.findByEmail("naoexiste@email.com");

        assertThat(result).isEmpty();
    }
}

