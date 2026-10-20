package com.jlas.e2e.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One-off utility (not part of the regular suite) that provisions a fresh,
 * real LOCAL account directly in the local MySQL database for the Selenium
 * E2E test to log into — used instead of reusing any seed/demo account.
 *
 * Run once with:
 *   mvnw.cmd test -Dtest=CreateE2ETestUserTool -De2e.excludedGroups=
 *
 * Requires JLAS_TEST_EMAIL, JLAS_TEST_PASSWORD and JLAS_DB_PASSWORD in the
 * process environment. Never logs the password or stores it in source.
 */
@Tag("e2e")
class CreateE2ETestUserTool {

    @Test
    void createLocalTestUser() throws Exception {
        String email = required("JLAS_TEST_EMAIL");
        String accountPassword = required("JLAS_TEST_PASSWORD");
        String url = env("JLAS_DB_URL", "jdbc:mysql://localhost:3306/jlas?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
        String user = env("JLAS_DB_USER", "root");
        String password = required("JLAS_DB_PASSWORD");

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            Long existingId = findUserId(conn, email);
            if (existingId != null) {
                System.out.println("[e2e-setup] Dedicated test user already exists (id=" + existingId + ")");
                return;
            }

            long roleId = findLearnerRoleId(conn);
            String hash = new BCryptPasswordEncoder().encode(accountPassword);

            try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO users (role_id, email, password_hash, login_type, full_name, is_verified) "
                    + "VALUES (?, ?, ?, 'LOCAL', ?, 1)")) {
                insert.setLong(1, roleId);
                insert.setString(2, email);
                insert.setString(3, hash);
                insert.setString(4, "Selenium E2E Tester");
                insert.executeUpdate();
            }

            System.out.println("[e2e-setup] Created dedicated local test user");
        }
    }

    private static Long findUserId(Connection conn, String email) throws Exception {
        try (PreparedStatement select = conn.prepareStatement("SELECT id FROM users WHERE email = ?")) {
            select.setString(1, email);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static long findLearnerRoleId(Connection conn) throws Exception {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id FROM roles WHERE name = 'LEARNER'")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        throw new IllegalStateException("No LEARNER role found in roles table");
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
