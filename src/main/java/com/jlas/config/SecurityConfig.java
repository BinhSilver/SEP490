package com.jlas.config;

import com.jlas.auth.security.JwtAuthenticationFilter;
import com.jlas.moderation.security.BanEnforcementFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final BanEnforcementFilter banEnforcementFilter;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                handleUnauthenticated(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                handleAccessDenied(request, response)))
                .authorizeHttpRequests(auth -> auth
                        // Arena SSE completes through an async dispatch of an authenticated request.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/admin/jlpt", "/admin/jlpt/**", "/api/admin/jlpt/**")
                        .hasRole("CONTENT_CREATOR")
                        // JLAS2-51: System Admin area, never shared with moderation.
                        .requestMatchers("/admin/system", "/admin/system/**", "/api/admin/system/**")
                        .hasRole("SUPER_ADMIN")
                        // Landing page only: AdminPageController sends System Admin to its own area.
                        .requestMatchers("/admin", "/admin/").hasAnyRole("MODERATOR", "ADMIN", "SUPER_ADMIN")
                        // JLAS-W0250: shared admin workspace; each API narrows access further.
                        .requestMatchers("/admin/**", "/api/admin/moderation/**")
                        .hasAnyRole("MODERATOR", "ADMIN")
                        .requestMatchers("/", "/login", "/register", "/forgot-password", "/verify-email",
                                "/profile", "/profile/security/**", "/goals", "/learning/goals", "/streak", "/progress", "/credit-history", "/retention-demo", "/error").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/assets/**", "/fonts/**",
                                "/videos/**", "/voices/**", "/audio/**", "/JLPT/**", "/flashcard/**", "/shadowing/css/**",
                                "/shadowing/js/**", "/shadowing/icon/**",
                                "/uploads/**", "/favicon.ico", "/*.png", "/*.svg", "/*.webp").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/logout", "/api/auth/verify-email", "/api/auth/resend-verification",
                                "/api/auth/forgot-password", "/api/auth/verify-reset-otp", "/api/auth/reset-password", "/api/auth/google").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/learning/lessons/*/quiz").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/dictionary/**", "/api/assessment/**",
                                "/api/learning/curriculum", "/api/learning/lessons/**",
                                "/api/flashcards/public", "/api/flashcards/*", "/api/flashcards/*/comments").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/appeal.html", "/api/appeals/**", "/api/credits/**").authenticated()
                        .requestMatchers(
                                "/shadowing", "/shadowing/", "/shadowing/upload",
                                "/shadowing/create/**", "/shadowing/editor/**", "/shadowing/prepare/**", "/shadowing/practice/**",
                                "/shadowing/exercise/**", "/shadowing/player/**", "/shadowing/series/**",
                                "/shadowing/conversation/**", "/create/**", "/editor/**", "/player/**",
                                "/api/videos/**", "/api/series/**", "/api/youtube/**",
                                "/api/subtitles/**", "/api/interactions/**", "/api/shadowing-practice/**",
                                "/api/pronunciation/**")
                        .authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(banEnforcementFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void writeSecurityError(HttpServletResponse response, int status, String message, String code)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message
                + "\",\"data\":null,\"errors\":null,\"code\":\"" + code + "\"}");
    }

    /** Pages get the designed error page (templates/error.html, 403 branch); APIs keep the JSON body. */
    private static void handleAccessDenied(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN,
                "Bạn không có quyền thực hiện thao tác này.", "FORBIDDEN");
    }

    private static void handleUnauthenticated(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.ACCOUNT_LOCKED_ATTRIBUTE))) {
            if (request.getRequestURI().startsWith("/api/")) {
                writeSecurityError(response, HttpServletResponse.SC_FORBIDDEN,
                        "Tài khoản đã bị khóa. Vui lòng liên hệ quản trị viên.", "ACCOUNT_LOCKED");
            } else {
                response.sendRedirect("/login?locked=1");
            }
            return;
        }
        if (!request.getRequestURI().startsWith("/api/")) {
            String returnUrl = request.getRequestURI();
            if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
                returnUrl += "?" + request.getQueryString();
            }
            response.sendRedirect("/login?redirect=" + URLEncoder.encode(returnUrl, StandardCharsets.UTF_8));
            return;
        }

        writeSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED,
                "Vui lòng đăng nhập để tiếp tục.", "UNAUTHORIZED");
    }
}
