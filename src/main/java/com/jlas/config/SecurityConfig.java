package com.jlas.config;

import com.jlas.auth.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/register", "/forgot-password", "/verify-email",
                                "/profile", "/goals", "/learning/goals", "/streak", "/retention-demo", "/error").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/assets/**", "/fonts/**",
                                "/videos/**", "/voices/**", "/flashcard/**", "/shadowing/css/**",
                                "/shadowing/js/**", "/shadowing/icon/**", "/admin/**",
                                "/uploads/**", "/favicon.ico", "/*.png", "/*.svg", "/*.webp").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/logout", "/api/auth/verify-email", "/api/auth/resend-verification",
                                "/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/google").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/dictionary/**", "/api/assessment/**",
                                "/api/learning/curriculum", "/api/learning/lessons/**",
                                "/api/flashcards/public", "/api/flashcards/*", "/api/flashcards/*/comments").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(
                                "/shadowing", "/shadowing/", "/shadowing/upload",
                                "/shadowing/create/**", "/shadowing/editor/**", "/shadowing/practice/**",
                                "/shadowing/exercise/**", "/shadowing/player/**", "/shadowing/series/**",
                                "/shadowing/conversation/**", "/create/**", "/editor/**", "/player/**",
                                "/api/videos/**", "/api/series/**", "/api/youtube/**",
                                "/api/subtitles/**", "/api/interactions/**", "/api/shadowing-practice/**",
                                "/api/pronunciation/**")
                        .authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
