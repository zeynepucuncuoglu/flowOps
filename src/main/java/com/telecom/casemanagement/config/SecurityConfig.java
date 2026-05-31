package com.telecom.casemanagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Actuator health endpoint — public (Kubernetes liveness probe)
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Prometheus scrape — restricted to monitoring network in prod via NetworkPolicy
                .requestMatchers("/actuator/prometheus").permitAll()
                // Public read on health ping
                .requestMatchers(HttpMethod.GET, "/api/v1/cases/health").permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            // JWT filter would be added here in production
            // .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(basic -> {}); // Basic auth for local dev only

        return http.build();
    }
}
