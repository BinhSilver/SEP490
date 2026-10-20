package com.jlas.e2e;

import com.jlas.e2e.pages.LearningPathPage;
import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SmartTestPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OD-44 (owner 2026-10-20) on the live catalog: an N3 path studies the J-LAS "Tiếng Nhật N3" textbook while it stays
 * DRAFT (§2.28). Its chapters say "Giáo trình chưa duyệt", their first activity opens, and no status was published.
 * Reading/Listening for N3 are still a reported gap (no N3 mapping yet), so they are recorded, not asserted present.
 * Requires a fresh dedicated learner and JLAS_DB_* (read-only checks).
 */
@Tag("e2e")
class N3DraftTextbookPathE2ETest extends BaseE2ETest {
    private static final long JLAS_N3_TEXTBOOK = 4L;

    @Test
    @SuppressWarnings("unchecked")
    void n3PathStudiesTheDraftJlasN3TextbookWithAnUnreviewedNote() throws Exception {
        String baseUrl = E2EConfig.baseUrl();
        stepLog.step(driver, "Sign in", "Dedicated N3 learner is authenticated", () ->
            new LoginPage(driver).open(baseUrl).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));
        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Complete N3 survey", "N3 to N3 learning profile is saved", () ->
            survey.open(baseUrl).answerN3Profile());
        stepLog.step(driver, "Start Smart Test", "First assessment question is visible", survey::startSmartTest);
        SmartTestPage smartTest = new SmartTestPage(driver);
        stepLog.step(driver, "Complete adaptive Smart Test", "Result screen appears", () ->
            smartTest.waitUntilLoaded().completeAdaptiveTestAsCompetentLearner(60));
        stepLog.step(driver, "Create learning path", "Path page opens from assessment result", smartTest::createLearningPath);
        LearningPathPage path = new LearningPathPage(driver);
        path.waitUntilLoaded();

        Map<String, Object> current = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() })
              .then(async response => {
                const path = (await response.json()).data || {};
                done({ status: response.status, target: path.targetLevel, packages: (path.packages || []).map(pkg => ({
                  lesson: pkg.chapterLessonId == null ? null : String(pkg.chapterLessonId), note: pkg.sourceReviewNote,
                  phase: pkg.phase, types: (pkg.activities || []).map(a => a.type) })) });
              }).catch(error => done({ error: String(error) }));
            """);
        assertEquals(200L, current.get("status"), String.valueOf(current));
        List<Map<String, Object>> packages = (List<Map<String, Object>>) current.get("packages");
        List<Map<String, Object>> noted = packages.stream().filter(pkg -> pkg.get("note") != null).toList();
        assertFalse(noted.isEmpty(), "Expected chapters from the DRAFT N3 textbook: " + packages);
        assertTrue(noted.stream().allMatch(pkg -> "Giáo trình chưa duyệt — nội dung đang được rà soát.".equals(pkg.get("note"))));
        Set<String> notedTypes = new TreeSet<>();
        noted.forEach(pkg -> notedTypes.addAll((List<String>) pkg.get("types")));
        assertTrue(notedTypes.containsAll(List.of("VOCABULARY", "KANJI", "GRAMMAR", "DIALOGUE")), notedTypes.toString());
        Set<String> allTypes = new TreeSet<>();
        packages.forEach(pkg -> allTypes.addAll((List<String>) pkg.get("types")));
        Set<Long> notedLessons = new TreeSet<>();
        noted.forEach(pkg -> notedLessons.add(Long.valueOf((String) pkg.get("lesson"))));

        // Read-only DB check: every noted chapter is a lesson of textbook 4 and nothing was published.
        String url = System.getenv().getOrDefault("JLAS_DB_URL", "jdbc:mysql://localhost:3306/jlas");
        try (var connection = java.sql.DriverManager.getConnection(url,
                System.getenv().getOrDefault("JLAS_DB_USER", "root"), System.getenv("JLAS_DB_PASSWORD"));
             var query = connection.prepareStatement("""
                 select u.textbook_id, t.status, l.status, count(*) from lesson l join unit u on u.id = l.unit_id
                 join textbook t on t.id = u.textbook_id where l.id in (%s) group by u.textbook_id, t.status, l.status
                 """.formatted(String.join(",", notedLessons.stream().map(String::valueOf).toList())));
             var rows = query.executeQuery()) {
            List<String> groups = new ArrayList<>();
            while (rows.next()) groups.add(rows.getLong(1) + "/" + rows.getString(2) + "/" + rows.getString(3) + "=" + rows.getLong(4));
            assertEquals(List.of(JLAS_N3_TEXTBOOK + "/DRAFT/DRAFT=" + notedLessons.size()), groups);
            // OD-44 follow-up (owner 2026-10-20): once the Speed Master N3 seed is applied, every chapter studies
            // reading and listening too; before that the path still reports both skills as missing.
            try (var speed = connection.prepareStatement(
                    "select count(*) from assessment_question where is_active = 1 and source_code like 'LP-OWNED-SPEED-N3-C%'");
                 var count = speed.executeQuery()) {
                count.next();
                boolean seeded = count.getLong(1) > 0;
                assertEquals(seeded, notedTypes.containsAll(List.of("READING", "LISTENING")),
                    "Speed Master N3 rows=" + count.getLong(1) + " types=" + notedTypes);
                System.out.println("N3-SPEED-ROWS=" + count.getLong(1));
            }
        }
        System.out.println("N3-DRAFT-PATH target=" + current.get("target") + " notedChapters=" + notedLessons.size()
            + " notedTypes=" + notedTypes + " allTypes=" + allTypes);

        stepLog.step(driver, "Roadmap note", "Draft N3 chapters say Giáo trình chưa duyệt", () -> {
            driver.get(baseUrl + "/learning/path/v2");
            var note = wait.withTimeout(Duration.ofSeconds(30)).until(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".ilp-chapter__unreviewed")));
            assertTrue(note.getText().contains("Giáo trình chưa duyệt"), note.getText());
        });
        stepLog.step(driver, "Open the first activity", "A DRAFT N3 lesson opens its study player", () -> {
            String route = new LearningPathPage(driver).waitUntilLoaded().startFirstAvailablePackage();
            assertTrue(route.contains("/learning/v2/"), route);
            String type = route.substring(route.indexOf("/learning/v2/") + "/learning/v2/".length()).split("[?/]", 2)[0];
            String rootId = switch (type) {
                case "grammar" -> "interactiveGrammarStudyRoot";
                case "vocabulary" -> "interactiveStudyRoot";
                case "kanji" -> "interactiveKanjiStudyRoot";
                case "dialogue" -> "interactiveDialogueStudyRoot";
                default -> throw new IllegalStateException("Unexpected first N3 activity: " + route);
            };
            var root = wait.withTimeout(Duration.ofSeconds(30)).until(
                ExpectedConditions.visibilityOfElementLocated(By.id(rootId)));
            wait.until(d -> "false".equals(root.getAttribute("aria-busy")));
            assertTrue(root.getText().length() > 20, "Study player rendered without lesson content");
            assertFalse(root.getText().contains("không còn sẵn sàng"), root.getText());
        });
    }
}
