package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.learning.service.impl.JpaInteractiveContentCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Release data gate, OFF by default: runs the real Learning Path content catalog ({@link JpaInteractiveContentCatalog})
 * over every published learning-path source lesson of a real MySQL database and reports the coverage gaps that would
 * stop path composition. Read-only: the test transaction is rolled back and the schema is only validated.
 *
 * <pre>
 * JLAS_AUDIT_PASSWORD=... mvn test -Dtest=LearningSourceReadinessAuditTest \
 *   -Djlas.audit.url=jdbc:mysql://localhost:3306/&lt;scratch-db&gt; -Djlas.audit.user=root \
 *   [-Djlas.audit.requireLevels=N5,N4]
 * </pre>
 * Point it at a scratch copy, never at a database that other people are writing to. The report is written to
 * {@code target/learning-source-readiness.txt}. With {@code requireLevels}, the listed levels must have no gap.
 */
@EnabledIfSystemProperty(named = "jlas.audit.url", matches = "jdbc:mysql:.+")
@DataJpaTest(properties = {"spring.profiles.include=", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = LearningSourceReadinessAuditTest.Cfg.class)
class LearningSourceReadinessAuditTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.dictionary.repository", "com.jlas.learning.repository",
            "com.jlas.auth.repository"})
    @Import(JpaInteractiveContentCatalog.class)
    static class Cfg { }

    @DynamicPropertySource
    static void auditDatabase(DynamicPropertyRegistry registry) {
        // mvn.cmd treats '&' as a command separator, so a bare URL gets the app's usual connection options appended.
        registry.add("spring.datasource.url", () -> {
            String url = System.getProperty("jlas.audit.url");
            return url.contains("?") ? url : url + "?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh"
                    + "&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        });
        registry.add("spring.datasource.username", () -> System.getProperty("jlas.audit.user", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("JLAS_AUDIT_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired JpaInteractiveContentCatalog catalog;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reportsCoverageGapsPerLevel() throws Exception {
        var lessons = jdbc.queryForList("""
                SELECT l.id, t.jlpt_level FROM lesson l JOIN unit u ON u.id = l.unit_id JOIN textbook t ON t.id = u.textbook_id
                WHERE l.status = 'PUBLISHED' AND t.status = 'PUBLISHED' AND t.is_learning_path_source = 1
                  AND t.jlpt_level IS NOT NULL ORDER BY t.jlpt_level, l.id""");
        Map<String, int[]> perLevel = new TreeMap<>();               // level -> {lessons, blocked}
        Map<String, Map<String, Integer>> reasons = new TreeMap<>(); // level -> gap code -> lessons
        List<String> detail = new ArrayList<>();
        for (var row : lessons) {
            long id = ((Number) row.get("id")).longValue();
            String level = (String) row.get("jlpt_level");
            var gaps = catalog.inspect(id, level).gaps();
            int[] counts = perLevel.computeIfAbsent(level, k -> new int[2]);
            counts[0]++;
            if (!gaps.isEmpty()) counts[1]++;
            gaps.stream().map(g -> g.reasonCode()).distinct().forEach(code ->
                    reasons.computeIfAbsent(level, k -> new TreeMap<>()).merge(code, 1, Integer::sum));
            if (!gaps.isEmpty()) detail.add(level + " lesson " + id + ": " + gaps.stream().map(g -> g.reasonCode()).distinct().toList());
        }
        var report = new StringBuilder("level | lessons | blocked | gap reasons (lessons)\n");
        perLevel.forEach((level, c) -> report.append(level).append(" | ").append(c[0]).append(" | ").append(c[1])
                .append(" | ").append(reasons.getOrDefault(level, Map.of())).append('\n'));
        report.append("\n").append(String.join("\n", detail)).append('\n');
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/learning-source-readiness.txt"), report);
        System.out.println(report);

        String required = System.getProperty("jlas.audit.requireLevels", "");
        for (String level : Arrays.stream(required.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            int[] c = perLevel.getOrDefault(level, new int[2]);
            assertTrue(c[0] > 0 && c[1] == 0, level + " must have published source lessons and no gap: " + Arrays.toString(c));
        }
    }
}
