package com.capitec.connect.fraudengine.infrastructure.config;

import com.capitec.connect.fraudengine.infrastructure.security.JwtAuthConverter;
import com.capitec.connect.fraudengine.infrastructure.security.SecurityErrorHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityConfig {

    private final JwtAuthConverter   jwtAuthConverter;
    private final SecurityErrorHandlers securityErrorHandlers;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs",
                    "/v3/api-docs/**"
                ).permitAll()
                .requestMatchers("/api/v1/fraud-cases", "/api/v1/fraud-cases/**").hasRole("FRAUD_ANALYST")
                .requestMatchers(HttpMethod.POST, "/api/v1/transactions/evaluate")
                    .hasAnyRole("TRANSACTION_INGESTOR", "FRAUD_ANALYST")
                // FRAUD_VIEWER used to sit in this list. Nothing reads every account except
                // FRAUD_ANALYST, so a viewer was a role with a permission that could never be
                // granted: AccountAccessPolicy would still have refused anything but its own
                // account. Removing it keeps one read-all role instead of two, one of which is
                // dead.
                .requestMatchers("/api/v1/fraud-alerts/**")
                    .hasAnyRole("FRAUD_ANALYST", "ACCOUNT_HOLDER")
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint(securityErrorHandlers)
                .accessDeniedHandler(securityErrorHandlers)
            )
            .oauth2ResourceServer(resourceServer -> resourceServer
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                .authenticationEntryPoint(securityErrorHandlers)
                .accessDeniedHandler(securityErrorHandlers)
            );

        return http.build();
    }
}
