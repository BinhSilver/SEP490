package com.jlas.moderation.security;

import com.jlas.auth.security.CurrentUser;
import com.jlas.moderation.service.SanctionStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A banned account can still sign in, because it must be able to read its notifications and
 * file an appeal. Everything else is refused: APIs get 403 ACCOUNT_BANNED, pages redirect to
 * the appeal page. Runs right after JWT authentication, so a new ban applies on the next request.
 */
@Component
@RequiredArgsConstructor
public class BanEnforcementFilter extends OncePerRequestFilter {
    static final String APPEAL_PAGE = "/appeal.html";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    /** What a banned user may still reach. */
    private static final List<String> ALLOWED = List.of(
            APPEAL_PAGE, "/login", "/error", "/favicon.ico", "/*.png", "/*.svg",
            "/api/auth/**", "/api/appeals/**", "/api/notifications/**",
            "/css/**", "/js/**", "/images/**", "/assets/**", "/fonts/**",
            "/flashcard/js/**", "/flashcard/*.css", "/uploads/avatar/**", "/actuator/health/**");

    private final SanctionStatusService status;
    private final AntPathMatcher matcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Long> userId = CurrentUser.currentUserId();
        if (userId.isEmpty() || allowed(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<SanctionStatusService.ActiveBan> ban = status.activeBan(userId.get());
        if (ban.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getRequestURI().startsWith("/api/")) {
            writeBanned(response, ban.get());
        } else {
            response.sendRedirect(APPEAL_PAGE);
        }
    }

    private boolean allowed(String uri) {
        return ALLOWED.stream().anyMatch(pattern -> matcher.match(pattern, uri));
    }

    private static void writeBanned(HttpServletResponse response, SanctionStatusService.ActiveBan ban)
            throws IOException {
        String until = ban.endsAt() == null ? "vĩnh viễn" : "đến " + DATE.format(ban.endsAt());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"ACCOUNT_BANNED\",\"message\":\"Tài khoản của bạn đang bị khoá "
                + until + ". Bạn chỉ có thể xem thông báo và gửi kháng nghị.\",\"data\":{\"appealUrl\":\""
                + APPEAL_PAGE + "\"}}");
    }
}
