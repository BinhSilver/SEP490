package com.jlas.moderation.security;

import com.jlas.auth.security.CurrentUser;
import com.jlas.moderation.service.BulkContentGuardService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chống tải nội dung hàng loạt ({@link BulkContentGuardService}). Chạy sau {@link BanEnforcementFilter}: tài khoản đã
 * bị tạm ngưng / khoá dừng ở đó, không bị đếm thêm. Vượt ngưỡng → 429 {@code BULK_RATE_LIMITED} kèm Retry-After; lần
 * vượt được ghi vi phạm thì áp luôn mức xử lý (cảnh báo / tạm ngưng / khoá).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BulkContentGuardFilter extends OncePerRequestFilter {

    private final BulkContentGuardService guard;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        BulkContentGuardService.Kind kind = guard.classify(request.getMethod(), request.getRequestURI());
        if (kind == null || exempt()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<Long> userId = CurrentUser.currentUserId();
        // Forwarded headers are untrusted when the app is reached directly (same rule as AuthController.clientIp).
        String subject = userId.map(id -> "u:" + id).orElseGet(() -> "ip:" + request.getRemoteAddr());
        BulkContentGuardService.Hit hit = guard.hit(subject, kind);
        if (!hit.blocked()) {
            chain.doFilter(request, response);
            return;
        }
        BulkContentGuardService.Action action = null;
        if (hit.newViolation() && userId.isPresent()) {
            try {
                action = guard.recordViolation(userId.get(), hit, request.getRequestURI());
            } catch (Exception e) {
                log.error("Bulk content guard could not record violation user={}: {}", userId.get(), e.getMessage(), e);
            }
        }
        writeLimited(response, hit, action);
    }

    private static boolean exempt() {
        return BulkContentGuardService.EXEMPT_ROLES.stream().anyMatch(CurrentUser::hasRole);
    }

    /** "45 giây", "12 phút", "3 giờ" (làm tròn lên). */
    static String describeWait(long seconds) {
        if (seconds < 90) return Math.max(1, seconds) + " giây";
        if (seconds < 90 * 60) return (seconds + 59) / 60 + " phút";
        return (seconds + 3599) / 3600 + " giờ";
    }

    private static void writeLimited(HttpServletResponse response, BulkContentGuardService.Hit hit,
                                     BulkContentGuardService.Action action) throws IOException {
        boolean upload = hit.kind() == BulkContentGuardService.Kind.UPLOAD;
        String message = (upload ? "Bạn đang đăng tải nội dung quá nhanh" : "Bạn đang tải nội dung quá nhanh")
                + ", hãy thử lại sau " + describeWait(hit.retryAfterSeconds()) + ".";
        if (action == BulkContentGuardService.Action.WARN) {
            message += " Đây là cảnh báo: nếu tiếp tục, tài khoản sẽ bị tạm ngưng.";
        } else if (action == BulkContentGuardService.Action.SUSPEND) {
            message = "Tài khoản của bạn đã bị tạm ngưng vì tải nội dung hàng loạt. Xem thông báo để biết chi tiết "
                    + "hoặc gửi kháng nghị.";
        } else if (action == BulkContentGuardService.Action.LOCK) {
            message = "Tài khoản của bạn đã bị khoá vì tái phạm tải nội dung hàng loạt. Bạn có thể gửi kháng nghị.";
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(hit.retryAfterSeconds()));
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"BULK_RATE_LIMITED\",\"message\":\""
                + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"data\":{\"retryAfterSeconds\":"
                + hit.retryAfterSeconds() + (action != null ? ",\"action\":\"" + action.name() + "\"" : "") + "}}");
    }
}
