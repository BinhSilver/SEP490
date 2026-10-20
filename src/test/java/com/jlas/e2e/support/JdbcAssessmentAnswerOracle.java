package com.jlas.e2e.support;

import java.sql.DriverManager;

/**
 * Test-only oracle for canonical assessment questions.
 *
 * <p>Comprehension answers cannot be inferred reliably from UI text. Selenium
 * therefore asks the same local canonical database for the expected option,
 * then selects that option through the public learner UI. No production route
 * exposes this value and the oracle never writes to the database.</p>
 */
public final class JdbcAssessmentAnswerOracle {

    private final String url;
    private final String user;
    private final String password;

    public JdbcAssessmentAnswerOracle() {
        this.url = env("JLAS_DB_URL",
            "jdbc:mysql://localhost:3306/jlas?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
        this.user = env("JLAS_DB_USER", "root");
        this.password = required("JLAS_DB_PASSWORD");
    }

    public int correctOption(long questionId) {
        String sql = "SELECT correct_option FROM assessment_question WHERE id = ? AND is_active = 1";
        try (var connection = DriverManager.getConnection(url, user, password);
             var query = connection.prepareStatement(sql)) {
            query.setLong(1, questionId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    int option = rows.getInt(1);
                    if (!rows.wasNull() && option > 0) return option;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read canonical answer for E2E question " + questionId, e);
        }
        throw new IllegalStateException("No active canonical answer for E2E question " + questionId);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }
}
