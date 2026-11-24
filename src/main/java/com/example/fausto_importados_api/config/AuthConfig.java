package com.example.fausto_importados_api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    /** Relógio injetável: os testes o substituem para avançar o tempo sem dormir. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
