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
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                        .addHeaderWriter((request, response) -> {
                            // Only this admin preview can be embedded by the same website.
                            boolean preview = request.getRequestURI().equals(
                                    request.getContextPath() + "/admin/game-effects/preview");
                            response.setHeader("X-Frame-Options", preview ? "SAMEORIGIN" : "DENY");
                        }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                handleUnauthenticated(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                handleAccessDenied(request, response)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/dev/**").hasRole("ADMIN")
                        // Arena SSE completes through an async dispatch of an authenticated request.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/admin/kanji-map", "/api/admin/kanji-map/**").hasRole("CONTENT_CREATOR")
                        .requestMatchers("/admin/jlpt", "/admin/jlpt/**", "/api/admin/jlpt/**")
                        .hasRole("CONTENT_CREATOR")
                        .requestMatchers("/admin/verb-practice", "/api/admin/verb-practice/**")
                        .hasRole("CONTENT_CREATOR")
                        // JLAS2-51: System Admin area; moderators have no access.
                        .requestMatchers("/admin/system", "/admin/system/**", "/api/admin/system/**")
                        .hasRole("ADMIN")
                        // System Admin also runs moderation (incl. sanctions); MODERATOR has moderation only.
                        .requestMatchers("/admin", "/admin/").hasAnyRole("MODERATOR", "ADMIN")
                        // JLAS-W0250: shared admin workspace; each API narrows access further.
                        .requestMatchers("/admin/game-skins", "/api/admin/game-skins", "/api/admin/game-skins/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/admin/avatar-frames", "/api/admin/avatar-frames", "/api/admin/avatar-frames/**")
                        .hasRole("ADMIN")
                        // [JLAS2-270] Learners submit; only content creators/admins review learning error reports.
                        .requestMatchers("/admin/learning-content-reports", "/api/admin/learning-content-reports", "/api/admin/learning-content-reports/**")
                        .hasAnyRole("CONTENT_CREATOR", "ADMIN")
                        // [JLAS-W0108] Báo sai lộ trình JLPT: hàng chờ người biên soạn.
                        .requestMatchers("/admin/learning-path", "/admin/learning-path/**", "/api/admin/learning-path/**")
                        .hasAnyRole("CONTENT_CREATOR", "ADMIN")
                        // Duyệt nội dung do gia sư AI tạo ra: hàng chờ người biên soạn.
                        .requestMatchers("/admin/tutor-review", "/admin/tutor-review/**", "/api/admin/tutor-review/**")
                        .hasAnyRole("CONTENT_CREATOR", "ADMIN")
                        // Báo cáo Cộng đồng: người biên soạn (content admin) xử lý mọi báo cáo bài viết / bình luận.
                        .requestMatchers("/admin/community-reports", "/api/admin/community-reports", "/api/admin/community-reports/**")
                        .hasAnyRole("CONTENT_CREATOR", "MODERATOR", "ADMIN")
                        .requestMatchers("/admin/**", "/api/admin/moderation/**")
                        .hasAnyRole("MODERATOR", "ADMIN")
                        // [JLAS2-37] Gỡ trực tiếp bài viết / bình luận cộng đồng: chặn theo vai trò TRƯỚC khi đọc và kiểm
                        // tra body, để người không phải MODERATOR/ADMIN/CONTENT_CREATOR luôn nhận 403 (không phải 400 vì thiếu lý do).
                        .requestMatchers(HttpMethod.POST, "/api/community/posts/*/remove", "/api/community/comments/*/remove")
                        .hasAnyRole("MODERATOR", "ADMIN", "CONTENT_CREATOR")
                        .requestMatchers("/", "/premium", "/credits", "/login", "/register", "/forgot-password", "/verify-email",
                                "/profile", "/profile/security/**", "/goals", "/learning/goals", "/streak", "/kana-connect", "/credit-history", "/retention-demo", "/practice/verbs", "/error", "/about", "/policy").permitAll()
                        .requestMatchers("/dictionary/kanji-map", "/dictionary/kanji-map.html").permitAll()
                        .requestMatchers("/search", "/search/**", "/dictionary/index.html",
                                "/dictionary/css/**", "/dictionary/js/**").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/assets/**", "/fonts/**",
                                "/arena/js/**", "/arena/images/**", "/arena/app/**", "/arena/audio/memory/**",
                                "/videos/**", "/voices/**", "/audio/**", "/JLPT/**", "/flashcard/**", "/shadowing/css/**",
                                "/shadowing/js/**", "/shadowing/icon/**",
                                "/uploads/**", "/favicon.ico", "/*.png", "/*.svg", "/*.webp").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/logout", "/api/auth/verify-email", "/api/auth/resend-verification",
                                "/api/auth/forgot-password", "/api/auth/verify-reset-otp", "/api/auth/reset-password", "/api/auth/google").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/dictionary/handwriting/recognize").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/learning/lessons/*/quiz").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/premium/plans", "/api/credits/packages", "/api/dictionary/**", "/api/assessment/**",
                                "/api/learning/curriculum", "/api/learning/lessons/**",
                                "/api/flashcards/public", "/api/flashcards/*", "/api/flashcards/*/comments").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Danh mục hiển thị khung avatar: khung hiện cả ở trang không cần đăng nhập.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/avatar-frames").permitAll()
                        .requestMatchers("/appeal.html", "/api/appeals/**", "/api/credits/**").authenticated()
                        .requestMatchers("/api/mission/kana-connect/**").authenticated()
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
