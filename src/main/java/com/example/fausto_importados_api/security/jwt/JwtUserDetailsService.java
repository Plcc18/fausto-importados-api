package com.example.fausto_importados_api.security.jwt;

import com.example.fausto_importados_api.services.UserService;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class JwtUserDetailsService implements UserDetailsService {
    private final UserService userService;

    public JwtUserDetailsService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {
        try {
            return userService.findByEmail(email);
        } catch (ResourceNotFoundException e) {
            throw new UsernameNotFoundException(
                    "Admin not found with email: " + email
            );
        }
    }
}
