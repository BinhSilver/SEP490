package com.jlas.e2e.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/**
 * One-off utility (not part of the regular suite) that resets the password of an
 * existing dedicated LOCAL learner account directly in the local MySQL database, so
 * an E2E run can log in as that learner without reusing any seed/demo account. A
 * second account can be reset in the same run via JLAS_TEST_EMAIL_2 /
 * JLAS_TEST_PASSWORD_2, which the two-account isolation UAT uses.
 *
 * Run once with:
 *   mvnw.cmd test -Dtest=ResetE2EPasswordTool -De2e.excludedGroups=
 *
 * Requires JLAS_TEST_EMAIL, JLAS_TEST_PASSWORD and JLAS_DB_PASSWORD in the process
 * environment. The password is passed through the environment only and is never
 * logged or stored in source.
 */
@Tag("e2e")
class ResetE2EPasswordTool {

    @Test
    void resetLocalTestUserPassword() throws Exception {
        String url = env("JLAS_DB_URL", "jdbc:mysql://localhost:3306/jlas?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
        String user = env("JLAS_DB_USER", "root");
        String password = required("JLAS_DB_PASSWORD");

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            reset(conn, required("JLAS_TEST_EMAIL"), required("JLAS_TEST_PASSWORD"));
            if (present("JLAS_TEST_EMAIL_2") && present("JLAS_TEST_PASSWORD_2")) {
                reset(conn, System.getenv("JLAS_TEST_EMAIL_2"), System.getenv("JLAS_TEST_PASSWORD_2"));
            }
        }
    }

    private static void reset(Connection conn, String email, String accountPassword) throws Exception {
        String hash = new BCryptPasswordEncoder().encode(accountPassword);
        try (PreparedStatement update = conn.prepareStatement(
            "UPDATE users SET password_hash = ?, login_type = 'LOCAL' WHERE email = ?")) {
            update.setString(1, hash);
            update.setString(2, email);
            int rows = update.executeUpdate();
            if (rows == 0) {
                throw new IllegalStateException("No user with email " + email);
            }
            System.out.println("[e2e-setup] Reset password for " + email);
        }
    }

    private static boolean present(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }
}
