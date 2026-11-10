package com.jlas.auth.security;

import com.jlas.auth.util.JwtUtil;
import com.jlas.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String ACCOUNT_LOCKED_ATTRIBUTE = "jlas.accountLocked";

    private final AuthProperties authProperties;
    private final UserDetailsService userDetailsService;

    /**
     * Public file folders (see SecurityConfig). The access_token cookie comes with every request, and looking the
     * user up for each CSS/JS/image cost a database call per file without any of them using the result.
     */
    private static final List<String> PUBLIC_FILE_PREFIXES = List.of("/css/", "/js/", "/images/", "/assets/", "/fonts/",
            "/arena/js/", "/arena/images/", "/arena/app/", "/shadowing/css/", "/shadowing/js/", "/shadowing/icon/",
            "/flashcard/js/", "/uploads/");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/favicon.ico") || path.startsWith("/flashcard/") && path.endsWith(".css")
                || PUBLIC_FILE_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (JwtUtil.isTokenValid(token, authProperties.jwtSecret())) {
                    String email = JwtUtil.extractEmail(token, authProperties.jwtSecret());
                    UserDetails user = userDetailsService.loadUserByUsername(email);
                    var authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (LockedException exception) {
                SecurityContextHolder.clearContext();
                request.setAttribute(ACCOUNT_LOCKED_ATTRIBUTE, true);
            } catch (RuntimeException ignored) {
                // Invalid tokens are treated as anonymous; Security handles the 401 response.
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7).trim();
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (("access_token".equals(cookie.getName()) || "accessToken".equals(cookie.getName())
                        || "jwt".equals(cookie.getName())) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        String queryToken = request.getParameter("access_token");
        return queryToken == null || queryToken.isBlank() ? null : queryToken.trim();
    }
}
