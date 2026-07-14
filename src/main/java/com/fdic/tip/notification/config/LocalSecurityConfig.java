package com.fdic.tip.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * LOCAL DEV ONLY. Permits everything with no auth, so you can exercise the
 * full pipeline (publish -> Event Hub emulator -> consume -> persist -> SSE
 * push) from Postman/curl without needing a real Entra AD tenant or minting
 * JWTs by hand. The Event Hubs Emulator itself has no Entra ID support
 * either, so this matches the reduced-auth nature of the whole local setup.
 *
 * Active ONLY when spring.profiles.active includes "local" - see
 * application-local.yml. Never active in any deployed environment.
 */
@Configuration
@Profile("local")
public class LocalSecurityConfig {

    @Bean
    public SecurityFilterChain localFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
