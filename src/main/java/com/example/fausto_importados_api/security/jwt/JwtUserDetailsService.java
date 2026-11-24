package com.example.fausto_importados_api.security.jwt;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.UserRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class JwtUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public JwtUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Usado no login (AuthenticationManager), que ainda identifica o usuário pelo e-mail. */
    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() ->
                        new UsernameNotFoundException("Admin not found with email: " + email)
                );

        return user;
    }

    /** Usado pelo filtro JWT: o access token identifica o usuário pelo id. */
    public User loadUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("Admin not found with id: " + id));
    }
}
