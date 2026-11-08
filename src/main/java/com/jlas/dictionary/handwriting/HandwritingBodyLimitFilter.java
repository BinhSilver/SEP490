package com.jlas.dictionary.handwriting;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Chặn body quá lớn của API nhận diện TRƯỚC khi Jackson dựng object: Bean Validation chỉ chạy sau
 * khi cả body đã được đọc vào bộ nhớ nên không tự bảo vệ được. Body hợp lệ tối đa khoảng
 * 30 nét x 500 điểm (~200KB); FE thực tế gửi vài KB sau khi làm gọn nét.
 *
 * <p>Content-Length quá lớn bị từ chối ngay (413); body chunked (không có Content-Length) bị cắt
 * khi đọc vượt giới hạn, khi đó request kết thúc bằng lỗi đọc body (400).</p>
 */
@Component
public class HandwritingBodyLimitFilter extends OncePerRequestFilter {

    static final String PATH = "/api/dictionary/handwriting/recognize";
    static final int MAX_BODY_BYTES = 256 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Dữ liệu nét vẽ quá lớn.\","
                    + "\"data\":null,\"errors\":null,\"code\":\"PAYLOAD_TOO_LARGE\"}");
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) limited = new LimitedStream(super.getInputStream());
            return limited;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream in;
        private long count;

        LimitedStream(ServletInputStream in) {
            this.in = in;
        }

        private void count(long bytes) throws IOException {
            count += bytes;
            if (count > MAX_BODY_BYTES) throw new IOException("Request body exceeds " + MAX_BODY_BYTES + " bytes");
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = in.read(buffer, off, len);
            if (n > 0) count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
