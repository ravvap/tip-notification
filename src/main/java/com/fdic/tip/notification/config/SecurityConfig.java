package com.fdic.tip.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Entra AD (Azure AD) JWT resource server, following the same pattern used
 * elsewhere in TIP (appid/oid claim based auth, stateless sessions).
 *
 * Active for every profile EXCEPT "local" - see LocalSecurityConfig for the
 * permissive local-only alternative (Event Hubs Emulator has no Entra ID
 * support, so testing the full pipeline locally needs auth out of the way).
 *
 * NOTE: /api/v1/notifications/stream is permitted through the filter chain
 * WITHOUT a bearer header, because the browser's EventSource API cannot set
 * custom headers - the token instead arrives as a query param and is
 * validated manually inside NotificationController.stream(). Everything else
 * still goes through normal JWT validation.
 */
@Configuration
@Profile("!local")
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/notifications/stream").permitAll() // token validated manually, see note above
                .requestMatchers("/api/v1/notifications/publish").authenticated() // TODO: restrict to calling services' app IDs via your existing ServicePrincipalAllowlist pattern (see tip-cm-retention) - this is service-to-service, not end-user traffic
                .requestMatchers("/internal/events/**").authenticated() // TODO: replace with your existing ServicePrincipalAllowlist check (see tip-cm-retention) instead of end-user JWT auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }
}
