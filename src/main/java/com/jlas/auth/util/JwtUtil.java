package com.jlas.auth.util;

/**
 * Utility hỗ trợ xử lý JWT token cho module auth.
 * TODO: Thêm dependency jjwt và triển khai các phương thức.
 */
public class JwtUtil {

    private JwtUtil() { /* Utility class – không khởi tạo */ }

    /**
     * Tạo JWT token từ email người dùng.
     *
     * @param email   email tài khoản
     * @param secret  khóa bí mật ký token
     * @return JWT string
     */
    public static String generateToken(String email, String secret) {
        // TODO: Implement using io.jsonwebtoken (jjwt)
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Xác thực và lấy email từ JWT token.
     *
     * @param token  JWT string
     * @param secret khóa bí mật
     * @return email được lưu trong token
     */
    public static String extractEmail(String token, String secret) {
        // TODO: Implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Kiểm tra token còn hạn hay không.
     *
     * @param token  JWT string
     * @param secret khóa bí mật
     * @return {@code true} nếu token hợp lệ và chưa hết hạn
     */
    public static boolean isTokenValid(String token, String secret) {
        // TODO: Implement
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
